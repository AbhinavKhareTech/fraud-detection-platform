package com.payments.fraud.engine;

import com.payments.fraud.featurestore.FeatureStoreClient;
import com.payments.fraud.featurestore.FeatureVector;
import com.payments.fraud.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Routes transactions to the appropriate scoring pipeline based on channel.
 *
 * Two distinct pipelines (see ADR-002):
 *
 * Card-Present (CP) Pipeline:
 *   Primary: Rule engine (< 10ms, deterministic)
 *   Shadow:  ML model (logged but not used for decisioning)
 *   Total latency budget: 50ms
 *
 * Card-Not-Present (CNP) Pipeline:
 *   Primary: ML model ensemble (XGBoost + sequence model)
 *   Supplementary: Rule engine (for explainability)
 *   Total latency budget: 200ms
 *
 * The separation exists because:
 * 1. CP and CNP have different feature availability (CP has EMV data, CNP has device/IP data)
 * 2. Latency budgets differ by 4x (terminal responsiveness vs web checkout UX)
 * 3. Fraud patterns are fundamentally different (counterfeit vs stolen card numbers)
 * 4. False positive costs differ (declining a diner at the table vs declining an online order)
 */
@Service
public class ScoringRouter {

    private static final Logger log = LoggerFactory.getLogger(ScoringRouter.class);

    private static final String CP_PIPELINE = "CP_RULE_ENGINE";
    private static final String CNP_PIPELINE = "CNP_ML_ENSEMBLE";

    @Value("${fraud.thresholds.cp-decline:0.70}")
    private double cpDeclineThreshold;

    @Value("${fraud.thresholds.cp-review:0.40}")
    private double cpReviewThreshold;

    @Value("${fraud.thresholds.cnp-decline:0.65}")
    private double cnpDeclineThreshold;

    @Value("${fraud.thresholds.cnp-review:0.35}")
    private double cnpReviewThreshold;

    private final FeatureStoreClient featureStoreClient;
    private final RuleEngine ruleEngine;
    private final MLScoringEngine mlScoringEngine;
    private final Timer cpScoringTimer;
    private final Timer cnpScoringTimer;

    public ScoringRouter(FeatureStoreClient featureStoreClient,
                         RuleEngine ruleEngine,
                         MLScoringEngine mlScoringEngine,
                         MeterRegistry meterRegistry) {
        this.featureStoreClient = featureStoreClient;
        this.ruleEngine = ruleEngine;
        this.mlScoringEngine = mlScoringEngine;
        this.cpScoringTimer = Timer.builder("fraud.scoring.cp")
            .description("CP pipeline end-to-end latency")
            .register(meterRegistry);
        this.cnpScoringTimer = Timer.builder("fraud.scoring.cnp")
            .description("CNP pipeline end-to-end latency")
            .register(meterRegistry);
    }

    /**
     * Scores a transaction through the appropriate pipeline.
     */
    public ScoringResponse score(TransactionRequest request) {
        long startTime = System.nanoTime();

        // Step 1: Fetch features from the online store
        FeatureVector features = featureStoreClient.getFeatures(
            request.cardToken(),
            request.merchantId(),
            request.deviceFingerprint(),
            request.serverId()
        );

        // Step 2: Route to appropriate pipeline
        ScoringResponse response;
        if (request.isCardPresent()) {
            response = scoreCardPresent(request, features, startTime);
        } else {
            response = scoreCardNotPresent(request, features, startTime);
        }

        return response;
    }

    /**
     * CP Pipeline: Rule engine primary, ML shadow.
     *
     * The ML model runs asynchronously via virtual threads (Java 21).
     * Its predictions are logged for monitoring and comparison but do not
     * influence the authorization decision. This allows safe model validation
     * on production traffic before promotion.
     */
    private ScoringResponse scoreCardPresent(TransactionRequest request,
                                              FeatureVector features,
                                              long startTime) {
        return cpScoringTimer.record(() -> {
            // Primary: rule engine
            RuleEngine.RuleResult ruleResult = ruleEngine.evaluate(request, features);

            // Shadow: ML model (async, non-blocking)
            // In production, use virtual threads: Thread.startVirtualThread(() -> {...})
            try {
                MLScoringEngine.MLResult mlResult = mlScoringEngine.score(features, true);
                log.debug("CP shadow ML score: {} (rule score: {}) for txn {}",
                          mlResult.score(), ruleResult.score(), request.transactionId());
            } catch (Exception e) {
                // Shadow scoring failure is never propagated to the auth path
                log.debug("CP shadow ML scoring failed (non-blocking): {}", e.getMessage());
            }

            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            Map<String, Object> featuresUsed = features.toMap();
            String modelVersion = "rules-v1.0.0";

            return buildResponse(request.transactionId(), ruleResult.score(),
                                 ruleResult.reasonCodes(), latencyMs, CP_PIPELINE,
                                 featuresUsed, modelVersion,
                                 cpDeclineThreshold, cpReviewThreshold);
        });
    }

    /**
     * CNP Pipeline: ML model primary, rules supplementary.
     *
     * The ML model has a richer feature set for CNP (device, IP, email)
     * and a higher latency budget (200ms). Rules provide supplementary
     * reason codes for explainability in dispute evidence.
     */
    private ScoringResponse scoreCardNotPresent(TransactionRequest request,
                                                 FeatureVector features,
                                                 long startTime) {
        return cnpScoringTimer.record(() -> {
            // Primary: ML model
            MLScoringEngine.MLResult mlResult = mlScoringEngine.score(features, false);

            // Supplementary: rules for additional reason codes
            RuleEngine.RuleResult ruleResult = ruleEngine.evaluate(request, features);

            // Combine: use ML score but merge reason codes from both engines
            double combinedScore = mlResult.score() * 0.7 + ruleResult.score() * 0.3;
            List<ReasonCode> combinedReasons = new java.util.ArrayList<>(mlResult.reasonCodes());
            for (ReasonCode rc : ruleResult.reasonCodes()) {
                if (!combinedReasons.contains(rc)) {
                    combinedReasons.add(rc);
                }
            }

            long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
            Map<String, Object> featuresUsed = features.toMap();

            return buildResponse(request.transactionId(), combinedScore,
                                 combinedReasons, latencyMs, CNP_PIPELINE,
                                 featuresUsed, mlResult.modelVersion(),
                                 cnpDeclineThreshold, cnpReviewThreshold);
        });
    }

    private ScoringResponse buildResponse(String transactionId, double score,
                                           List<ReasonCode> reasons, long latencyMs,
                                           String pipeline, Map<String, Object> features,
                                           String modelVersion,
                                           double declineThreshold, double reviewThreshold) {
        if (score >= declineThreshold) {
            return ScoringResponse.decline(transactionId, score, reasons,
                                           latencyMs, pipeline, features, modelVersion);
        } else if (score >= reviewThreshold) {
            return ScoringResponse.review(transactionId, score, reasons,
                                          latencyMs, pipeline, features, modelVersion);
        } else {
            return ScoringResponse.approve(transactionId, score,
                                           latencyMs, pipeline, features, modelVersion);
        }
    }
}
