package com.minelsaygisever.account.domain;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("processed_transactions")
public class ProcessedTransaction {

    @Id
    @Column("transaction_id")
    private UUID transactionId;

    @Column("processed_at")
    private LocalDateTime processedAt;
}
