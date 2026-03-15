package com.payments.fraud.model;

/**
 * Terminal decision communicated to the payment gateway.
 *
 * APPROVE: Transaction proceeds to processor authorization.
 * DECLINE: Transaction is blocked before reaching the processor. The gateway
 *          returns a soft decline to the terminal, preserving the merchant
 *          relationship (the diner sees "please try another card").
 * REVIEW:  Transaction proceeds with a post-authorization flag. An async
 *          review task is created for the fraud ops team. This is the
 *          preferred path for borderline scores to avoid false positives
 *          that damage merchant experience.
 */
public enum RiskDecision {
    APPROVE,
    DECLINE,
    REVIEW
}
