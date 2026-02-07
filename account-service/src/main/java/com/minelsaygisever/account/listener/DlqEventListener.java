package com.minelsaygisever.account.listener;

import com.minelsaygisever.account.service.DlqNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class DlqEventListener {

    private final DlqNotificationService notificationService;

    @Bean
    public Consumer<Message<String>> onAccountDlqEvent() {
        return message -> {
            String payload = message.getPayload();
            String exceptionMessage = "Processing Failed (Unknown Reason)";

            if (message.getHeaders().containsKey("x-exception-message")) {
                Object headerVal = message.getHeaders().get("x-exception-message");
                exceptionMessage = headerVal != null ? new String((byte[]) headerVal) : exceptionMessage;
            }

            notificationService.sendAlert(exceptionMessage, payload);
        };
    }
}
