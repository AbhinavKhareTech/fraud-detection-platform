"""
Real-time feature engineering pipeline for fraud detection.

Consumes transaction events from Kafka, computes sliding-window features,
and writes them to Redis for online serving by the fraud scoring service.

Architecture:
  Kafka (transaction events) -> Feature Pipeline -> Redis (online store)
                                                 -> PostgreSQL (training store)

In production, this runs as a Flink/Spark Streaming job. This reference
implementation uses a single-threaded consumer for clarity and testability.

Feature categories:
  1. Velocity: Transaction counts over sliding windows (1h, 24h, 7d)
  2. Behavioral: Patterns derived from historical transaction sequences
  3. Restaurant-specific: Signals from POS operational data
  4. Identity: Device and address signals (CNP only)
"""

import json
import time
import logging
from datetime import datetime, timedelta, timezone
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Optional

from feature_definitions import FEATURE_DEFINITIONS
from restaurant_features import RestaurantFeatureComputer

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@dataclass
class TransactionEvent:
    """Inbound transaction event from the payment gateway Kafka topic."""
    transaction_id: str
    merchant_id: str
    card_token: str
    amount_cents: int
    currency: str
    channel: str          # CARD_PRESENT or CARD_NOT_PRESENT
    entry_mode: str
    terminal_id: Optional[str] = None
    server_id: Optional[str] = None
    ip_address: Optional[str] = None
    device_fingerprint: Optional[str] = None
    email: Optional[str] = None
    timestamp: str = ""
    latitude: Optional[float] = None
    longitude: Optional[float] = None

    @classmethod
    def from_json(cls, data: dict) -> "TransactionEvent":
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


class FeaturePipeline:
    """
    Streaming feature computation pipeline.

    Maintains in-memory sliding windows for real-time feature computation.
    In production, these windows would be managed by Flink state backends
    with checkpointing for fault tolerance.
    """

    def __init__(self, redis_client=None, pg_client=None):
        self.redis = redis_client
        self.pg = pg_client
        self.restaurant_computer = RestaurantFeatureComputer()

        # In-memory sliding windows (production: Flink managed state)
        # Key: card_token -> list of (timestamp, amount, merchant_id, lat, lon)
        self.card_windows: dict[str, list] = defaultdict(list)
        # Key: merchant_id -> list of (timestamp, amount)
        self.merchant_windows: dict[str, list] = defaultdict(list)
        # Key: device_fingerprint -> set of card_tokens
        self.device_cards: dict[str, set] = defaultdict(set)
        # Key: merchant_id:server_id -> {refunds: int, voids: int, total: int}
        self.server_stats: dict[str, dict] = defaultdict(
            lambda: {"refunds": 0, "voids": 0, "total": 0}
        )

    def process_event(self, event: TransactionEvent) -> dict:
        """
        Process a single transaction event and compute all features.

        Returns the computed feature dict for testing/logging.
        In production, this method would be called by the Kafka consumer
        for each message in the transaction topic.
        """
        ts = datetime.fromisoformat(event.timestamp.replace("Z", "+00:00"))
        features = {}

        # 1. Card velocity features
        card_features = self._compute_card_velocity(event, ts)
        features.update(card_features)

        # 2. Geographic velocity
        geo_features = self._compute_geo_velocity(event, ts)
        features.update(geo_features)

        # 3. Merchant features
        merchant_features = self._compute_merchant_features(event, ts)
        features.update(merchant_features)

        # 4. Restaurant-specific features
        restaurant_features = self.restaurant_computer.compute(event, features)
        features.update(restaurant_features)

        # 5. Device features (CNP only)
        if event.device_fingerprint:
            device_features = self._compute_device_features(event)
            features.update(device_features)

        # 6. Server behavioral features
        if event.server_id:
            server_features = self._compute_server_features(event)
            features.update(server_features)

        # Write to online store (Redis)
        self._write_to_redis(event, features)

        # Write to offline store (PostgreSQL) for model training
        self._write_to_training_store(event, features)

        # Update sliding windows
        self._update_windows(event, ts)

        logger.debug(
            "Computed features for txn %s: score_inputs=%d",
            event.transaction_id, len(features)
        )

        return features

    def _compute_card_velocity(self, event: TransactionEvent,
                                ts: datetime) -> dict:
        """Count transactions per card in sliding time windows."""
        card_history = self.card_windows.get(event.card_token, [])

        now = ts
        velocity_1h = sum(1 for t, *_ in card_history if now - t <= timedelta(hours=1))
        velocity_24h = sum(1 for t, *_ in card_history if now - t <= timedelta(hours=24))
        velocity_7d = sum(1 for t, *_ in card_history if now - t <= timedelta(days=7))

        # Average transaction amount for this card
        amounts = [amt for _, amt, *_ in card_history]
        avg_amount = sum(amounts) / len(amounts) if amounts else 0.0

        return {
            "velocity_1h": velocity_1h,
            "velocity_24h": velocity_24h,
            "velocity_7d": velocity_7d,
            "avg_amount": round(avg_amount, 2),
        }

    def _compute_geo_velocity(self, event: TransactionEvent,
                               ts: datetime) -> dict:
        """
        Calculate geographic velocity between consecutive transactions.

        If a card was used in New York at 2:00 PM and in Los Angeles at
        2:30 PM, the geographic velocity is ~6400 km / 0.5 hr = 12800 km/hr.
        This is physically impossible and a strong fraud signal.
        """
        if event.latitude is None or event.longitude is None:
            return {"geo_velocity": 0.0}

        card_history = self.card_windows.get(event.card_token, [])
        if not card_history:
            return {"geo_velocity": 0.0}

        # Find most recent transaction with location data
        for hist_ts, _, _, lat, lon in reversed(card_history):
            if lat is not None and lon is not None:
                time_diff_hours = (ts - hist_ts).total_seconds() / 3600
                if time_diff_hours < 0.001:  # < 3.6 seconds, skip
                    continue

                distance_km = self._haversine(lat, lon,
                                              event.latitude, event.longitude)
                velocity = distance_km / time_diff_hours
                return {"geo_velocity": round(velocity, 2)}

        return {"geo_velocity": 0.0}

    def _compute_merchant_features(self, event: TransactionEvent,
                                    ts: datetime) -> dict:
        """Compute merchant-level aggregation features."""
        merchant_history = self.merchant_windows.get(event.merchant_id, [])

        velocity_1h = sum(
            1 for t, _ in merchant_history if ts - t <= timedelta(hours=1)
        )
        amounts = [amt for _, amt in merchant_history]
        avg_check = sum(amounts) / len(amounts) / 100 if amounts else 0.0

        return {
            "merchant_velocity_1h": velocity_1h,
            "avg_check_size": round(avg_check, 2),
        }

    def _compute_device_features(self, event: TransactionEvent) -> dict:
        """Track device-to-card associations for CNP fraud detection."""
        fp = event.device_fingerprint
        self.device_cards[fp].add(event.card_token)

        return {
            "device_seen": True,
            "card_count_7d": len(self.device_cards[fp]),
        }

    def _compute_server_features(self, event: TransactionEvent) -> dict:
        """
        Track per-server behavioral patterns for employee fraud detection.

        A server with a refund rate 2.5x the merchant average may be
        processing refunds to personal cards (a common employee fraud pattern).
        """
        key = f"{event.merchant_id}:{event.server_id}"
        stats = self.server_stats[key]

        # Compute refund rate relative to merchant average
        total = stats["total"]
        if total == 0:
            return {"refund_rate_vs_avg": 1.0, "void_count_24h": 0}

        refund_rate = stats["refunds"] / total
        # In production, fetch merchant-wide average from a separate aggregation
        merchant_avg_refund_rate = 0.03  # 3% baseline
        ratio = refund_rate / merchant_avg_refund_rate if merchant_avg_refund_rate > 0 else 1.0

        return {
            "refund_rate_vs_avg": round(ratio, 2),
            "void_count_24h": stats["voids"],
        }

    def _update_windows(self, event: TransactionEvent, ts: datetime):
        """Update sliding windows with the current transaction."""
        self.card_windows[event.card_token].append((
            ts, event.amount_cents, event.merchant_id,
            event.latitude, event.longitude
        ))
        self.merchant_windows[event.merchant_id].append((ts, event.amount_cents))

        # Prune entries older than 7 days to bound memory usage
        cutoff = ts - timedelta(days=7)
        self.card_windows[event.card_token] = [
            entry for entry in self.card_windows[event.card_token]
            if entry[0] > cutoff
        ]
        self.merchant_windows[event.merchant_id] = [
            entry for entry in self.merchant_windows[event.merchant_id]
            if entry[0] > cutoff
        ]

    def _write_to_redis(self, event: TransactionEvent, features: dict):
        """Write computed features to Redis for online serving."""
        if self.redis is None:
            return

        card_key = f"fraud:card:{event.card_token}"
        merchant_key = f"fraud:merchant:{event.merchant_id}"

        card_features = {k: str(v) for k, v in features.items()
                        if k.startswith("velocity") or k in ("avg_amount", "geo_velocity")}
        merchant_features = {k: str(v) for k, v in features.items()
                           if k.startswith("merchant") or k in ("avg_check_size",)}

        try:
            pipe = self.redis.pipeline()
            if card_features:
                pipe.hset(card_key, mapping=card_features)
                pipe.expire(card_key, 7 * 24 * 3600)  # 7-day TTL
            if merchant_features:
                pipe.hset(merchant_key, mapping=merchant_features)
                pipe.expire(merchant_key, 24 * 3600)  # 24-hour TTL
            pipe.execute()
        except Exception as e:
            logger.error("Failed to write features to Redis: %s", e)

    def _write_to_training_store(self, event: TransactionEvent, features: dict):
        """Write features + labels to PostgreSQL for model retraining."""
        if self.pg is None:
            return
        # In production: INSERT INTO fraud_features (transaction_id, features, ...)
        # The label (is_fraud) is backfilled when chargeback data arrives

    @staticmethod
    def _haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
        """Calculate great-circle distance between two points in km."""
        import math
        R = 6371  # Earth radius in km
        dlat = math.radians(lat2 - lat1)
        dlon = math.radians(lon2 - lon1)
        a = (math.sin(dlat / 2) ** 2 +
             math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) *
             math.sin(dlon / 2) ** 2)
        c = 2 * math.asin(math.sqrt(a))
        return R * c


if __name__ == "__main__":
    logger.info("Starting feature pipeline (standalone mode)")

    pipeline = FeaturePipeline()

    # Simulate a stream of restaurant transactions
    sample_events = [
        {
            "transaction_id": "txn_001",
            "merchant_id": "mch_downtown_bistro",
            "card_token": "tok_alice_1234",
            "amount_cents": 4500,
            "currency": "USD",
            "channel": "CARD_PRESENT",
            "entry_mode": "EMV_CHIP",
            "terminal_id": "term_01",
            "server_id": "srv_jane",
            "timestamp": "2026-03-15T19:00:00Z",
            "latitude": 37.7749,
            "longitude": -122.4194,
        },
        {
            "transaction_id": "txn_002",
            "merchant_id": "mch_downtown_bistro",
            "card_token": "tok_alice_1234",
            "amount_cents": 3200,
            "currency": "USD",
            "channel": "CARD_PRESENT",
            "entry_mode": "NFC_TAP",
            "terminal_id": "term_01",
            "server_id": "srv_jane",
            "timestamp": "2026-03-15T19:30:00Z",
            "latitude": 37.7749,
            "longitude": -122.4194,
        },
        {
            "transaction_id": "txn_003_suspicious",
            "merchant_id": "mch_airport_cafe",
            "card_token": "tok_alice_1234",
            "amount_cents": 4500,
            "currency": "USD",
            "channel": "CARD_PRESENT",
            "entry_mode": "EMV_CHIP",
            "terminal_id": "term_99",
            "server_id": "srv_bob",
            "timestamp": "2026-03-15T19:45:00Z",
            "latitude": 40.6413,  # JFK Airport, NYC
            "longitude": -73.7781,
        },
    ]

    for event_data in sample_events:
        event = TransactionEvent.from_json(event_data)
        features = pipeline.process_event(event)
        logger.info(
            "Txn %s: velocity_1h=%s, geo_velocity=%s km/h",
            event.transaction_id,
            features.get("velocity_1h", 0),
            features.get("geo_velocity", 0),
        )
