package com.payments.fraud.featurestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Pre-computed feature vector retrieved from the online feature store (Redis).
 *
 * Features are computed by the streaming feature pipeline and written to Redis
 * with sliding window TTLs. The fraud scoring service reads these features
 * at scoring time rather than computing them inline, keeping the authorization
 * path latency under budget.
 *
 * Feature categories:
 * - Velocity: transaction counts over time windows (1h, 24h, 7d)
 * - Behavioral: patterns derived from historical transactions
 * - Restaurant-specific: signals from POS operational data
 * - Identity: device and address signals (CNP only)
 */
public class FeatureVector {

    // Velocity features (sliding window counts)
    private int cardVelocity1h;
    private int cardVelocity24h;
    private int cardVelocity7d;
    private int merchantVelocity1h;

    // Behavioral features
    private double cardMerchantAffinity;    // 0.0 = never seen, 1.0 = frequent
    private double avgTransactionAmount;     // historical average for this card
    private double geoVelocityKmPerHour;     // distance / time since last txn

    // Restaurant-specific features
    private double merchantAvgCheckSize;     // average check at this restaurant
    private boolean withinOperatingHours;
    private double serverRefundRateVsAvg;    // ratio: this server's refund rate / merchant avg
    private int serverVoidCount24h;
    private boolean hasMatchingOrder;         // POS order exists for this transaction

    // Identity features (CNP)
    private boolean deviceSeen;
    private int deviceCardCount7d;           // distinct cards from this device in 7 days
    private boolean proxyDetected;
    private boolean disposableEmail;

    // Tip analysis
    private double tipRatio;                 // tip / base amount
    private double merchantAvgTipRatio;
    private boolean tipAnomaly;

    public FeatureVector() {}

    /**
     * Converts the feature vector to a flat map for model input and response logging.
     * Keys match the feature names used in model training for consistency.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("card_velocity_1h", cardVelocity1h);
        map.put("card_velocity_24h", cardVelocity24h);
        map.put("card_velocity_7d", cardVelocity7d);
        map.put("merchant_velocity_1h", merchantVelocity1h);
        map.put("card_merchant_affinity", cardMerchantAffinity);
        map.put("avg_transaction_amount", avgTransactionAmount);
        map.put("geo_velocity_km_per_hour", geoVelocityKmPerHour);
        map.put("amount_vs_avg_check", merchantAvgCheckSize > 0
            ? 0.0 : 0.0); // computed at scoring time with actual txn amount
        map.put("within_operating_hours", withinOperatingHours);
        map.put("server_refund_rate_vs_avg", serverRefundRateVsAvg);
        map.put("server_void_count_24h", serverVoidCount24h);
        map.put("has_matching_order", hasMatchingOrder);
        map.put("device_seen", deviceSeen);
        map.put("device_card_count_7d", deviceCardCount7d);
        map.put("proxy_detected", proxyDetected);
        map.put("disposable_email", disposableEmail);
        map.put("tip_ratio", tipRatio);
        map.put("tip_anomaly", tipAnomaly);
        return map;
    }

    /**
     * Converts to a float array for ONNX model inference.
     * Feature order must match the training feature order exactly.
     */
    public float[] toModelInput() {
        return new float[] {
            cardVelocity1h,
            cardVelocity24h,
            cardVelocity7d,
            merchantVelocity1h,
            (float) cardMerchantAffinity,
            (float) avgTransactionAmount,
            (float) geoVelocityKmPerHour,
            withinOperatingHours ? 1.0f : 0.0f,
            (float) serverRefundRateVsAvg,
            serverVoidCount24h,
            hasMatchingOrder ? 1.0f : 0.0f,
            deviceSeen ? 1.0f : 0.0f,
            deviceCardCount7d,
            proxyDetected ? 1.0f : 0.0f,
            disposableEmail ? 1.0f : 0.0f,
            (float) tipRatio,
            tipAnomaly ? 1.0f : 0.0f
        };
    }

    // --- Getters and setters ---

    public int getCardVelocity1h() { return cardVelocity1h; }
    public void setCardVelocity1h(int v) { this.cardVelocity1h = v; }

    public int getCardVelocity24h() { return cardVelocity24h; }
    public void setCardVelocity24h(int v) { this.cardVelocity24h = v; }

    public int getCardVelocity7d() { return cardVelocity7d; }
    public void setCardVelocity7d(int v) { this.cardVelocity7d = v; }

    public int getMerchantVelocity1h() { return merchantVelocity1h; }
    public void setMerchantVelocity1h(int v) { this.merchantVelocity1h = v; }

    public double getCardMerchantAffinity() { return cardMerchantAffinity; }
    public void setCardMerchantAffinity(double v) { this.cardMerchantAffinity = v; }

    public double getAvgTransactionAmount() { return avgTransactionAmount; }
    public void setAvgTransactionAmount(double v) { this.avgTransactionAmount = v; }

    public double getGeoVelocityKmPerHour() { return geoVelocityKmPerHour; }
    public void setGeoVelocityKmPerHour(double v) { this.geoVelocityKmPerHour = v; }

    public double getMerchantAvgCheckSize() { return merchantAvgCheckSize; }
    public void setMerchantAvgCheckSize(double v) { this.merchantAvgCheckSize = v; }

    public boolean isWithinOperatingHours() { return withinOperatingHours; }
    public void setWithinOperatingHours(boolean v) { this.withinOperatingHours = v; }

    public double getServerRefundRateVsAvg() { return serverRefundRateVsAvg; }
    public void setServerRefundRateVsAvg(double v) { this.serverRefundRateVsAvg = v; }

    public int getServerVoidCount24h() { return serverVoidCount24h; }
    public void setServerVoidCount24h(int v) { this.serverVoidCount24h = v; }

    public boolean isHasMatchingOrder() { return hasMatchingOrder; }
    public void setHasMatchingOrder(boolean v) { this.hasMatchingOrder = v; }

    public boolean isDeviceSeen() { return deviceSeen; }
    public void setDeviceSeen(boolean v) { this.deviceSeen = v; }

    public int getDeviceCardCount7d() { return deviceCardCount7d; }
    public void setDeviceCardCount7d(int v) { this.deviceCardCount7d = v; }

    public boolean isProxyDetected() { return proxyDetected; }
    public void setProxyDetected(boolean v) { this.proxyDetected = v; }

    public boolean isDisposableEmail() { return disposableEmail; }
    public void setDisposableEmail(boolean v) { this.disposableEmail = v; }

    public double getTipRatio() { return tipRatio; }
    public void setTipRatio(double v) { this.tipRatio = v; }

    public double getMerchantAvgTipRatio() { return merchantAvgTipRatio; }
    public void setMerchantAvgTipRatio(double v) { this.merchantAvgTipRatio = v; }

    public boolean isTipAnomaly() { return tipAnomaly; }
    public void setTipAnomaly(boolean v) { this.tipAnomaly = v; }
}
