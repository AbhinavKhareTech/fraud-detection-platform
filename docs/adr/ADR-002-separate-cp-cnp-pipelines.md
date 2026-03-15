# ADR-002: Separate Card-Present and Card-Not-Present Scoring Pipelines

## Status
Accepted

## Context
The fraud scoring service handles both card-present (CP) transactions from POS terminals and card-not-present (CNP) transactions from online ordering. A single unified pipeline was considered against dual specialized pipelines.

## Decision
Maintain two distinct scoring pipelines: a rule-engine-primary pipeline for CP and an ML-primary pipeline for CNP.

## Rationale

### Different latency budgets
CP transactions have a 50ms scoring budget (terminal responsiveness). CNP transactions have a 200ms budget (web checkout UX). A unified pipeline would be constrained by the tighter CP budget, preventing richer ML inference for CNP.

### Different feature availability
CP transactions have EMV chip data, terminal ID, server ID, and physical presence signals. CNP transactions have device fingerprint, IP address, email, delivery address, and browser characteristics. A unified model would waste feature capacity on null values for each channel.

### Different fraud patterns
CP fraud is dominated by lost/stolen cards and employee fraud. CNP fraud is dominated by stolen card numbers from data breaches and account takeover. The feature importance profiles are fundamentally different, meaning a single model would be suboptimal for both.

### Different false positive costs
A false positive on a CP transaction means a diner standing at the counter is told their card was declined. This is a high-cost merchant experience event that generates support calls. A false positive on a CNP transaction means an online order is held for review, which is lower friction. The threshold calibration must differ.

### Independent evolution
The CP pipeline can be updated (e.g., new rule for a specific merchant category) without risk to CNP scoring, and vice versa. A unified pipeline creates coupling where a change to improve CNP detection could degrade CP performance.

## Consequences
- Two model training pipelines must be maintained
- Feature store schema must support channel-specific features
- Monitoring dashboards must track CP and CNP metrics separately
- The scoring router adds a branching layer, increasing code complexity slightly
- Model A/B testing must be configured per-pipeline

## Alternatives Considered
- **Unified pipeline with channel as a feature**: Simpler code but constrained by CP latency budget and produces suboptimal accuracy for both channels.
- **Three pipelines (CP, CNP online, CNP mobile)**: Over-segmented for current scale. Can revisit if mobile ordering fraud patterns diverge significantly from web.
