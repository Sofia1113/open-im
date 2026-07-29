package com.openim.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OpenIM 服务端入口
 * Spring Boot 3 应用，提供 REST API 和 WebSocket 实时通信。
 */
@SpringBootApplication
@EnableScheduling
public class OpenImApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpenImApplication.class, args);
    }
}
