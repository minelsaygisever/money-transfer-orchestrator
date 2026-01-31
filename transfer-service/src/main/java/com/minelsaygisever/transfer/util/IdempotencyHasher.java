package com.minelsaygisever.transfer.util;

import com.minelsaygisever.transfer.dto.TransferCommand;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class IdempotencyHasher {

    private IdempotencyHasher() {}

    public static String hash(TransferCommand cmd) {
        String currency = cmd.currency().trim().toUpperCase(Locale.ROOT);

        BigDecimal amount = cmd.amount();
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Amount must have at most 2 decimal places");
        }
        BigDecimal normalizedAmount = amount.setScale(2, java.math.RoundingMode.UNNECESSARY);

        String canonical = String.join("|",
                cmd.senderAccountId().trim(),
                cmd.receiverAccountId().trim(),
                normalizedAmount.toPlainString(),
                currency
        );

        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash idempotency payload", e);
        }
    }
}
