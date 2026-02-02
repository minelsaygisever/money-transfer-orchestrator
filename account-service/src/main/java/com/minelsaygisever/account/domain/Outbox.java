package com.minelsaygisever.account.domain;

import com.minelsaygisever.account.domain.enums.AggregateType;
import com.minelsaygisever.common.domain.enums.EventType;
import com.minelsaygisever.account.domain.enums.OutboxStatus;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("outbox")
public class Outbox {

    @Id
    @Column("id")
    private Long id;

    @Column("aggregate_type")
    private AggregateType aggregateType; // domain (ACCOUNT)

    @Column("aggregate_id")
    private String aggregateId;

    @Column("type")
    private EventType type;          // event (ACCOUNT_DEBITED)

    @Column("payload")
    private String payload;       // content (JSON string)

    @Column("status")
    private OutboxStatus status;

    @Column("retry_count")
    private Integer retryCount;

    @CreatedDate
    @Column("created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private LocalDateTime updatedAt;

    @Column("next_attempt_time")
    private LocalDateTime nextAttemptTime;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Outbox outbox = (Outbox) o;
        return id != null && Objects.equals(id, outbox.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
