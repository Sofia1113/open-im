package com.openim.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 契约测试
 *
 * 规范由 springdoc 从控制器实际签名生成，因此它不会与实现漂移——
 * 但"规范能生成"和"规范有用"是两件事。这里验证后者：
 * <ul>
 *   <li>规范确实能生成且是合法 JSON（注解写错会让整个 {@code /v3/api-docs} 500）</li>
 *   <li>每一个 HTTP 端点都出现在规范里，没有漏掉的</li>
 *   <li>认证方案已声明，且 auth 端点被标记为无需认证</li>
 * </ul>
 *
 * 用真实 Servlet 容器（{@code RANDOM_PORT}）而不是 MockMvc：springdoc 的端点
 * 依赖完整的 web 环境，MockMvc 下取不到。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "springdoc.api-docs.enabled=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiContractTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    private JsonNode spec;

    @BeforeAll
    void fetchSpec() {
        // 延迟到第一个测试前取一次，后续用例复用
    }

    private JsonNode spec() {
        if (spec == null) {
            String body = restTemplate.getForObject(
                    "http://localhost:" + port + "/v3/api-docs", String.class);
            assertThat(body)
                    .as("规范端点应返回内容。返回 null 通常意味着注解写错导致端点 500")
                    .isNotNull();
            try {
                spec = new ObjectMapper().readTree(body);
            } catch (Exception exception) {
                throw new AssertionError("规范不是合法 JSON：" + body, exception);
            }
        }
        return spec;
    }

    @Test
    void specificationIsGeneratedAndWellFormed() {
        JsonNode root = spec();

        assertThat(root.path("openapi").asText()).startsWith("3.");
        assertThat(root.path("info").path("title").asText()).isEqualTo("OpenIM API");
        assertThat(root.path("info").path("version").asText()).isEqualTo("v1");
        assertThat(root.path("paths").isObject()).isTrue();
        assertThat(root.path("paths").size())
                .as("规范里应当有多个路径")
                .isGreaterThan(5);
    }

    /**
     * 每个端点都必须出现在规范里。
     *
     * 漏掉的端点在规范里不存在，而客户端 SDK 是从规范生成的——
     * 结果是那个端点对 SDK 使用者不可见，而没有任何错误提示。
     */
    @Test
    void everyApiPathAppearsInTheSpecification() {
        Set<String> documented = new LinkedHashSet<>();
        spec().path("paths").fieldNames().forEachRemaining(documented::add);

        // 这些是当前实现的全部 HTTP 端点前缀。新增控制器时这个列表要同步——
        // 不同步的话测试会失败，那正是提醒。
        List<String> expected = List.of(
                "/api/v1/auth/login",
                "/api/v1/auth/register",
                "/api/v1/auth/refresh",
                "/api/v1/auth/password/forgot",
                "/api/v1/auth/password/reset",
                "/api/v1/sessions/password",
                "/api/v1/account",
                "/api/v1/red-packets",
                "/api/v1/calls",
                "/api/v1/me"
        );

        List<String> missing = new ArrayList<>();
        for (String path : expected) {
            if (documented.stream().noneMatch(d -> d.equals(path) || d.startsWith(path))) {
                missing.add(path);
            }
        }

        assertThat(missing)
                .as("这些端点没有出现在 OpenAPI 规范里，从规范生成的客户端将看不到它们。"
                        + "实际收录：%s", documented)
                .isEmpty();
    }

    @Test
    void bearerAuthenticationSchemeIsDeclared() {
        JsonNode scheme = spec().path("components").path("securitySchemes").path("bearerAuth");

        assertThat(scheme.isMissingNode()).as("必须声明 bearerAuth 安全方案").isFalse();
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");
    }

    /**
     * 登录端点不得要求认证。
     *
     * 全局声明了 Bearer 要求，{@code AuthController} 用
     * {@code @SecurityRequirements} 覆盖。写错的话生成的 SDK 会在调用登录前
     * 就要求一个还不存在的令牌——一个只有到运行时才会发现的死锁。
     */
    @Test
    void authEndpointsAreMarkedAsNotRequiringAuthentication() {
        JsonNode login = spec().path("paths").path("/api/v1/auth/login").path("post");

        assertThat(login.isMissingNode()).isFalse();
        assertThat(login.has("security"))
                .as("登录端点应显式声明空的 security，覆盖全局的 Bearer 要求")
                .isTrue();
        assertThat(login.path("security").isEmpty())
                .as("登录端点的 security 应为空数组")
                .isTrue();
    }

    /** 受保护端点应当继承全局的 Bearer 要求 */
    @Test
    void protectedEndpointsRequireBearerAuthentication() {
        JsonNode globalSecurity = spec().path("security");

        assertThat(globalSecurity.isArray()).isTrue();
        assertThat(globalSecurity.toString()).contains("bearerAuth");
    }

    /**
     * 把规范导出到 {@code target/openapi.json}。
     *
     * 这不是断言，是<b>产出</b>——CI 把它作为构建产物上传，供客户端生成 SDK
     * 或做跨版本 diff。放在测试里而不是引入 springdoc-openapi-maven-plugin：
     * 那个插件需要在构建期启动一次完整应用，而这里的 Spring 上下文已经起来了，
     * 顺手写个文件就行。
     *
     * 生产环境的 {@code /v3/api-docs} 是关闭的，因此这个文件是规范的唯一来源。
     */
    @Test
    void specificationIsExportedForDownstreamConsumers() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        java.nio.file.Path output = java.nio.file.Path.of("target", "openapi.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), spec());

        assertThat(output).exists();
        assertThat(java.nio.file.Files.size(output))
                .as("导出的规范不应是空文件")
                .isGreaterThan(1000);
    }

    /** 分组标签齐全，使生成的文档按功能域可读而不是一长串平铺的端点 */
    @Test
    void endpointsAreGroupedByTag() {
        Set<String> tags = new LinkedHashSet<>();
        spec().path("paths").forEach(pathItem ->
                pathItem.forEach(operation ->
                        operation.path("tags").forEach(tag -> tags.add(tag.asText()))));

        assertThat(tags)
                .as("每个控制器都应有 @Tag")
                .contains("认证", "会话与设备", "账号生命周期", "红包与钱包");
    }
}
