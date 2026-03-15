# ADR-006: Tokenization Boundary for PCI Scope Minimization

## Status
Accepted

## Context
As a PCI Level 1 Service Provider, every service that touches cardholder data (PAN, CVV, expiry, track data) is within PCI audit scope. Audit scope carries significant engineering constraints and compliance costs.

## Decision
Implement a tokenization boundary at the earliest possible point in the payment flow. Only the tokenization service and the processor adapter operate within PCI scope.

## Architecture

```
POS Terminal           Tokenization         Fraud Scoring        Processor Adapter
[Card Data] ---------> [PAN -> Token] -----> [Token only] -----> [Token -> PAN]
  (encrypted)            PCI SCOPE            OUT OF SCOPE         PCI SCOPE
```

### What is in PCI scope (2 services)
1. **Tokenization service**: Receives encrypted card data from the terminal, generates a token, stores the PAN-to-token mapping in an HSM-backed vault.
2. **Processor adapter**: De-tokenizes to send the PAN to the card network for authorization.

### What is out of PCI scope (everything else)
- Fraud scoring service (operates on tokens)
- Feature pipeline (operates on tokens)
- Analytics and data warehouse (operates on tokens)
- Merchant dashboards (display masked card: **** 1234)
- Chargeback engine (uses tokens and last-4 digits)

## Token Format
Tokens preserve the last 4 digits and BIN (first 6 digits) of the PAN:
- PAN: `4111 1111 1111 1234`
- Token: `tok_411111_xxxxx_1234` (BIN preserved for routing, last 4 for display)

This allows downstream services to perform BIN-based routing and display masked card numbers without de-tokenizing.

## PCI Boundary Enforcement
- Automated PCI boundary tests in CI/CD scan API schemas and data models for PAN patterns (Luhn-valid 13-19 digit numbers)
- Log sanitization library is a mandatory dependency in every service, scanning log output for PAN patterns and redacting before write
- Network segmentation: tokenization service and processor adapter run in a separate VPC with explicit firewall rules
- Service-to-service communication within PCI scope uses mTLS

## Consequences
- Adding a new service to the payment flow does not expand PCI scope if it operates on tokens
- PCI audit scope is limited to 2 services instead of 10+, reducing audit cost and engineering constraints
- Token vault (PAN-to-token mapping) is a critical single point of failure requiring HSM-backed encryption, synchronous replication, and continuous backup
- P2PE (Point-to-Point Encryption) at the terminal ensures card data is encrypted before it hits the restaurant's network, taking the merchant entirely out of PCI scope
