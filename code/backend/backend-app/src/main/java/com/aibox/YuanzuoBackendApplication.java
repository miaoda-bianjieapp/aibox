package com.aibox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@EnableScheduling
@SpringBootApplication
public class YuanzuoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(YuanzuoBackendApplication.class, args);
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}

