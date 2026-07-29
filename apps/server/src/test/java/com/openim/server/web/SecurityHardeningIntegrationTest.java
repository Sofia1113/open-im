package com.openim.server.web;

import com.openim.server.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全响应头与限流的集成验证
 *
 * 限流用较小的配额单独配置，避免为了触发阈值而发出上百个请求拖慢测试。
 */
@SpringBootTest(properties = {
        "openim.rate-limit.enabled=true"
})
@AutoConfigureMockMvc
class SecurityHardeningIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void securityHeadersArePresentOnApiResponses() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    void hstsIsNotSentOutsideProduction() throws Exception {
        // 本地开发走 HTTP，下发 HSTS 会把 localhost 永久锁到 HTTPS
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void repeatedLoginAttemptsAreEventuallyRateLimited() throws Exception {
        // 登录配额为每分钟 10 次，这里连续尝试直到被拒
        int rejectedStatus = 0;
        for (int attempt = 0; attempt < 40; attempt++) {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/demo-login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "username": "does-not-exist",
                                      "deviceId": "rate-limit-probe",
                                      "deviceName": "Probe"
                                    }
                                    """))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) {
                rejectedStatus = 429;
                assertThat(result.getResponse().getHeader("Retry-After")).isNotNull();
                break;
            }
        }

        assertThat(rejectedStatus)
                .as("持续的登录尝试应当在配额耗尽后被拒绝")
                .isEqualTo(429);
    }

    @Test
    void rateLimitedResponsesUseTheStandardErrorEnvelope() throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"not-a-jwt\"}"))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) {
                mockMvc.perform(post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"not-a-jwt\"}"))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                        .andExpect(jsonPath("$.requestId").exists());
                return;
            }
        }
        throw new AssertionError("刷新接口的限流未生效");
    }
}
