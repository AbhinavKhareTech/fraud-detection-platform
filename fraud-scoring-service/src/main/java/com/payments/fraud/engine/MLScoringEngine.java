package com.payments.fraud.engine;

import com.payments.fraud.featurestore.FeatureVector;
import com.payments.fraud.model.ReasonCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * ML-based fraud scoring engine using ONNX Runtime for model inference.
 *
 * Design rationale (see ADR-001, ADR-002):
 * - XGBoost model exported to ONNX format for deterministic inference latency
 * - ONNX Runtime runs natively in the JVM without Python dependency
 * - Feature importance from XGBoost maps directly to reason codes
 * - Separate models for CP (shadow mode) and CNP (primary scorer)
 *
 * Model lifecycle:
 * 1. Training: Python pipeline trains XGBoost on labeled transaction data
 * 2. Export: Model exported to ONNX format with fixed feature schema
 * 3. Validation: Shadow scoring on production traffic for 7 days
 * 4. Deployment: Model file updated via configuration, no service restart
 * 5. Monitoring: Prediction distribution tracked via Micrometer metrics
 *
 * In this reference implementation, the ONNX Runtime integration is stubbed
 * with a feature-weighted scoring function that mirrors model behavior.
 * In production, replace the score() method body with OrtSession inference.
 */
@Component
public class MLScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(MLScoringEngine.class);

    @Value("${fraud.ml.model-version:xgb-v1.0.0}")
    private String modelVersion;

    @Value("${fraud.ml.cp-threshold:0.7}")
    private double cpThreshold;

    @Value("${fraud.ml.cnp-threshold:0.6}")
    private double cnpThreshold;

    private final Timer inferenceTimer;

    public MLScoringEngine(MeterRegistry meterRegistry) {
        this.inferenceTimer = Timer.builder("fraud.ml.inference")
            .description("ML model inference latency")
            .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        log.info("ML Scoring Engine initialized with model version: {}", modelVersion);
        // In production: load ONNX model file here
        // OrtEnvironment env = OrtEnvironment.getEnvironment();
        // OrtSession session = env.createSession("models/fraud_model.onnx");
    }

    /**
     * Scores a feature vector using the ML model.
     *
     * Production implementation would use:
     *   OnnxTensor inputTensor = OnnxTensor.createTensor(env, features.toModelInput());
     *   OrtSession.Result result = session.run(Map.of("features", inputTensor));
     *   float[] output = ((float[][]) result.get(0).getValue())[0];
     *   return output[1]; // probability of fraud class
     *
     * This reference implementation uses a weighted feature scoring function
     * that approximates XGBoost behavior for demonstration purposes.
     */
    public MLResult score(FeatureVector features, boolean isCardPresent) {
        return inferenceTimer.record(() -> {
            float[] input = features.toModelInput();
            double rawScore = computeWeightedScore(input, isCardPresent);
            double threshold = isCardPresent ? cpThreshold : cnpThreshold;

            List<ReasonCode> reasons = new ArrayList<>();
            if (rawScore > threshold) {
                reasons.add(ReasonCode.ML_HIGH_RISK_SCORE);

                // Add specific reason codes based on feature contributions
                // In production, derive these from SHAP values or feature importance
                if (features.getCardVelocity1h() > 3) {
                    reasons.add(ReasonCode.VELOCITY_CARD_HIGH_1H);
                }
                if (features.getGeoVelocityKmPerHour() > 500) {
                    reasons.add(ReasonCode.GEO_IMPOSSIBLE_TRAVEL);
                }
                if (!features.isDeviceSeen() && !isCardPresent) {
                    reasons.add(ReasonCode.DEVICE_FINGERPRINT_NEW);
                }
                if (features.isProxyDetected()) {
                    reasons.add(ReasonCode.IP_PROXY_DETECTED);
                }
            }

            return new MLResult(rawScore, reasons, modelVersion);
        });
    }

    /**
     * Weighted scoring function approximating XGBoost output.
     *
     * Feature weights derived from model feature importance analysis.
     * In production, this entire method is replaced by ONNX Runtime inference.
     */
    private double computeWeightedScore(float[] input, boolean isCardPresent) {
        // Feature indices match FeatureVector.toModelInput() order
        double score = 0.0;

        // Card velocity features (indices 0-3)
        score += sigmoid(input[0] - 4) * 0.12;    // card_velocity_1h
        score += sigmoid(input[1] - 15) * 0.08;   // card_velocity_24h
        score += sigmoid(input[2] - 50) * 0.04;   // card_velocity_7d
        score += sigmoid(input[3] - 200) * 0.03;  // merchant_velocity_1h

        // Behavioral features (indices 4-6)
        score += (1.0 - input[4]) * 0.08;          // inverse card_merchant_affinity
        score += sigmoid(input[6] - 600) * 0.18;   // geo_velocity (impossible travel)

        // Restaurant features (indices 7-10)
        score += (1.0 - input[7]) * 0.15;           // NOT within operating hours
        score += sigmoid(input[8] - 2.0) * 0.08;    // server refund rate anomaly
        score += sigmoid(input[9] - 8) * 0.06;      // server void count
        score += (1.0 - input[10]) * 0.10;          // no matching order

        // CNP-specific features (indices 11-14)
        if (!isCardPresent) {
            score += (1.0 - input[11]) * 0.12;      // device NOT seen before
            score += sigmoid(input[12] - 3) * 0.10; // multiple cards on device
            score += input[13] * 0.08;               // proxy detected
            score += input[14] * 0.06;               // disposable email
        }

        // Tip anomaly (index 16)
        score += input[16] * 0.05;

        return Math.min(Math.max(score, 0.0), 1.0);
    }

    /**
     * Sigmoid function for smooth threshold transitions.
     * Maps (-inf, +inf) to (0, 1) with steepness around zero.
     */
    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    public String getModelVersion() {
        return modelVersion;
    }

    /**
     * Result of ML model inference.
     */
    public record MLResult(double score, List<ReasonCode> reasonCodes, String modelVersion) {}
}
