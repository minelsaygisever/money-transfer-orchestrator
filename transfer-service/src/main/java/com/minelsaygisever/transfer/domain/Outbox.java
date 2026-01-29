package com.minelsaygisever.transfer.domain;

import com.minelsaygisever.transfer.domain.enums.AggregateType;
import com.minelsaygisever.transfer.domain.enums.EventType;
import com.minelsaygisever.transfer.domain.enums.OutboxStatus;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
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
    private Long id;

    private AggregateType aggregateType; // domain (TRANSFER)

    private String aggregateId;

    private EventType type;          // event (TRANSFER_INITIATED)

    private String payload;       // content (JSON string)

    private OutboxStatus status;

    private Integer retryCount;

    @CreatedDate
    private LocalDateTime createdAt;

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