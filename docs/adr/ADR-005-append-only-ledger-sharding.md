# ADR-005: Append-Only Ledger with Merchant-ID Sharding

## Status
Accepted

## Context
The transaction ledger records every authorization, capture, void, refund, and chargeback event. It must support both operational queries (merchant dashboard: "show my transactions today") and compliance requirements (PCI DSS Requirement 10: maintain audit trails for 18+ months).

## Decision
Implement an append-only ledger on sharded PostgreSQL with merchant_id as the shard key.

## Rationale

### Append-only design
No UPDATE or DELETE operations on ledger tables. A void is a new event that references the original authorization. A refund is a new credit event. This creates a complete, immutable audit trail. A database trigger rejects any UPDATE or DELETE on ledger tables, with a separate privileged archival process for data retention compliance.

### Merchant-ID shard key
The dominant query pattern is "all transactions for merchant X in time range Y" (merchant dashboard, settlement reconciliation, merchant risk scoring). Sharding by merchant_id ensures these queries hit a single shard. Hot merchant mitigation: top 1% of merchants by volume are placed on dedicated shard groups with proportionally more resources.

### Serializable isolation for write path
The ledger write path (authorization creation, capture, void) uses serializable isolation to prevent double-spend scenarios where concurrent capture requests against the same authorization could both succeed. Performance impact is acceptable because the write contention window is narrow (single row per event).

### Synchronous replication
Writes are not acknowledged until both primary and synchronous replica have committed (RPO = 0). This guarantees no data loss on primary failure. The latency cost (~1-2ms per write) is acceptable for a financial ledger. Async replicas in additional AZs serve read traffic.

## Consequences
- No ad-hoc cross-merchant queries without scatter-gather (acceptable: analytics uses the data warehouse, not the operational ledger)
- Shard rebalancing requires planned maintenance when merchant volume distribution shifts significantly
- WAL archiving to S3 for point-in-time recovery
