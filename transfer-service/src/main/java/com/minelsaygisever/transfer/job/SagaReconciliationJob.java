package com.minelsaygisever.transfer.job;

import com.minelsaygisever.transfer.config.TransferProperties;
import com.minelsaygisever.transfer.domain.enums.TransferState;
import com.minelsaygisever.transfer.repository.TransferRepository;
import com.minelsaygisever.transfer.service.TransferSagaOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class SagaReconciliationJob {

    private final TransferRepository transferRepository;
    private final TransferSagaOrchestrator orchestrator;
    private final TransferProperties properties;

    /**
     * Periodically scans for transfers stuck in DEBITED state.
     */
    @Scheduled(fixedRateString = "${transfer.reconciliation.rate:60000}")
    public void scanStuckTransfers() {
        var thresholdDuration = properties.reconciliation().timeoutThreshold();
        var timeoutThreshold = LocalDateTime.now().minus(thresholdDuration);

        log.debug("Scanning for stuck transfers older than {} (Threshold: {})",
                thresholdDuration, timeoutThreshold);

        transferRepository.findByStateAndUpdatedAtBefore(TransferState.DEBITED, timeoutThreshold)
                .flatMap(transfer -> {
                    log.warn("⚠Stuck transfer detected! Tx: {} | Last Updated: {}",
                            transfer.getTransactionId(), transfer.getUpdatedAt());

                    return orchestrator.handleTimeout(transfer);
                })
                .subscribe();
    }
}
