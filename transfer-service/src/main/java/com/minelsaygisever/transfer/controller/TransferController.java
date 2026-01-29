package com.minelsaygisever.transfer.controller;

import com.minelsaygisever.transfer.controller.api.TransferApi;
import com.minelsaygisever.transfer.dto.TransferApiRequest;
import com.minelsaygisever.transfer.dto.TransferCommand;
import com.minelsaygisever.transfer.dto.TransferResponse;
import com.minelsaygisever.transfer.service.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@Slf4j
public class TransferController implements TransferApi {

    private final TransferService transferService;

    @Override
    public Mono<ResponseEntity<TransferResponse>> initiateTransfer(String idempotencyKey, TransferApiRequest requestPayload) {
        log.info("Transfer request received with key: {}", idempotencyKey);

        TransferCommand command = new TransferCommand(
                idempotencyKey,
                requestPayload.senderAccountId(),
                requestPayload.receiverAccountId(),
                requestPayload.amount(),
                requestPayload.currency()
        );

        return transferService.initiateTransfer(command)
                .map(ResponseEntity::ok);
    }
}