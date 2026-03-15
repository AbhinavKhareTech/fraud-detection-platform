package com.payments.fraud.circuit;

/**
 * Dependency classification for circuit breaker behavior.
 *
 * Payment authorization has multiple downstream dependencies, each with
 * different criticality. A failure in the fraud scoring service should NOT
 * cause the same response as a failure in the card processor.
 *
 * Tier classification (see ADR-004):
 *
 * TIER_1 (Hard dependency - cannot degrade):
 *   The card processor. If you cannot reach the processor, you cannot
 *   authorize the payment. Circuit breaker triggers FAILOVER to secondary
 *   processor, not degradation.
 *   Example: Visa/Mastercard processor adapter
 *
 * TIER_2 (Soft dependency - degrade gracefully):
 *   Services that improve authorization quality but are not required.
 *   When the circuit opens, the authorization proceeds with reduced
 *   intelligence. The business accepts marginally higher risk for minutes
 *   rather than declining all transactions.
 *   Examples: Fraud scoring service, Feature store (Redis)
 *
 * TIER_3 (Optional dependency - skip entirely):
 *   Enrichment services that add value but have zero impact on the
 *   authorization decision if unavailable.
 *   Examples: IP geolocation, Email reputation, Device fingerprint enrichment
 */
public enum DependencyTier {

    /**
     * Hard dependency. Circuit breaker triggers failover, not degradation.
     * Configuration: 5% failure rate threshold, 30-second window.
     */
    TIER_1(5, 30, "failover"),

    /**
     * Soft dependency. Circuit breaker returns default/cached values.
     * Configuration: 50% failure rate threshold, 10-second window.
     */
    TIER_2(50, 10, "degrade"),

    /**
     * Optional dependency. Circuit breaker skips the call entirely.
     * Configuration: 25% failure rate threshold, 5-second window.
     */
    TIER_3(25, 5, "skip");

    private final int failureRateThreshold;
    private final int slidingWindowSeconds;
    private final String fallbackStrategy;

    DependencyTier(int failureRateThreshold, int slidingWindowSeconds,
                   String fallbackStrategy) {
        this.failureRateThreshold = failureRateThreshold;
        this.slidingWindowSeconds = slidingWindowSeconds;
        this.fallbackStrategy = fallbackStrategy;
    }

    public int getFailureRateThreshold() { return failureRateThreshold; }
    public int getSlidingWindowSeconds() { return slidingWindowSeconds; }
    public String getFallbackStrategy() { return fallbackStrategy; }
}
