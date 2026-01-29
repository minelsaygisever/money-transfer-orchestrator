package com.minelsaygisever.transfer.domain;

import com.minelsaygisever.transfer.domain.enums.TransferState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("transfers")
public class Transfer {

    @Id
    private Long id;

    private String idempotencyKey;

    private UUID transactionId;

    private String senderAccountId;

    private String receiverAccountId;

    private BigDecimal amount;

    private String currency;

    private TransferState state;

    private String failureReason;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}