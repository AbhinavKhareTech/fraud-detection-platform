"""
Restaurant-specific fraud features.

These features are the competitive moat for a restaurant payment platform.
Horizontal processors (Stripe, Adyen) cannot compute these because they
lack access to the POS operational data: menu prices, order composition,
server identity, operating hours, and tip patterns.

This module computes features that normalize fraud signals against
restaurant-specific context, dramatically reducing false positives.

Example: A $500 transaction is suspicious at a fast-casual restaurant
(avg check $15) but normal at a fine-dining establishment (avg check $120).
Without menu/order data, a generic processor would treat both the same.
"""

import logging
from dataclasses import dataclass
from datetime import datetime, time

logger = logging.getLogger(__name__)


# Simulated merchant configuration (production: from merchant config service)
MERCHANT_CONFIGS = {
    "mch_downtown_bistro": {
        "category": "fine_dining",
        "avg_check_size": 85.00,
        "opening_time": time(11, 0),
        "closing_time": time(23, 0),
        "avg_tip_ratio": 0.20,
    },
    "mch_airport_cafe": {
        "category": "fast_casual",
        "avg_check_size": 18.00,
        "opening_time": time(5, 0),
        "closing_time": time(22, 0),
        "avg_tip_ratio": 0.10,
    },
    "mch_pizza_chain_042": {
        "category": "quick_service",
        "avg_check_size": 25.00,
        "opening_time": time(10, 0),
        "closing_time": time(1, 0),  # Past midnight
        "avg_tip_ratio": 0.05,
    },
}


class RestaurantFeatureComputer:
    """Computes fraud features specific to restaurant merchant verticals."""

    def compute(self, event, existing_features: dict) -> dict:
        """
        Compute restaurant-specific features for a transaction.

        Args:
            event: TransactionEvent with merchant_id, amount, timestamp
            existing_features: Features already computed (velocity, geo, etc.)

        Returns:
            Dict of restaurant-specific feature values
        """
        features = {}
        config = MERCHANT_CONFIGS.get(event.merchant_id)

        if config is None:
            # Unknown merchant, return neutral defaults
            return {
                "within_hours": True,
                "amount_vs_avg_check": 1.0,
                "tip_anomaly": False,
            }

        # Feature 1: Transaction amount vs merchant average check size
        # A $500 charge at a $15-avg fast-casual restaurant is a 33x ratio.
        # A $500 charge at an $85-avg fine-dining restaurant is a 5.9x ratio.
        avg_check = config["avg_check_size"]
        if avg_check > 0:
            amount_dollars = event.amount_cents / 100
            ratio = amount_dollars / avg_check
            features["amount_vs_avg_check"] = round(ratio, 2)
        else:
            features["amount_vs_avg_check"] = 1.0

        # Feature 2: Within operating hours
        # Transaction at 3 AM at a restaurant that closes at 11 PM is suspicious.
        features["within_hours"] = self._check_operating_hours(
            event.timestamp, config["opening_time"], config["closing_time"]
        )

        # Feature 3: Tip pattern anomaly
        # Stolen cards at fine-dining restaurants often skip the tip or leave
        # an unusually small tip. Legitimate diners at fine dining typically
        # tip 18-25%. A 0% tip on a $200 fine-dining bill is anomalous.
        features["tip_anomaly"] = False  # Computed post-capture when tip is added

        # Feature 4: Category-adjusted amount threshold
        # Quick-service: flag above $100 (4x avg)
        # Fast-casual: flag above $100 (5x avg)
        # Fine-dining: flag above $500 (6x avg)
        category_multipliers = {
            "quick_service": 4.0,
            "fast_casual": 5.0,
            "fine_dining": 6.0,
        }
        multiplier = category_multipliers.get(config["category"], 5.0)
        category_threshold = avg_check * multiplier
        amount_dollars = event.amount_cents / 100
        features["above_category_threshold"] = amount_dollars > category_threshold

        return features

    @staticmethod
    def _check_operating_hours(timestamp_str: str,
                                opening: time, closing: time) -> bool:
        """
        Check if a transaction timestamp falls within merchant operating hours.

        Handles restaurants that close past midnight (e.g., 10 AM to 1 AM).
        """
        try:
            ts = datetime.fromisoformat(timestamp_str.replace("Z", "+00:00"))
            txn_time = ts.time()

            if closing > opening:
                # Normal hours (e.g., 11 AM to 11 PM)
                return opening <= txn_time <= closing
            else:
                # Crosses midnight (e.g., 10 AM to 1 AM)
                return txn_time >= opening or txn_time <= closing
        except (ValueError, AttributeError):
            return True  # Default to within hours on parse failure
