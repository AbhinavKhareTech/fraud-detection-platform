package com.payments.fraud;

import com.payments.fraud.engine.RuleEngine;
import com.payments.fraud.featurestore.FeatureVector;
import com.payments.fraud.model.ReasonCode;
import com.payments.fraud.model.TransactionRequest;
import com.payments.fraud.model.TransactionRequest.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the CP rule engine.
 *
 * Test strategy: each test isolates a single fraud signal to verify
 * that the rule fires correctly and produces the expected reason code.
 * Integration tests (not included here) would test the full pipeline
 * with Redis and the feature store.
 */
class RuleEngineTest {

    private RuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        ruleEngine = new RuleEngine(new SimpleMeterRegistry());
    }

    private TransactionRequest buildRequest(long amountCents) {
        return new TransactionRequest(
            "txn_test_001", "mch_restaurant_001", "tok_xxxx1234",
            amountCents, "USD", Channel.CARD_PRESENT, "EMV_CHIP",
            "term_001", "srv_jane", null, null, null, null,
            Instant.now()
        );
    }

    private FeatureVector buildCleanFeatures() {
        FeatureVector fv = new FeatureVector();
        fv.setCardVelocity1h(1);
        fv.setCardVelocity24h(3);
        fv.setCardVelocity7d(10);
        fv.setMerchantVelocity1h(45);
        fv.setCardMerchantAffinity(0.8);
        fv.setMerchantAvgCheckSize(35.00);
        fv.setWithinOperatingHours(true);
        fv.setHasMatchingOrder(true);
        fv.setServerRefundRateVsAvg(1.0);
        fv.setServerVoidCount24h(2);
        fv.setGeoVelocityKmPerHour(0.0);
        fv.setTipAnomaly(false);
        return fv;
    }

    @Test
    @DisplayName("Clean transaction should have low score and no reason codes")
    void cleanTransaction() {
        TransactionRequest request = buildRequest(3500); // $35, close to avg
        FeatureVector features = buildCleanFeatures();

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertTrue(result.score() < 0.1, "Clean transaction should score below 0.1");
        assertTrue(result.reasonCodes().isEmpty(), "Clean transaction should have no reason codes");
    }

    @Test
    @DisplayName("High card velocity in 1 hour should trigger velocity rule")
    void highCardVelocity1h() {
        TransactionRequest request = buildRequest(3500);
        FeatureVector features = buildCleanFeatures();
        features.setCardVelocity1h(8); // exceeds threshold of 5

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertTrue(result.score() >= 0.25);
        assertTrue(result.reasonCodes().contains(ReasonCode.VELOCITY_CARD_HIGH_1H));
    }

    @Test
    @DisplayName("Impossible travel should produce high score")
    void impossibleTravel() {
        TransactionRequest request = buildRequest(3500);
        FeatureVector features = buildCleanFeatures();
        features.setGeoVelocityKmPerHour(1200.0); // 1200 km/h, clearly impossible by car

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertTrue(result.score() >= 0.45, "Impossible travel should score >= 0.45");
        assertTrue(result.reasonCodes().contains(ReasonCode.GEO_IMPOSSIBLE_TRAVEL));
    }

    @Test
    @DisplayName("Transaction outside operating hours should trigger restaurant rule")
    void outsideOperatingHours() {
        TransactionRequest request = buildRequest(3500);
        FeatureVector features = buildCleanFeatures();
        features.setWithinOperatingHours(false);

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertTrue(result.score() >= 0.35);
        assertTrue(result.reasonCodes().contains(ReasonCode.RESTAURANT_OUTSIDE_HOURS));
    }

    @Test
    @DisplayName("Transaction amount 5x merchant average should trigger amount rule")
    void highAmountVsAverage() {
        // Average check is $35, transaction is $200 (5.7x average)
        TransactionRequest request = buildRequest(20000);
        FeatureVector features = buildCleanFeatures();

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertTrue(result.reasonCodes().contains(ReasonCode.AMOUNT_ABOVE_MERCHANT_AVG));
    }

    @Test
    @DisplayName("No matching order should trigger missing order rule")
    void noMatchingOrder() {
        TransactionRequest request = buildRequest(3500);
        FeatureVector features = buildCleanFeatures();
        features.setHasMatchingOrder(false);

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertTrue(result.reasonCodes().contains(ReasonCode.RESTAURANT_NO_MATCHING_ORDER));
    }

    @Test
    @DisplayName("Server with high refund rate should trigger employee fraud rule")
    void serverRefundAnomaly() {
        TransactionRequest request = buildRequest(3500);
        FeatureVector features = buildCleanFeatures();
        features.setServerRefundRateVsAvg(3.0); // 3x merchant average

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertTrue(result.reasonCodes().contains(ReasonCode.RESTAURANT_SERVER_ANOMALY));
    }

    @Test
    @DisplayName("Multiple signals should accumulate score and cap at 1.0")
    void multipleSignalsCap() {
        TransactionRequest request = buildRequest(3500);
        FeatureVector features = buildCleanFeatures();
        features.setCardVelocity1h(10);
        features.setGeoVelocityKmPerHour(1500);
        features.setWithinOperatingHours(false);
        features.setHasMatchingOrder(false);

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertEquals(1.0, result.score(), 0.001, "Score should cap at 1.0");
        assertTrue(result.reasonCodes().size() >= 4, "Should have at least 4 reason codes");
    }

    @Test
    @DisplayName("Round number large amount should trigger testing-stolen-cards rule")
    void roundNumberAmount() {
        TransactionRequest request = buildRequest(50000); // $500.00, round number
        FeatureVector features = buildCleanFeatures();
        features.setMerchantAvgCheckSize(45.00);

        RuleEngine.RuleResult result = ruleEngine.evaluate(request, features);

        assertTrue(result.reasonCodes().contains(ReasonCode.AMOUNT_ROUND_NUMBER));
    }

    @Test
    @DisplayName("Scoring latency should be under 10ms for rule engine")
    void scoringLatency() {
        TransactionRequest request = buildRequest(3500);
        FeatureVector features = buildCleanFeatures();

        // Warm up
        for (int i = 0; i < 100; i++) {
            ruleEngine.evaluate(request, features);
        }

        // Measure
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            ruleEngine.evaluate(request, features);
        }
        long avgNanos = (System.nanoTime() - start) / 1000;
        long avgMicros = avgNanos / 1000;

        assertTrue(avgMicros < 1000, // < 1ms average (well within 10ms budget)
            "Average rule engine latency should be under 1ms, was: " + avgMicros + " micros");
    }
}
