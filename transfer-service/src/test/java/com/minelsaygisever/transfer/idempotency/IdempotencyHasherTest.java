package com.minelsaygisever.transfer.idempotency;


import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.util.IdempotencyHasher;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyHasherTest {

    @Test
    void shouldThrow_WhenAmountHasMoreThan2Decimals() {
        TransferCommand cmd = new TransferCommand("k", "1", "2", new BigDecimal("10.123"), "try");

        assertThatThrownBy(() -> IdempotencyHasher.hash(cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimal places");
    }
}
