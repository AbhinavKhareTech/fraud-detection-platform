# ADR-004: Three-Tier Circuit Breaker Classification

## Status
Accepted

## Context
The payment authorization path depends on multiple downstream services (fraud scoring, feature store, enrichment APIs, card processors). Each dependency has different criticality. A uniform circuit breaker configuration would either be too aggressive (unnecessary degradation) or too lenient (cascading failures).

## Decision
Classify all dependencies into three tiers with distinct circuit breaker configurations and fallback strategies.

## Tier Definitions

### Tier 1: Hard Dependencies (Failover, not degrade)
- **What**: Card processor adapters (Visa, Mastercard, Amex)
- **Why**: If you cannot reach the processor, you cannot authorize the payment. Period.
- **Circuit breaker**: 5% failure rate threshold, 30-second sliding window
- **Fallback**: Automatic failover to secondary processor with post-facto reconciliation
- **Recovery**: Half-open permits 10 trial requests before closing

### Tier 2: Soft Dependencies (Degrade gracefully)
- **What**: Feature store (Redis), fraud scoring ML model
- **Why**: These improve authorization quality but are not required. A brief outage should cause slightly reduced fraud detection, not payment failures.
- **Circuit breaker**: 50% failure rate threshold, 10-second sliding window
- **Fallback**: Return conservative default values. Feature store returns neutral features. ML engine returns rule-engine-only score.
- **Recovery**: Half-open permits 10 trial requests before closing

### Tier 3: Optional Dependencies (Skip entirely)
- **What**: IP geolocation, email reputation, device fingerprint enrichment
- **Why**: These add supplementary signals but have zero impact on the authorization decision if unavailable.
- **Circuit breaker**: 25% failure rate threshold, 5-second sliding window
- **Fallback**: Skip the enrichment call, exclude corresponding features from scoring
- **Recovery**: Half-open permits 5 trial requests before closing

## Consequences
- Each new dependency must be classified into a tier during design review
- Circuit breaker metrics (open/closed/half-open state) must be exposed via Prometheus
- Tier classification documented in service dependency map
- Runbook must specify manual override procedures for each tier

## Anti-Pattern Avoided
Cascading timeout propagation: without tiered breakers, a slow Tier 3 enrichment service could consume thread pool capacity in the main scoring service, causing Tier 1 processor requests to queue and timeout. Bulkheads (separate thread pools per tier) prevent this cross-contamination.
