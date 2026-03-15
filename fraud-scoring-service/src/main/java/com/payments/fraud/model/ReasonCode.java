package com.payments.fraud.model;

/**
 * Explainable reason codes attached to DECLINE and REVIEW decisions.
 *
 * Each code maps to a human-readable explanation that can be:
 * 1. Included in chargeback representment evidence packages
 * 2. Surfaced in merchant dashboards for transparency
 * 3. Logged for regulatory audit trails
 * 4. Fed back into model training as labeled features
 *
 * Codes are organized by fraud signal category. The naming convention
 * follows the pattern: CATEGORY_SPECIFIC_SIGNAL.
 */
public enum ReasonCode {

    // Velocity signals
    VELOCITY_CARD_HIGH_1H("Card used more than 5 times in the last hour"),
    VELOCITY_CARD_HIGH_24H("Card used more than 20 times in the last 24 hours"),
    VELOCITY_MERCHANT_ANOMALY("Transaction volume at this merchant is abnormally high"),

    // Geographic signals
    GEO_IMPOSSIBLE_TRAVEL("Card used in two distant locations within an impossibly short time"),
    GEO_HIGH_RISK_REGION("Transaction originates from a high-risk geographic region"),

    // Amount signals
    AMOUNT_ABOVE_MERCHANT_AVG("Transaction amount significantly exceeds merchant average check size"),
    AMOUNT_ROUND_NUMBER("Large round-number transaction (common in testing stolen cards)"),

    // Restaurant-specific signals
    RESTAURANT_OUTSIDE_HOURS("Transaction occurred outside merchant operating hours"),
    RESTAURANT_NO_MATCHING_ORDER("Payment without a corresponding order in the POS system"),
    RESTAURANT_SERVER_ANOMALY("Server processing this transaction has elevated refund/void rates"),
    RESTAURANT_TIP_ANOMALY("Tip pattern inconsistent with merchant category norms"),

    // Device and identity signals (CNP only)
    DEVICE_FINGERPRINT_NEW("Device has not been seen in previous legitimate transactions"),
    DEVICE_MULTIPLE_CARDS("Multiple distinct cards used from the same device recently"),
    EMAIL_DISPOSABLE("Email address uses a known disposable email provider"),
    IP_PROXY_DETECTED("IP address is associated with a known proxy or VPN service"),
    AVS_MISMATCH("Billing address does not match card issuer records"),

    // Card signals
    CARD_FIRST_USE_MERCHANT("First time this card is used at this merchant with high amount"),
    CARD_BIN_HIGH_RISK("Card BIN is associated with elevated fraud rates"),

    // ML model signals
    ML_HIGH_RISK_SCORE("Machine learning model assigned a high risk probability"),
    ML_ANOMALY_DETECTED("Transaction deviates from learned behavioral patterns");

    private final String description;

    ReasonCode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
