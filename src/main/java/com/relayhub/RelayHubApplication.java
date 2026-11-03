package com.relayhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RelayHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelayHubApplication.class, args);
    }
}

