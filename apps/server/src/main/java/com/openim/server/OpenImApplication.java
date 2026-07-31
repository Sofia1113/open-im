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
    /** 应用启动入口：装配 Spring Boot 容器，提供 REST API、WebSocket 实时通信与定时任务 */
    public static void main(String[] args) {
        SpringApplication.run(OpenImApplication.class, args);
    }
}
