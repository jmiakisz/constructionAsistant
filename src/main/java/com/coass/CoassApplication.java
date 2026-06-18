package com.coass;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CoassApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoassApplication.class, args);
    }
}
