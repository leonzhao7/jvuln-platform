package com.jvuln;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class JvulnApplication {
    public static void main(String[] args) {
        SpringApplication.run(JvulnApplication.class, args);
    }
}
