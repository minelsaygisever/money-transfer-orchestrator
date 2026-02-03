package com.minelsaygisever.transfer.job;

import com.minelsaygisever.common.event.refund.AccountRefundFailedEvent;
import com.minelsaygisever.transfer.config.TransferProperties;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.repository.TransferRepository;
import com.minelsaygisever.transfer.service.TransferSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReconciliationJob {

    private final TransferRepository transferRepository;
    private final TransferSagaOrchestrator orchestrator;
    private final TransferProperties properties;

    @Scheduled(fixedRateString = "${transfer.reconciliation.rate:60000}")
    public void scanStuckTransfers() {
        var thresholdDuration = properties.reconciliation().timeoutThreshold();
        var timeoutThreshold = LocalDateTime.now().minus(thresholdDuration);

        var maxRetryDuration = properties.reconciliation().maxRetryDuration();
        var giveUpThreshold = LocalDateTime.now().minus(maxRetryDuration);

        var stuckStates = List.of(TransferState.DEBITED, TransferState.REFUND_INITIATED);

        log.debug("Scanning stuck transfers. Timeout: {}, GiveUp: {}", thresholdDuration, maxRetryDuration);

        transferRepository.findByStateInAndUpdatedAtBefore(stuckStates, timeoutThreshold)
                .flatMap(transfer -> {
                    // --- KILL SWITCH ---
                    if (transfer.getCreatedAt().isBefore(giveUpThreshold)) {
                        log.error("Transfer stuck for too long (>{}). Giving up! Tx: {}",
                                maxRetryDuration, transfer.getTransactionId());

                        return orchestrator.handleRefundFail(
                                new AccountRefundFailedEvent(
                                        transfer.getTransactionId(),
                                        transfer.getSenderAccountId(),
                                        transfer.getAmount(),
                                        transfer.getCurrency(),
                                        "Saga Reconciliation gave up after " + maxRetryDuration
                                )
                        );
                    }

                    log.warn("Stuck transfer detected! Retrying... Tx: {} State: {}",
                            transfer.getTransactionId(), transfer.getState());

                    if (transfer.getState() == TransferState.DEBITED) {
                        return orchestrator.handleTimeout(transfer);
                    } else {
                        return orchestrator.retryRefund(transfer);
                    }
                })
                .subscribe();
    }
}
