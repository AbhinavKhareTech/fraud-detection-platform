# Fraud Detection Platform

A production-grade, real-time fraud scoring and chargeback management system designed for card-present (CP) and card-not-present (CNP) payment environments at scale.

This platform is architected for merchant payment processors handling **$50B+ quarterly GPV** across **100K+ merchant locations**, with sub-50ms scoring latency in the payment authorization path.

## Architecture Overview

```
                                    +------------------+
                                    |   POS Terminal   |
                                    |  (EMV/NFC/Swipe) |
                                    +--------+---------+
                                             |
                                             v
+------------------+              +----------+-----------+              +------------------+
|                  |   auth req   |                      |   score req  |                  |
|  Online Order    +------------->+  Payment Gateway     +------------->+ Fraud Scoring    |
|  (CNP Channel)   |              |  Service             |              | Service          |
+------------------+              |                      |              |  (< 50ms SLA)    |
                                  +----------+-----------+              +--------+---------+
                                             |                                   |
                                             |                          +--------+---------+
                                             |                          |                  |
                                             |                          | Feature Store    |
                                             |                          | (Redis)          |
                                             |                          |                  |
                                             |                          +--------+---------+
                                             |                                   ^
                                             v                                   |
                                  +----------+-----------+              +--------+---------+
                                  |                      |              |                  |
                                  |  Processor Adapter   |              | Feature Pipeline |
                                  |  (Visa/MC/Amex)      |              | (Kafka + Flink)  |
                                  |                      |              |                  |
                                  +----------+-----------+              +------------------+
                                             |                                   ^
                                             v                                   |
                                  +----------+-----------+              +--------+---------+
                                  |                      |              |                  |
                                  |  Transaction Ledger  +------------->+ Chargeback       |
                                  |  (Append-only)       |              | Engine           |
                                  |                      |              |                  |
                                  +----------------------+              +------------------+
```

## System Components

### 1. Fraud Scoring Service (`/fraud-scoring-service`)
Java 21 / Spring Boot microservice that sits in the payment authorization path.

- **Dual pipeline architecture**: Lightweight rule engine for CP transactions (< 10ms), heavier ML ensemble for CNP transactions (< 200ms)
- **Feature store integration**: Redis-backed online feature store with sub-5ms reads
- **Explainable decisions**: Every score includes reason codes for chargeback representment and regulatory audit
- **Circuit breaker tiering**: Resilience4j-based graceful degradation with three dependency tiers
- **ONNX Runtime inference**: XGBoost model served via ONNX for deterministic latency

### 2. Feature Engineering Pipeline (`/feature-pipeline`)
Python-based streaming pipeline that computes real-time fraud features.

- **Velocity features**: Card velocity, merchant velocity, geographic velocity across sliding windows (1hr, 24hr, 7d)
- **Restaurant-specific features**: Transaction amount vs average check size ratio, operating hours flag, server behavioral patterns
- **Streaming computation**: Event-driven architecture consuming from Kafka topics
- **Dual-layer store**: Hot features in Redis (online serving), cold features in PostgreSQL (model training)

### 3. Chargeback Evidence Engine (`/chargeback-engine`)
Automated dispute management and evidence assembly.

- **Evidence orchestration**: Parallel fan-out to transaction, receipt, merchant, and EMV cryptogram services
- **Auto-decisioning**: Rule-based representment decisions based on reason code, evidence strength, and dollar threshold
- **Win rate optimization**: Feedback loop from dispute outcomes to fraud scoring model training data

## Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| CP fraud model | XGBoost via ONNX Runtime | Sub-5ms inference, deterministic latency, feature importance for explainability |
| CNP fraud model | Stacked ensemble (XGBoost + sequence model) | Higher latency budget (200ms) allows richer features |
| Feature store | Redis Cluster | Sub-5ms reads, native TTL for sliding window expiry |
| Circuit breaker | Resilience4j with 3-tier classification | Fraud scoring degrades gracefully; processor failover is hard switch |
| Ledger | Append-only PostgreSQL with serializable isolation | Zero tolerance for double-spend; immutable audit trail |
| Message bus | Kafka | Ordered event streams for feature computation; replay capability for model retraining |
| Model format | ONNX | Runtime-agnostic, embeddable in Java without Python dependency |

## Architecture Decision Records

Detailed rationale for each design choice is documented in [`/docs/adr`](docs/adr/):

- [ADR-001: XGBoost over Neural Networks for CP Fraud Scoring](docs/adr/ADR-001-xgboost-over-neural-nets.md)
- [ADR-002: Separate CP and CNP Scoring Pipelines](docs/adr/ADR-002-separate-cp-cnp-pipelines.md)
- [ADR-003: Redis over DynamoDB for Online Feature Store](docs/adr/ADR-003-redis-over-dynamodb.md)
- [ADR-004: Three-Tier Circuit Breaker Classification](docs/adr/ADR-004-circuit-breaker-tiering.md)
- [ADR-005: Append-Only Ledger with Merchant-ID Sharding](docs/adr/ADR-005-append-only-ledger-sharding.md)
- [ADR-006: Tokenization Boundary for PCI Scope Minimization](docs/adr/ADR-006-tokenization-boundary.md)

## Restaurant-Vertical Fraud Advantage

Unlike horizontal payment processors, this platform is designed for restaurant and food-service merchants, enabling fraud features that generic processors cannot compute:

| Feature | Signal | Why Generic Processors Lack This |
|---------|--------|----------------------------------|
| `txn_amount / avg_check_size` | Anomaly detection normalized per restaurant | Requires POS menu/order data |
| `is_within_operating_hours` | Transaction outside business hours | Requires merchant schedule data |
| `server_refund_rate_vs_avg` | Employee fraud detection | Requires POS employee tracking |
| `card_merchant_affinity` | Repeat customer identification | Requires loyalty/guest data |
| `order_item_count_ratio` | Transactions without matching orders | Requires order management integration |
| `tip_pattern_anomaly` | Stolen cards rarely tip at fine dining | Requires tip data from POS |

## Latency Budget

```
Total authorization SLA: 3000ms (terminal to terminal)

Breakdown:
  Terminal -> Gateway:       200ms (network)
  Gateway -> Fraud Scorer:    10ms (internal)
  Fraud Scoring (CP):         40ms (rule engine + feature lookup)
  Fraud Scoring (CNP):       180ms (ML inference + enrichment)
  Gateway -> Processor:      800ms (external network + processing)
  Processor -> Gateway:      800ms (response)
  Gateway -> Terminal:       200ms (network)
  Buffer:                    770ms
```

## Running Locally

### Prerequisites
- Java 21+
- Maven 3.9+
- Redis 7+ (or Docker)
- Python 3.10+ (for feature pipeline)

### Fraud Scoring Service
```bash
cd fraud-scoring-service
mvn clean package
java -jar target/fraud-scoring-service-1.0.0.jar
```

### Feature Pipeline
```bash
cd feature-pipeline
pip install -r requirements.txt
python src/feature_pipeline.py
```

### Run Tests
```bash
cd fraud-scoring-service
mvn test
```

## API Examples

### Score a Card-Present Transaction
```bash
curl -X POST http://localhost:8080/api/v1/fraud/score \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "txn_abc123",
    "merchant_id": "mch_restaurant_456",
    "card_token": "tok_xxxx1234",
    "amount_cents": 4500,
    "currency": "USD",
    "channel": "CARD_PRESENT",
    "entry_mode": "EMV_CHIP",
    "terminal_id": "term_789",
    "server_id": "srv_jane",
    "timestamp": "2026-03-15T19:30:00Z"
  }'
```

### Response
```json
{
  "transaction_id": "txn_abc123",
  "risk_score": 0.12,
  "decision": "APPROVE",
  "reason_codes": [],
  "scoring_latency_ms": 8,
  "pipeline": "CP_RULE_ENGINE",
  "features_used": {
    "card_velocity_1h": 1,
    "merchant_velocity_1h": 45,
    "amount_vs_avg_check": 1.02,
    "within_operating_hours": true,
    "card_merchant_affinity": 0.85
  }
}
```

### Score a Card-Not-Present Transaction
```bash
curl -X POST http://localhost:8080/api/v1/fraud/score \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "txn_online_789",
    "merchant_id": "mch_restaurant_456",
    "card_token": "tok_xxxx5678",
    "amount_cents": 8900,
    "currency": "USD",
    "channel": "CARD_NOT_PRESENT",
    "entry_mode": "ONLINE_ORDER",
    "ip_address": "203.0.113.42",
    "device_fingerprint": "dfp_abc123",
    "email": "customer@example.com",
    "delivery_address_hash": "addr_hash_xyz",
    "timestamp": "2026-03-15T20:15:00Z"
  }'
```

## Project Structure

```
fraud-detection-platform/
+-- fraud-scoring-service/          # Java 21 / Spring Boot microservice
|   +-- src/main/java/com/payments/fraud/
|   |   +-- FraudScoringApplication.java
|   |   +-- config/
|   |   |   +-- RedisConfig.java
|   |   |   +-- CircuitBreakerConfig.java
|   |   |   +-- ScoringConfig.java
|   |   +-- controller/
|   |   |   +-- FraudScoringController.java
|   |   |   +-- HealthController.java
|   |   +-- engine/
|   |   |   +-- RuleEngine.java
|   |   |   +-- MLScoringEngine.java
|   |   |   +-- ScoringRouter.java
|   |   +-- featurestore/
|   |   |   +-- FeatureStoreClient.java
|   |   |   +-- FeatureVector.java
|   |   +-- model/
|   |   |   +-- TransactionRequest.java
|   |   |   +-- ScoringResponse.java
|   |   |   +-- RiskDecision.java
|   |   |   +-- ReasonCode.java
|   |   +-- service/
|   |   |   +-- FraudScoringService.java
|   |   |   +-- TransactionEnricher.java
|   |   +-- circuit/
|   |       +-- DependencyTier.java
|   |       +-- TieredCircuitBreaker.java
|   +-- src/main/resources/
|   |   +-- application.yml
|   |   +-- rules/
|   |       +-- cp-rules.json
|   |       +-- cnp-rules.json
|   +-- src/test/java/com/payments/fraud/
|   |   +-- FraudScoringServiceTest.java
|   |   +-- RuleEngineTest.java
|   |   +-- CircuitBreakerTest.java
|   +-- pom.xml
|   +-- Dockerfile
+-- feature-pipeline/               # Python streaming feature computation
|   +-- src/
|   |   +-- feature_pipeline.py
|   |   +-- feature_definitions.py
|   |   +-- feature_store_writer.py
|   |   +-- restaurant_features.py
|   +-- requirements.txt
|   +-- tests/
|       +-- test_features.py
+-- chargeback-engine/              # Python chargeback evidence assembly
|   +-- src/
|   |   +-- chargeback_orchestrator.py
|   |   +-- evidence_assembler.py
|   |   +-- decisioning_engine.py
|   +-- requirements.txt
+-- docs/
|   +-- adr/                        # Architecture Decision Records
|   +-- diagrams/
+-- .github/workflows/
|   +-- ci.yml
+-- .gitignore
+-- LICENSE
```

## Technology Stack

- **Language**: Java 21 (fraud scoring service), Python 3.12 (pipelines)
- **Framework**: Spring Boot 3.2
- **Feature Store**: Redis 7 Cluster
- **Model Serving**: ONNX Runtime (embedded in JVM)
- **Circuit Breaker**: Resilience4j 2.x
- **Message Bus**: Apache Kafka (feature pipeline ingestion)
- **Database**: PostgreSQL 16 (ledger, training data)
- **Observability**: Micrometer + Prometheus metrics
- **Container**: Docker + Kubernetes-ready

## License

MIT License. See [LICENSE](LICENSE) for details.
