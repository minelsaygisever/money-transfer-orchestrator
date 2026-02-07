package com.minelsaygisever.account.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DlqNotificationService {

    public void sendAlert(String reason, String payload) {
        log.error("""
            
            =============================================================
            🚨 CRITICAL ALERT: DLQ MESSAGE DETECTED 🚨
            =============================================================
            Attention: Operations Team
            Reason   : {}
            Payload  : {}
            Action   : Please investigate.
            =============================================================
            """, reason, payload);
    }
}
