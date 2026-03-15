"""
Chargeback evidence assembly and auto-decisioning engine.

When a chargeback notification arrives from the card network (via the
processor's dispute API), this engine:

1. Parses the dispute reason code and deadline
2. Fans out to internal services to assemble evidence
3. Evaluates evidence strength against the reason code
4. Decides whether to represent (fight), accept (concede), or escalate

Architecture:
  Dispute Notification -> Intake Service -> Evidence Orchestrator
                                                |
                          +---------------------+---------------------+
                          |           |          |          |         |
                     Transaction   Receipt   Merchant    EMV      Order
                     Service       Service   Service   Crypto    Service
                          |           |          |          |         |
                          +---------------------+---------------------+
                                                |
                                         Decisioning Engine
                                                |
                                    +--------+--------+
                                    |        |        |
                               Represent  Accept  Escalate

Target metrics:
  - 85%+ disputes auto-resolved (no human analyst)
  - 70%+ win rate on represented disputes
  - Evidence assembly latency < 5 seconds
"""

import logging
import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from enum import Enum
from typing import Optional

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class DisputeReasonCode(Enum):
    """
    Visa and Mastercard chargeback reason codes (simplified).
    Each code has different evidence requirements and win rate profiles.
    """
    # Fraud-related
    FRAUD_CP_COUNTERFEIT = "10.1"     # Visa: EMV counterfeit
    FRAUD_CP_LOST_STOLEN = "10.2"     # Visa: Lost/stolen card
    FRAUD_CNP = "10.4"                # Visa: Card-not-present fraud
    # Consumer disputes
    MERCHANDISE_NOT_RECEIVED = "13.1"
    NOT_AS_DESCRIBED = "13.3"
    # Processing errors
    DUPLICATE_PROCESSING = "12.1"
    INCORRECT_AMOUNT = "12.3"
    # Authorization
    NO_AUTHORIZATION = "11.1"


class DisputeDecision(Enum):
    REPRESENT = "represent"    # Fight the chargeback with evidence
    ACCEPT = "accept"          # Concede the chargeback
    ESCALATE = "escalate"      # Route to human analyst


@dataclass
class DisputeNotification:
    """Inbound chargeback notification from the card network."""
    dispute_id: str
    transaction_id: str
    merchant_id: str
    card_network: str           # VISA, MASTERCARD, AMEX
    reason_code: str
    amount_cents: int
    currency: str
    deadline: datetime          # Representment deadline (miss = auto-loss)
    cardholder_name: str = ""


@dataclass
class EvidencePackage:
    """Assembled evidence for chargeback representment."""
    dispute_id: str
    transaction_id: str

    # Evidence items (each is Optional because not all items are available)
    emv_cryptogram: Optional[str] = None          # Proves chip card was present
    signed_receipt_url: Optional[str] = None       # Digital signature from POS
    tip_amount_cents: Optional[int] = None         # Tip = cardholder was present
    order_details: Optional[dict] = None           # Itemized order from POS
    server_name: Optional[str] = None
    terminal_id: Optional[str] = None
    avs_match: Optional[bool] = None               # Address verification result
    cvv_match: Optional[bool] = None
    three_ds_authenticated: Optional[bool] = None  # 3DS challenge completed
    delivery_confirmation: Optional[str] = None

    # Computed fields
    evidence_strength: float = 0.0
    assembly_latency_ms: int = 0

    def compute_strength(self, reason_code: str) -> float:
        """
        Compute evidence strength score (0.0 to 1.0) based on available
        evidence and the specific reason code.

        Different reason codes require different evidence:
        - Counterfeit fraud: EMV cryptogram is dispositive
        - Lost/stolen: Signed receipt + tip proves cardholder presence
        - CNP fraud: 3DS authentication shifts liability to issuer
        """
        score = 0.0

        if reason_code in (DisputeReasonCode.FRAUD_CP_COUNTERFEIT.value,
                           DisputeReasonCode.FRAUD_CP_LOST_STOLEN.value):
            # Card-present fraud: EMV cryptogram is the strongest evidence
            if self.emv_cryptogram:
                score += 0.40
            if self.signed_receipt_url:
                score += 0.25
            if self.tip_amount_cents and self.tip_amount_cents > 0:
                score += 0.15  # Tip proves cardholder authorized the transaction
            if self.order_details:
                score += 0.10
            if self.server_name:
                score += 0.05
            if self.terminal_id:
                score += 0.05

        elif reason_code == DisputeReasonCode.FRAUD_CNP.value:
            # Card-not-present fraud: 3DS is dispositive (liability shift)
            if self.three_ds_authenticated:
                score += 0.50  # Liability shifts to issuer
            if self.avs_match:
                score += 0.15
            if self.cvv_match:
                score += 0.15
            if self.delivery_confirmation:
                score += 0.15
            if self.order_details:
                score += 0.05

        elif reason_code == DisputeReasonCode.DUPLICATE_PROCESSING.value:
            # Duplicate processing: need to show transactions are distinct
            if self.order_details:
                score += 0.50  # Different order IDs prove distinct transactions
            if self.signed_receipt_url:
                score += 0.30
            if self.terminal_id:
                score += 0.20

        else:
            # Default evidence evaluation
            if self.signed_receipt_url:
                score += 0.30
            if self.order_details:
                score += 0.30
            if self.emv_cryptogram:
                score += 0.20
            if self.delivery_confirmation:
                score += 0.20

        self.evidence_strength = min(score, 1.0)
        return self.evidence_strength


class ChargebackOrchestrator:
    """
    Orchestrates evidence assembly and auto-decisioning for chargebacks.

    The orchestrator fans out to multiple internal services in parallel
    to assemble the evidence package within the 5-second latency target.
    """

    # Auto-decisioning thresholds
    REPRESENT_THRESHOLD = 0.60    # Evidence strength above this = auto-represent
    ACCEPT_THRESHOLD = 0.25       # Evidence strength below this = auto-accept
    MIN_AMOUNT_TO_FIGHT = 2500    # $25 minimum to justify representment costs

    def __init__(self):
        # In production: inject service clients for each evidence source
        pass

    async def process_dispute(self, notification: DisputeNotification) -> dict:
        """
        Process a chargeback notification end-to-end.

        Returns the decision and evidence package.
        """
        start_time = datetime.now()

        # Check deadline (no point assembling evidence if deadline passed)
        if notification.deadline < datetime.now():
            logger.warning("Dispute %s deadline passed, auto-accepting",
                          notification.dispute_id)
            return {
                "decision": DisputeDecision.ACCEPT.value,
                "reason": "deadline_passed",
            }

        # Assemble evidence (parallel fan-out)
        evidence = await self._assemble_evidence(notification)

        assembly_ms = int((datetime.now() - start_time).total_seconds() * 1000)
        evidence.assembly_latency_ms = assembly_ms

        # Compute evidence strength for this reason code
        strength = evidence.compute_strength(notification.reason_code)

        # Auto-decisioning
        decision = self._decide(notification, evidence)

        logger.info(
            "Dispute %s: decision=%s, evidence_strength=%.2f, "
            "assembly_ms=%d, reason_code=%s, amount=$%.2f",
            notification.dispute_id,
            decision.value,
            strength,
            assembly_ms,
            notification.reason_code,
            notification.amount_cents / 100,
        )

        return {
            "dispute_id": notification.dispute_id,
            "decision": decision.value,
            "evidence_strength": round(strength, 3),
            "assembly_latency_ms": assembly_ms,
            "reason_code": notification.reason_code,
            "evidence_items": self._summarize_evidence(evidence),
        }

    async def _assemble_evidence(self,
                                  notification: DisputeNotification) -> EvidencePackage:
        """
        Fan out to internal services to assemble evidence.
        All calls are made concurrently to minimize latency.
        """
        evidence = EvidencePackage(
            dispute_id=notification.dispute_id,
            transaction_id=notification.transaction_id,
        )

        # Parallel evidence retrieval (simulated)
        # In production, these are async HTTP calls to internal services
        tasks = [
            self._fetch_transaction_details(notification.transaction_id, evidence),
            self._fetch_receipt(notification.transaction_id, evidence),
            self._fetch_order_details(notification.transaction_id, evidence),
            self._fetch_emv_cryptogram(notification.transaction_id, evidence),
        ]

        await asyncio.gather(*tasks, return_exceptions=True)
        return evidence

    async def _fetch_transaction_details(self, txn_id: str,
                                          evidence: EvidencePackage):
        """Fetch auth details, AVS/CVV results, terminal info."""
        await asyncio.sleep(0.01)  # Simulated latency
        evidence.terminal_id = "term_001"
        evidence.avs_match = True
        evidence.cvv_match = True
        evidence.server_name = "Jane D."

    async def _fetch_receipt(self, txn_id: str, evidence: EvidencePackage):
        """Fetch signed receipt image URL from the receipt service."""
        await asyncio.sleep(0.02)
        evidence.signed_receipt_url = f"https://receipts.internal/signed/{txn_id}.png"
        evidence.tip_amount_cents = 800  # $8 tip

    async def _fetch_order_details(self, txn_id: str,
                                    evidence: EvidencePackage):
        """Fetch itemized order from the POS order service."""
        await asyncio.sleep(0.015)
        evidence.order_details = {
            "order_id": f"ord_{txn_id}",
            "items": [
                {"name": "Grilled Salmon", "price_cents": 2800, "qty": 1},
                {"name": "Caesar Salad", "price_cents": 1200, "qty": 1},
                {"name": "House Wine", "price_cents": 1400, "qty": 1},
            ],
            "subtotal_cents": 5400,
            "tax_cents": 460,
            "tip_cents": 800,
            "total_cents": 6660,
        }

    async def _fetch_emv_cryptogram(self, txn_id: str,
                                     evidence: EvidencePackage):
        """Fetch EMV cryptogram proving chip card was physically present."""
        await asyncio.sleep(0.005)
        evidence.emv_cryptogram = "ARQC:8A023030:9F26083AE2F..."  # Truncated

    def _decide(self, notification: DisputeNotification,
                evidence: EvidencePackage) -> DisputeDecision:
        """
        Auto-decisioning logic.

        Decision hierarchy:
        1. Amount too small to fight -> ACCEPT
        2. Evidence strength above threshold -> REPRESENT
        3. Evidence strength below threshold -> ACCEPT
        4. Gray zone -> ESCALATE to human analyst
        """
        # Small-dollar disputes: not worth the representment cost
        if notification.amount_cents < self.MIN_AMOUNT_TO_FIGHT:
            return DisputeDecision.ACCEPT

        # Strong evidence: auto-represent
        if evidence.evidence_strength >= self.REPRESENT_THRESHOLD:
            return DisputeDecision.REPRESENT

        # Weak evidence: auto-accept
        if evidence.evidence_strength < self.ACCEPT_THRESHOLD:
            return DisputeDecision.ACCEPT

        # Gray zone: escalate to human analyst
        return DisputeDecision.ESCALATE

    @staticmethod
    def _summarize_evidence(evidence: EvidencePackage) -> dict:
        """Summarize available evidence items for logging and API response."""
        return {
            "emv_cryptogram": evidence.emv_cryptogram is not None,
            "signed_receipt": evidence.signed_receipt_url is not None,
            "tip_present": (evidence.tip_amount_cents or 0) > 0,
            "order_details": evidence.order_details is not None,
            "avs_match": evidence.avs_match,
            "cvv_match": evidence.cvv_match,
            "three_ds": evidence.three_ds_authenticated,
        }


async def main():
    """Simulate processing a chargeback dispute."""
    orchestrator = ChargebackOrchestrator()

    # Simulate a CP fraud dispute with strong evidence
    dispute = DisputeNotification(
        dispute_id="dsp_001",
        transaction_id="txn_abc123",
        merchant_id="mch_downtown_bistro",
        card_network="VISA",
        reason_code=DisputeReasonCode.FRAUD_CP_LOST_STOLEN.value,
        amount_cents=6660,
        currency="USD",
        deadline=datetime.now() + timedelta(days=25),
        cardholder_name="John Doe",
    )

    result = await orchestrator.process_dispute(dispute)
    logger.info("Dispute result: %s", result)

    # Simulate a CNP fraud dispute with weak evidence
    cnp_dispute = DisputeNotification(
        dispute_id="dsp_002",
        transaction_id="txn_online_789",
        merchant_id="mch_pizza_chain_042",
        card_network="MASTERCARD",
        reason_code=DisputeReasonCode.FRAUD_CNP.value,
        amount_cents=8900,
        currency="USD",
        deadline=datetime.now() + timedelta(days=40),
    )

    result2 = await orchestrator.process_dispute(cnp_dispute)
    logger.info("CNP dispute result: %s", result2)


if __name__ == "__main__":
    asyncio.run(main())
