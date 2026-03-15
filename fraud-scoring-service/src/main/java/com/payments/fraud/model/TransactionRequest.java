package com.payments.fraud.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/**
 * Inbound transaction scoring request.
 * Supports both card-present (CP) and card-not-present (CNP) transactions.
 * CP transactions originate from POS terminals with EMV/NFC entry modes.
 * CNP transactions originate from online ordering channels.
 */
public record TransactionRequest(

    @NotBlank
    @JsonProperty("transaction_id")
    String transactionId,

    @NotBlank
    @JsonProperty("merchant_id")
    String merchantId,

    @NotBlank
    @JsonProperty("card_token")
    String cardToken,

    @NotNull @Positive
    @JsonProperty("amount_cents")
    Long amountCents,

    @NotBlank
    String currency,

    @NotNull
    Channel channel,

    @NotBlank
    @JsonProperty("entry_mode")
    String entryMode,

    @JsonProperty("terminal_id")
    String terminalId,

    @JsonProperty("server_id")
    String serverId,

    @JsonProperty("ip_address")
    String ipAddress,

    @JsonProperty("device_fingerprint")
    String deviceFingerprint,

    String email,

    @JsonProperty("delivery_address_hash")
    String deliveryAddressHash,

    @NotNull
    Instant timestamp
) {

    public enum Channel {
        CARD_PRESENT,
        CARD_NOT_PRESENT
    }

    /**
     * Determines if this transaction should use the CP scoring pipeline.
     * CP pipeline has stricter latency requirements (< 50ms) and uses
     * the rule engine as the primary scorer with ML in shadow mode.
     */
    public boolean isCardPresent() {
        return channel == Channel.CARD_PRESENT;
    }

    /**
     * Generates a deterministic idempotency key for deduplication.
     * Prevents duplicate scoring when the gateway retries on timeout.
     */
    public String idempotencyKey() {
        return transactionId + ":" + merchantId + ":" + amountCents;
    }
}
