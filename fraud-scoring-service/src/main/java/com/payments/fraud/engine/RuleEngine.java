package com.payments.fraud.engine;

import com.payments.fraud.featurestore.FeatureVector;
import com.payments.fraud.model.ReasonCode;
import com.payments.fraud.model.TransactionRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic rule engine for card-present (CP) fraud scoring.
 *
 * Design rationale (see ADR-001):
 * CP transactions have a 50ms total scoring budget. A rule engine executes
 * in under 10ms with predictable, zero-variance latency. Each rule produces
 * a weighted score contribution and an explainable reason code.
 *
 * Rules are evaluated in priority order. Each triggered rule adds its weight
 * to the cumulative score. The final score is capped at 1.0.
 *
 * Rule weights are calibrated against historical chargeback data:
 * - High-confidence signals (impossible travel, outside hours) get 0.3-0.5 weight
 * - Medium-confidence signals (velocity, amount anomaly) get 0.15-0.25 weight
 * - Low-confidence signals (first use, tip anomaly) get 0.05-0.10 weight
 *
 * This engine is the PRIMARY scorer for CP transactions. The ML model runs
 * in shadow mode for CP, logging predictions for comparison without affecting
 * the authorization decision. Once the ML model demonstrates superior
 * performance on CP traffic, it can be promoted via a feature flag.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    // Threshold configuration - externalize to application.yml in production
    private static final int CARD_VELOCITY_1H_THRESHOLD = 5;
    private static final int CARD_VELOCITY_24H_THRESHOLD = 20;
    private static final double GEO_VELOCITY_THRESHOLD_KM_H = 800.0; // ~500mph, impossible by car
    private static final double AMOUNT_VS_AVG_CHECK_THRESHOLD = 3.0;  // 3x merchant average
    private static final double SERVER_REFUND_RATE_THRESHOLD = 2.5;   // 2.5x merchant average
    private static final int SERVER_VOID_THRESHOLD_24H = 10;

    private final Counter rulesTriggeredCounter;
    private final Counter rulesEvaluatedCounter;

    public RuleEngine(MeterRegistry meterRegistry) {
        this.rulesTriggeredCounter = Counter.builder("fraud.rules.triggered")
            .description("Number of fraud rules triggered")
            .register(meterRegistry);
        this.rulesEvaluatedCounter = Counter.builder("fraud.rules.evaluated")
            .description("Number of transactions evaluated by rule engine")
            .register(meterRegistry);
    }

    /**
     * Evaluates all rules against the feature vector and returns a score
     * with reason codes.
     *
     * @param request The original transaction request (for amount, channel context)
     * @param features Pre-computed feature vector from the feature store
     * @return RuleResult containing cumulative score and triggered reason codes
     */
    public RuleResult evaluate(TransactionRequest request, FeatureVector features) {
        rulesEvaluatedCounter.increment();

        List<ReasonCode> reasons = new ArrayList<>();
        double score = 0.0;

        // Rule 1: Card velocity (1 hour window)
        if (features.getCardVelocity1h() > CARD_VELOCITY_1H_THRESHOLD) {
            score += 0.25;
            reasons.add(ReasonCode.VELOCITY_CARD_HIGH_1H);
        }

        // Rule 2: Card velocity (24 hour window)
        if (features.getCardVelocity24h() > CARD_VELOCITY_24H_THRESHOLD) {
            score += 0.20;
            reasons.add(ReasonCode.VELOCITY_CARD_HIGH_24H);
        }

        // Rule 3: Impossible travel detection
        // If geo_velocity exceeds ~500mph, the card is being used in two
        // locations faster than physically possible.
        if (features.getGeoVelocityKmPerHour() > GEO_VELOCITY_THRESHOLD_KM_H) {
            score += 0.45;
            reasons.add(ReasonCode.GEO_IMPOSSIBLE_TRAVEL);
        }

        // Rule 4: Transaction amount vs merchant average check size
        // A $500 transaction at a fast-casual restaurant (avg check $15) is suspicious.
        // A $500 transaction at fine dining (avg check $120) is normal.
        double avgCheck = features.getMerchantAvgCheckSize();
        if (avgCheck > 0) {
            double amountRatio = (double) request.amountCents() / (avgCheck * 100);
            if (amountRatio > AMOUNT_VS_AVG_CHECK_THRESHOLD) {
                score += 0.20;
                reasons.add(ReasonCode.AMOUNT_ABOVE_MERCHANT_AVG);
            }
        }

        // Rule 5: Outside operating hours (restaurant-specific)
        // Transaction at 3 AM at a restaurant that closes at 11 PM.
        if (!features.isWithinOperatingHours()) {
            score += 0.35;
            reasons.add(ReasonCode.RESTAURANT_OUTSIDE_HOURS);
        }

        // Rule 6: No matching order in POS
        // Payment processed without a corresponding order is a strong fraud signal.
        if (!features.isHasMatchingOrder()) {
            score += 0.30;
            reasons.add(ReasonCode.RESTAURANT_NO_MATCHING_ORDER);
        }

        // Rule 7: Server behavioral anomaly
        // Server with refund rate 2.5x the merchant average may be committing
        // employee fraud (processing refunds to personal cards).
        if (features.getServerRefundRateVsAvg() > SERVER_REFUND_RATE_THRESHOLD) {
            score += 0.20;
            reasons.add(ReasonCode.RESTAURANT_SERVER_ANOMALY);
        }

        // Rule 8: Server void count anomaly
        if (features.getServerVoidCount24h() > SERVER_VOID_THRESHOLD_24H) {
            score += 0.15;
            reasons.add(ReasonCode.RESTAURANT_SERVER_ANOMALY);
        }

        // Rule 9: First use at merchant with high amount
        // Card never seen at this restaurant + high amount = elevated risk.
        if (features.getCardMerchantAffinity() < 0.01
                && request.amountCents() > avgCheck * 200) { // 2x average in cents
            score += 0.10;
            reasons.add(ReasonCode.CARD_FIRST_USE_MERCHANT);
        }

        // Rule 10: Tip anomaly
        if (features.isTipAnomaly()) {
            score += 0.08;
            reasons.add(ReasonCode.RESTAURANT_TIP_ANOMALY);
        }

        // Rule 11: Round number amount (common in testing stolen cards)
        if (request.amountCents() >= 10000
                && request.amountCents() % 1000 == 0) { // $100+ and round to $10
            score += 0.10;
            reasons.add(ReasonCode.AMOUNT_ROUND_NUMBER);
        }

        // Cap score at 1.0
        score = Math.min(score, 1.0);

        if (!reasons.isEmpty()) {
            rulesTriggeredCounter.increment(reasons.size());
            log.debug("Transaction {} triggered {} rules, score: {}",
                      request.transactionId(), reasons.size(), score);
        }

        return new RuleResult(score, reasons);
    }

    /**
     * Result of rule engine evaluation.
     *
     * @param score Cumulative risk score from all triggered rules (0.0 to 1.0)
     * @param reasonCodes List of explainable reason codes for each triggered rule
     */
    public record RuleResult(double score, List<ReasonCode> reasonCodes) {}
}
