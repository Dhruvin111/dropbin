package com.textbin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TextbinApplication {
    public static void main(String[] args) {
        SpringApplication.run(TextbinApplication.class, args);
    }
}
