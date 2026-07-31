package com.openim.server.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 规范配置
 *
 * <b>为什么需要机器可读的规范</b>：此前 API 契约由两份手写文档承担——
 * {@code docs/01-architecture/API_AND_EVENTS.md} 和
 * {@code packages/contracts/src/index.ts} 里的 TypeScript 类型。
 * 两者与实现之间没有任何机制保证一致：改了控制器忘了改文档不会有任何提示，
 * 而"文档说返回 X、实际返回 Y"这类问题只能靠人发现。
 *
 * springdoc 从控制器与 DTO 的<b>实际签名</b>生成规范，因此它不可能与实现漂移。
 * 有了它才能做契约测试、生成客户端 SDK、给第三方接入。
 *
 * <b>为什么用 {@code -api} 而不是 {@code -ui}</b>：{@code springdoc-openapi-starter-webmvc-ui}
 * 会带上 Swagger UI 的静态资源，在服务上开出一个交互式 API 探索界面。
 * 那对开发方便，但它同时是一个信息泄漏面——列出全部端点、参数约束、错误码，
 * 且默认没有访问控制。规范本身用 {@code /v3/api-docs} 取得即可，
 * 需要界面时在本地或内网跑一个独立的 Swagger UI 指向那个 JSON。
 *
 * <b>生产环境默认关闭</b>：见 {@code application-prod.yml} 的
 * {@code springdoc.api-docs.enabled=false}。规范文件由 CI 在构建时导出成产物，
 * 不依赖线上端点——这样既有规范可用，又不必在生产暴露它。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 构建 OpenAPI 3 规范文档。
     *
     * 规范由 springdoc 从控制器与 DTO 的实际签名生成,不会与实现漂移;
     * 声明全局 Bearer 认证方案,并列出当前部署与本地开发两个 server 入口。
     */
    @Bean
    public OpenAPI openImOpenApi(
            @Value("${openim.client-origin:http://localhost:5173}") String clientOrigin
    ) {
        return new OpenAPI()
                .info(new Info()
                        .title("OpenIM API")
                        .version("v1")
                        .description("""
                                类微信即时通信服务端 API。

                                ## 认证

                                除 `/api/v1/auth/**` 之外的全部端点都需要
                                `Authorization: Bearer <accessToken>`。
                                访问令牌有效期 15 分钟，用 `/api/v1/auth/refresh` 刷新——
                                每次刷新都会轮换刷新令牌，客户端必须保存新的那个；
                                继续使用旧令牌会被判定为重放并撤销该设备的全部会话。

                                ## 响应包裹

                                成功响应为 `{ "data": ..., "requestId": "..." }`，
                                失败为 `{ "error": { "code", "message", "details" }, "requestId": "..." }`。
                                `requestId` 与服务端日志中的同名字段对应，排查时提供它可以直接定位。

                                ## 实时通道

                                消息收发与 WebRTC 信令走 WebSocket（`/ws`），不在本规范内——
                                OpenAPI 只描述 HTTP。事件与命令的契约见
                                `docs/01-architecture/API_AND_EVENTS.md`。

                                ## 限流

                                全部端点均有配额，超出返回 429 并带 `Retry-After` 头。
                                凭证类端点（登录、注册、改密、密码重置、注销）的配额显著更紧，
                                且在限流后端故障时选择拒绝而非放行。
                                """)
                        .contact(new Contact().name("OpenIM").url("https://github.com/"))
                        .license(new License().name("见仓库 LICENSE")))
                .servers(List.of(
                        new Server().url(clientOrigin).description("当前部署"),
                        new Server().url("http://127.0.0.1:3001").description("本地开发")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        访问令牌，由 `/api/v1/auth/login` 或
                                        `/api/v1/auth/refresh` 签发。

                                        令牌有效不代表会话有效——账号被封禁或设备被撤销时
                                        同样返回 401，客户端应据此引导重新登录而不是重试。
                                        """)))
                // 全局要求 Bearer；auth 端点在各自的注解上声明为无需认证
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
