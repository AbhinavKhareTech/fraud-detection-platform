package com.payments.fraud.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Fraud scoring response returned to the payment gateway.
 *
 * The gateway uses the decision field to approve/decline/review the transaction.
 * Reason codes are preserved for chargeback representment evidence and
 * regulatory audit trails. The features_used map enables debugging and
 * model monitoring without requiring a separate feature logging pipeline.
 */
public record ScoringResponse(

    @JsonProperty("transaction_id")
    String transactionId,

    @JsonProperty("risk_score")
    double riskScore,

    RiskDecision decision,

    @JsonProperty("reason_codes")
    List<ReasonCode> reasonCodes,

    @JsonProperty("scoring_latency_ms")
    long scoringLatencyMs,

    String pipeline,

    @JsonProperty("features_used")
    Map<String, Object> featuresUsed,

    @JsonProperty("model_version")
    String modelVersion
) {

    /**
     * Factory for an approved transaction with no risk signals.
     */
    public static ScoringResponse approve(
            String transactionId,
            double score,
            long latencyMs,
            String pipeline,
            Map<String, Object> features,
            String modelVersion) {
        return new ScoringResponse(
            transactionId, score, RiskDecision.APPROVE,
            List.of(), latencyMs, pipeline, features, modelVersion
        );
    }

    /**
     * Factory for a declined transaction with reason codes.
     * Reason codes are critical for chargeback evidence assembly.
     */
    public static ScoringResponse decline(
            String transactionId,
            double score,
            List<ReasonCode> reasons,
            long latencyMs,
            String pipeline,
            Map<String, Object> features,
            String modelVersion) {
        return new ScoringResponse(
            transactionId, score, RiskDecision.DECLINE,
            reasons, latencyMs, pipeline, features, modelVersion
        );
    }

    /**
     * Factory for a transaction flagged for manual review.
     * Used when the score falls in the gray zone between approve and decline thresholds.
     */
    public static ScoringResponse review(
            String transactionId,
            double score,
            List<ReasonCode> reasons,
            long latencyMs,
            String pipeline,
            Map<String, Object> features,
            String modelVersion) {
        return new ScoringResponse(
            transactionId, score, RiskDecision.REVIEW,
            reasons, latencyMs, pipeline, features, modelVersion
        );
    }
}
