"""
Feature definitions catalog for the fraud detection platform.

Each feature has a name, computation window, data source, and description.
This catalog serves as the contract between the feature pipeline (producer)
and the fraud scoring service (consumer). Any change to feature names or
semantics must be versioned here.

Feature naming convention: {category}_{specific_signal}_{window}
  Examples: card_velocity_1h, merchant_avg_check_size, device_card_count_7d
"""

from dataclasses import dataclass
from enum import Enum


class FeatureSource(Enum):
    """Data source for feature computation."""
    PAYMENT_STREAM = "payment_events"       # Transaction authorization events
    ORDER_STREAM = "order_events"           # POS order creation/completion events
    EMPLOYEE_STREAM = "employee_events"     # Server login, shift start/end
    MERCHANT_CONFIG = "merchant_config"     # Hours of operation, menu, category
    DEVICE_REGISTRY = "device_registry"     # Device fingerprint history
    GEO_SERVICE = "geo_service"             # IP geolocation, address verification


class FeatureWindow(Enum):
    """Sliding window duration for aggregate features."""
    REAL_TIME = "real_time"     # Computed per-transaction
    WINDOW_1H = "1_hour"
    WINDOW_24H = "24_hours"
    WINDOW_7D = "7_days"
    WINDOW_30D = "30_days"
    STATIC = "static"          # Merchant configuration, not time-windowed


@dataclass
class FeatureDefinition:
    name: str
    description: str
    source: FeatureSource
    window: FeatureWindow
    data_type: str              # "int", "float", "bool"
    default_value: object       # Fallback when feature store is unavailable
    pci_scope: bool = False     # True if feature computation touches PAN data


FEATURE_DEFINITIONS = {

    # --- Card velocity features ---
    "velocity_1h": FeatureDefinition(
        name="velocity_1h",
        description="Number of transactions on this card in the last 1 hour",
        source=FeatureSource.PAYMENT_STREAM,
        window=FeatureWindow.WINDOW_1H,
        data_type="int",
        default_value=0,
    ),
    "velocity_24h": FeatureDefinition(
        name="velocity_24h",
        description="Number of transactions on this card in the last 24 hours",
        source=FeatureSource.PAYMENT_STREAM,
        window=FeatureWindow.WINDOW_24H,
        data_type="int",
        default_value=0,
    ),
    "velocity_7d": FeatureDefinition(
        name="velocity_7d",
        description="Number of transactions on this card in the last 7 days",
        source=FeatureSource.PAYMENT_STREAM,
        window=FeatureWindow.WINDOW_7D,
        data_type="int",
        default_value=0,
    ),
    "avg_amount": FeatureDefinition(
        name="avg_amount",
        description="Average transaction amount for this card over 7 days",
        source=FeatureSource.PAYMENT_STREAM,
        window=FeatureWindow.WINDOW_7D,
        data_type="float",
        default_value=0.0,
    ),

    # --- Geographic features ---
    "geo_velocity": FeatureDefinition(
        name="geo_velocity",
        description="Speed in km/h between this and the previous transaction location",
        source=FeatureSource.PAYMENT_STREAM,
        window=FeatureWindow.REAL_TIME,
        data_type="float",
        default_value=0.0,
    ),

    # --- Merchant features ---
    "merchant_velocity_1h": FeatureDefinition(
        name="merchant_velocity_1h",
        description="Number of transactions at this merchant in the last 1 hour",
        source=FeatureSource.PAYMENT_STREAM,
        window=FeatureWindow.WINDOW_1H,
        data_type="int",
        default_value=0,
    ),
    "avg_check_size": FeatureDefinition(
        name="avg_check_size",
        description="Average check size at this merchant over 7 days",
        source=FeatureSource.PAYMENT_STREAM,
        window=FeatureWindow.WINDOW_7D,
        data_type="float",
        default_value=0.0,
    ),

    # --- Restaurant-specific features ---
    "within_operating_hours": FeatureDefinition(
        name="within_operating_hours",
        description="Whether transaction occurred within merchant business hours",
        source=FeatureSource.MERCHANT_CONFIG,
        window=FeatureWindow.STATIC,
        data_type="bool",
        default_value=True,  # Conservative default to avoid false positives
    ),
    "amount_vs_avg_check": FeatureDefinition(
        name="amount_vs_avg_check",
        description="Ratio of transaction amount to merchant average check size",
        source=FeatureSource.PAYMENT_STREAM,
        window=FeatureWindow.REAL_TIME,
        data_type="float",
        default_value=1.0,
    ),
    "has_matching_order": FeatureDefinition(
        name="has_matching_order",
        description="Whether a POS order exists for this transaction",
        source=FeatureSource.ORDER_STREAM,
        window=FeatureWindow.REAL_TIME,
        data_type="bool",
        default_value=True,
    ),
    "server_refund_rate_vs_avg": FeatureDefinition(
        name="server_refund_rate_vs_avg",
        description="Ratio of this server refund rate to merchant average",
        source=FeatureSource.EMPLOYEE_STREAM,
        window=FeatureWindow.WINDOW_30D,
        data_type="float",
        default_value=1.0,
    ),

    # --- Device features (CNP only) ---
    "device_seen": FeatureDefinition(
        name="device_seen",
        description="Whether this device fingerprint has been seen in legitimate transactions",
        source=FeatureSource.DEVICE_REGISTRY,
        window=FeatureWindow.WINDOW_30D,
        data_type="bool",
        default_value=True,
    ),
    "card_count_7d": FeatureDefinition(
        name="card_count_7d",
        description="Number of distinct cards used from this device in 7 days",
        source=FeatureSource.DEVICE_REGISTRY,
        window=FeatureWindow.WINDOW_7D,
        data_type="int",
        default_value=1,
    ),
}
