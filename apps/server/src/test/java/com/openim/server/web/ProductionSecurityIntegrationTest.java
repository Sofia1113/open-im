package com.openim.server.web;

import com.openim.server.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "openim.demo-login-enabled=false",
        "openim.token-secret=production-test-secret-with-more-than-32-characters",
        "openim.client-origin=https://im.example.test",
        // 与其他集成测试共用同一个容器，迁移集合必须一致，
        // 否则 Flyway 会因为"已应用但本地未解析"的 seed 迁移拒绝启动
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionSecurityIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void demoLoginCanBeDisabledByConfiguration() throws Exception {
        mockMvc.perform(post("/api/v1/auth/demo-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "alice",
                                  "deviceId": "production-device",
                                  "deviceName": "Production"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void productionCorsOnlyAllowsTheConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/users")
                        .header(HttpHeaders.ORIGIN, "https://im.example.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://im.example.test"
                ));

        mockMvc.perform(options("/api/v1/users")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }
}
