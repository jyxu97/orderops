package com.orderops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderOpsApplication.class, args);
    }
}
