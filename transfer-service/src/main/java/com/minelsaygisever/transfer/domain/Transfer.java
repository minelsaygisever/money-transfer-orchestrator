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
import org.springframework.data.relational.core.mapping.Column;
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
    @Column("id")
    private Long id;

    @Column("idempotency_key")
    private String idempotencyKey;

    @Column("transaction_id")
    private UUID transactionId;

    @Column("sender_account_id")
    private String senderAccountId;

    @Column("receiver_account_id")
    private String receiverAccountId;

    @Column("amount")
    private BigDecimal amount;

    @Column("currency")
    private String currency;

    @Column("request_hash")
    private String requestHash;

    @Column("state")
    private TransferState state;

    @Column("failure_reason")
    private String failureReason;

    @Column("created_at")
    @CreatedDate
    private LocalDateTime createdAt;

    @Column("updated_at")
    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Column("version")
    @Version
    private Long version;
}