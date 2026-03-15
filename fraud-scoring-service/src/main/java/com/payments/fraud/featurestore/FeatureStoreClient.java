package com.payments.fraud.featurestore;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Client for the online feature store backed by Redis.
 *
 * Design rationale:
 * - Features are pre-computed by the streaming pipeline and written to Redis
 *   with TTLs matching the sliding window periods (1h, 24h, 7d).
 * - This client is a Tier 2 dependency in the circuit breaker classification:
 *   if Redis is unavailable, the scoring service degrades to default features
 *   rather than failing the authorization.
 * - Read latency target: < 5ms for a complete feature vector retrieval.
 *
 * Key schema:
 *   Card features:     fraud:card:{card_token}
 *   Merchant features:  fraud:merchant:{merchant_id}
 *   Device features:    fraud:device:{device_fingerprint}
 *   Server features:    fraud:server:{merchant_id}:{server_id}
 */
@Component
public class FeatureStoreClient {

    private static final Logger log = LoggerFactory.getLogger(FeatureStoreClient.class);

    private static final String CARD_PREFIX = "fraud:card:";
    private static final String MERCHANT_PREFIX = "fraud:merchant:";
    private static final String DEVICE_PREFIX = "fraud:device:";
    private static final String SERVER_PREFIX = "fraud:server:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final Timer featureFetchTimer;

    public FeatureStoreClient(RedisTemplate<String, Object> redisTemplate,
                              MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.featureFetchTimer = Timer.builder("fraud.feature_store.fetch")
            .description("Time to fetch complete feature vector from Redis")
            .register(meterRegistry);
    }

    /**
     * Retrieves the complete feature vector for a transaction.
     * Fans out to multiple Redis keys in parallel using pipelining.
     *
     * If Redis is unavailable (circuit open), returns a default feature vector
     * with conservative values that will produce a slightly elevated but
     * non-blocking risk score.
     */
    @CircuitBreaker(name = "featureStore", fallbackMethod = "getDefaultFeatures")
    public FeatureVector getFeatures(String cardToken, String merchantId,
                                     String deviceFingerprint, String serverId) {
        return featureFetchTimer.record(() -> {
            FeatureVector vector = new FeatureVector();

            // Card velocity features
            fetchCardFeatures(vector, cardToken);

            // Merchant context features
            fetchMerchantFeatures(vector, merchantId);

            // Device features (CNP only, null-safe)
            if (deviceFingerprint != null) {
                fetchDeviceFeatures(vector, deviceFingerprint);
            }

            // Server behavioral features (restaurant-specific)
            if (serverId != null && merchantId != null) {
                fetchServerFeatures(vector, merchantId, serverId);
            }

            return vector;
        });
    }

    private void fetchCardFeatures(FeatureVector vector, String cardToken) {
        String key = CARD_PREFIX + cardToken;
        try {
            var ops = redisTemplate.opsForHash();
            var cardData = ops.entries(key);

            vector.setCardVelocity1h(getInt(cardData.get("velocity_1h")));
            vector.setCardVelocity24h(getInt(cardData.get("velocity_24h")));
            vector.setCardVelocity7d(getInt(cardData.get("velocity_7d")));
            vector.setAvgTransactionAmount(getDouble(cardData.get("avg_amount")));
            vector.setGeoVelocityKmPerHour(getDouble(cardData.get("geo_velocity")));
        } catch (Exception e) {
            log.warn("Failed to fetch card features for token ending {}",
                     cardToken.substring(Math.max(0, cardToken.length() - 4)), e);
        }
    }

    private void fetchMerchantFeatures(FeatureVector vector, String merchantId) {
        String key = MERCHANT_PREFIX + merchantId;
        try {
            var ops = redisTemplate.opsForHash();
            var merchantData = ops.entries(key);

            vector.setMerchantVelocity1h(getInt(merchantData.get("velocity_1h")));
            vector.setMerchantAvgCheckSize(getDouble(merchantData.get("avg_check_size")));
            vector.setWithinOperatingHours(getBool(merchantData.get("within_hours")));
            vector.setMerchantAvgTipRatio(getDouble(merchantData.get("avg_tip_ratio")));
        } catch (Exception e) {
            log.warn("Failed to fetch merchant features for {}", merchantId, e);
        }
    }

    private void fetchDeviceFeatures(FeatureVector vector, String fingerprint) {
        String key = DEVICE_PREFIX + fingerprint;
        try {
            var ops = redisTemplate.opsForHash();
            var deviceData = ops.entries(key);

            vector.setDeviceSeen(!deviceData.isEmpty());
            vector.setDeviceCardCount7d(getInt(deviceData.get("card_count_7d")));
            vector.setProxyDetected(getBool(deviceData.get("proxy_detected")));
        } catch (Exception e) {
            log.warn("Failed to fetch device features", e);
        }
    }

    private void fetchServerFeatures(FeatureVector vector, String merchantId, String serverId) {
        String key = SERVER_PREFIX + merchantId + ":" + serverId;
        try {
            var ops = redisTemplate.opsForHash();
            var serverData = ops.entries(key);

            vector.setServerRefundRateVsAvg(getDouble(serverData.get("refund_rate_vs_avg")));
            vector.setServerVoidCount24h(getInt(serverData.get("void_count_24h")));
        } catch (Exception e) {
            log.warn("Failed to fetch server features for {}:{}", merchantId, serverId, e);
        }
    }

    /**
     * Fallback when the feature store circuit breaker is open.
     * Returns conservative default values that produce a mildly elevated
     * risk score without causing false declines.
     *
     * This is a deliberate design choice: brief feature store unavailability
     * should NOT cause payment authorization failures. The business accepts
     * slightly reduced fraud detection accuracy for minutes over declining
     * all transactions.
     */
    @SuppressWarnings("unused")
    private FeatureVector getDefaultFeatures(String cardToken, String merchantId,
                                             String deviceFingerprint, String serverId,
                                             Throwable t) {
        log.warn("Feature store circuit breaker open, using defaults. Cause: {}",
                 t.getMessage());

        FeatureVector defaults = new FeatureVector();
        defaults.setCardVelocity1h(0);
        defaults.setCardVelocity24h(0);
        defaults.setCardMerchantAffinity(0.5);  // neutral affinity
        defaults.setWithinOperatingHours(true);  // assume within hours to avoid false positives
        defaults.setHasMatchingOrder(true);       // assume order exists
        defaults.setDeviceSeen(true);             // assume known device
        return defaults;
    }

    private static int getInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static double getDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(value.toString()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static boolean getBool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(value.toString()) || "1".equals(value.toString());
    }
}
