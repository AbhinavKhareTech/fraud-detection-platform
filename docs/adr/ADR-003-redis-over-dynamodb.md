# ADR-003: Redis over DynamoDB for Online Feature Store

## Status
Accepted

## Context
The fraud scoring service requires a low-latency online feature store to serve pre-computed features during transaction authorization. Redis and DynamoDB were the primary candidates.

## Decision
Use Redis Cluster as the online feature store.

## Rationale
- **Latency**: Redis delivers sub-1ms reads for hash operations. DynamoDB single-digit-millisecond reads are adequate but Redis is 3-5x faster, which matters when the total feature fetch budget is 5ms.
- **Data model fit**: Features are stored as hash maps (card_token -> {velocity_1h: 3, velocity_24h: 12, ...}). Redis HGETALL retrieves all features for a key in a single round trip. DynamoDB would require either a single large item or multiple GetItem calls.
- **TTL management**: Redis supports per-key TTL natively, mapping directly to sliding window expiry (1h, 24h, 7d features). DynamoDB TTL is eventual (up to 48 hours delay).
- **Cost at scale**: For 164K merchants with ~1M active cards, the working set is approximately 2-4 GB. A Redis Cluster with 3 shards handles this comfortably. DynamoDB on-demand pricing for the read volume would be significantly higher.

## Consequences
- Redis requires operational management (replication, persistence, failover)
- Feature store becomes a Tier 2 dependency with circuit breaker fallback to defaults
- Backup strategy: Redis RDB snapshots to S3 every hour; feature pipeline can rebuild state from Kafka replay

## Trade-offs Accepted
- Redis is not durable by default. A Redis failure loses the in-memory feature state. Mitigation: the feature pipeline replays the last 7 days of Kafka events to rebuild state. During rebuild, the scoring service operates on default features (degraded but functional).
