package com.minelsaygisever.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TransferServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransferServiceApplication.class, args);
    }
}