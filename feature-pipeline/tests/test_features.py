"""
Tests for the feature engineering pipeline.

Validates that features are computed correctly, especially:
- Velocity counts across sliding windows
- Geographic velocity (impossible travel detection)
- Restaurant-specific features
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from feature_pipeline import FeaturePipeline, TransactionEvent


def test_card_velocity_increments():
    """Verify that card velocity increases with each transaction."""
    pipeline = FeaturePipeline()

    event1 = TransactionEvent.from_json({
        "transaction_id": "txn_001",
        "merchant_id": "mch_test",
        "card_token": "tok_test_card",
        "amount_cents": 3500,
        "currency": "USD",
        "channel": "CARD_PRESENT",
        "entry_mode": "EMV_CHIP",
        "timestamp": "2026-03-15T19:00:00Z",
    })

    event2 = TransactionEvent.from_json({
        "transaction_id": "txn_002",
        "merchant_id": "mch_test",
        "card_token": "tok_test_card",
        "amount_cents": 2000,
        "currency": "USD",
        "channel": "CARD_PRESENT",
        "entry_mode": "EMV_CHIP",
        "timestamp": "2026-03-15T19:30:00Z",
    })

    features1 = pipeline.process_event(event1)
    assert features1["velocity_1h"] == 0, "First transaction should have 0 prior velocity"

    features2 = pipeline.process_event(event2)
    assert features2["velocity_1h"] == 1, "Second transaction should see 1 prior transaction"


def test_impossible_travel_detection():
    """
    Card used in San Francisco at 7:00 PM, then at JFK Airport at 7:15 PM.
    Distance: ~4150 km. Time: 0.25 hours. Velocity: ~16,600 km/h.
    This should produce a very high geo_velocity feature.
    """
    pipeline = FeaturePipeline()

    sf_event = TransactionEvent.from_json({
        "transaction_id": "txn_sf",
        "merchant_id": "mch_sf_restaurant",
        "card_token": "tok_travel_test",
        "amount_cents": 4500,
        "currency": "USD",
        "channel": "CARD_PRESENT",
        "entry_mode": "EMV_CHIP",
        "timestamp": "2026-03-15T19:00:00Z",
        "latitude": 37.7749,
        "longitude": -122.4194,
    })

    nyc_event = TransactionEvent.from_json({
        "transaction_id": "txn_nyc",
        "merchant_id": "mch_jfk_cafe",
        "card_token": "tok_travel_test",
        "amount_cents": 1500,
        "currency": "USD",
        "channel": "CARD_PRESENT",
        "entry_mode": "NFC_TAP",
        "timestamp": "2026-03-15T19:15:00Z",
        "latitude": 40.6413,
        "longitude": -73.7781,
    })

    pipeline.process_event(sf_event)
    features = pipeline.process_event(nyc_event)

    geo_velocity = features.get("geo_velocity", 0)
    assert geo_velocity > 10000, (
        f"Geo velocity should be >10000 km/h for SF->NYC in 15 min, got {geo_velocity}"
    )


def test_merchant_average_check_computation():
    """Verify that merchant average check size updates with transactions."""
    pipeline = FeaturePipeline()

    for i, amount in enumerate([3000, 4000, 5000]):  # $30, $40, $50
        event = TransactionEvent.from_json({
            "transaction_id": f"txn_{i}",
            "merchant_id": "mch_avg_test",
            "card_token": f"tok_card_{i}",
            "amount_cents": amount,
            "currency": "USD",
            "channel": "CARD_PRESENT",
            "entry_mode": "EMV_CHIP",
            "timestamp": f"2026-03-15T19:{i:02d}:00Z",
        })
        features = pipeline.process_event(event)

    # After 3 transactions ($30, $40, $50), avg check should be ~$40
    # The last event sees 2 prior transactions in the window
    avg = features.get("avg_check_size", 0)
    assert 25 < avg < 55, f"Average check size should be around $35-40, got {avg}"


def test_device_card_tracking():
    """Multiple cards from the same device should increment card count."""
    pipeline = FeaturePipeline()

    for i in range(3):
        event = TransactionEvent.from_json({
            "transaction_id": f"txn_device_{i}",
            "merchant_id": "mch_online",
            "card_token": f"tok_different_card_{i}",  # Different cards
            "amount_cents": 5000,
            "currency": "USD",
            "channel": "CARD_NOT_PRESENT",
            "entry_mode": "ONLINE_ORDER",
            "device_fingerprint": "dfp_same_device",  # Same device
            "timestamp": f"2026-03-15T19:{i:02d}:00Z",
        })
        features = pipeline.process_event(event)

    card_count = features.get("card_count_7d", 0)
    assert card_count == 3, f"Should see 3 distinct cards on device, got {card_count}"


if __name__ == "__main__":
    test_card_velocity_increments()
    print("PASS: test_card_velocity_increments")

    test_impossible_travel_detection()
    print("PASS: test_impossible_travel_detection")

    test_merchant_average_check_computation()
    print("PASS: test_merchant_average_check_computation")

    test_device_card_tracking()
    print("PASS: test_device_card_tracking")

    print("\nAll tests passed.")
