package com.payments.fraud.controller;

import com.payments.fraud.engine.ScoringRouter;
import com.payments.fraud.model.ScoringResponse;
import com.payments.fraud.model.TransactionRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST API for real-time fraud scoring.
 *
 * This controller sits in the payment authorization critical path.
 * Every millisecond of latency here directly impacts merchant experience
 * (diner waiting at the terminal). Design priorities:
 *
 * 1. No blocking I/O in the request thread
 * 2. Idempotent scoring (retries from gateway do not produce different results)
 * 3. Structured logging with transaction_id for distributed tracing
 * 4. Prometheus metrics for real-time monitoring
 */
@RestController
@RequestMapping("/api/v1/fraud")
public class FraudScoringController {

    private static final Logger log = LoggerFactory.getLogger(FraudScoringController.class);

    private final ScoringRouter scoringRouter;
    private final Counter requestCounter;
    private final Counter declineCounter;

    /**
     * Idempotency cache: prevents duplicate scoring when the payment gateway
     * retries a request on timeout. Uses a TTL-based cache (in production,
     * use Redis with 60-second TTL instead of in-memory map).
     */
    private final Map<String, ScoringResponse> idempotencyCache = new ConcurrentHashMap<>();

    public FraudScoringController(ScoringRouter scoringRouter, MeterRegistry meterRegistry) {
        this.scoringRouter = scoringRouter;
        this.requestCounter = Counter.builder("fraud.scoring.requests")
            .description("Total fraud scoring requests")
            .register(meterRegistry);
        this.declineCounter = Counter.builder("fraud.scoring.declines")
            .description("Transactions declined by fraud scoring")
            .register(meterRegistry);
    }

    /**
     * Score a transaction for fraud risk.
     *
     * Called by the payment gateway before forwarding the authorization
     * request to the card processor. The gateway sets a 50ms timeout
     * for CP transactions and 200ms for CNP.
     *
     * @param request Transaction details including card token, amount, channel
     * @return Scoring response with risk score, decision, and reason codes
     */
    @PostMapping("/score")
    public ResponseEntity<ScoringResponse> scoreTransaction(
            @Valid @RequestBody TransactionRequest request) {

        requestCounter.increment();

        // Idempotency check: return cached result for duplicate requests
        String idempotencyKey = request.idempotencyKey();
        ScoringResponse cached = idempotencyCache.get(idempotencyKey);
        if (cached != null) {
            log.debug("Returning cached score for idempotency key: {}", idempotencyKey);
            return ResponseEntity.ok(cached);
        }

        log.debug("Scoring transaction: id={}, channel={}, amount={}",
                  request.transactionId(), request.channel(), request.amountCents());

        ScoringResponse response = scoringRouter.score(request);

        // Cache the result for idempotency (60s TTL in production)
        idempotencyCache.put(idempotencyKey, response);

        if (response.decision() == com.payments.fraud.model.RiskDecision.DECLINE) {
            declineCounter.increment();
            log.info("DECLINED transaction: id={}, score={}, reasons={}",
                     request.transactionId(), response.riskScore(), response.reasonCodes());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Health check endpoint for Kubernetes liveness and readiness probes.
     * Separate from Spring Actuator to allow custom health logic
     * (e.g., verify feature store connectivity before reporting ready).
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "fraud-scoring-service",
            "pipeline", "CP_RULE_ENGINE + CNP_ML_ENSEMBLE"
        ));
    }
}
