package com.openim.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.domain.service.MessageService;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全新账号在 prod profile 下走通主流程
 *
 * <b>这个测试存在的理由，就是此前没有它。</b>
 *
 * 改造之前，服务端没有任何创建会话的接口——会话全部来自演示种子，而种子在
 * {@code prod} profile 下不加载。于是生产环境注册出来的账号打开的是一个空会话列表，
 * 无法与任何人建立对话，<b>一条消息都发不出去</b>。而当时 149 个 Java 测试与
 * 14 个 E2E 全部通过：它们用的都是种子里已经存在的 Alice/Bob/Carol 和
 * 已经存在的会话，没有任何一条断言覆盖"一个全新账号能做什么"。
 *
 * 因此这里的每一步都从注册开始，<b>不触碰任何种子数据</b>：
 * 注册 → 建单聊 → 发消息 → 对端读到 → 建群 → 加人 → 成员角色正确。
 *
 * <b>已知局限</b>：集成测试共用一个 PostgreSQL 容器，种子迁移已经应用过，
 * 所以这个测试无法验证"数据库里真的没有种子数据"时的行为。它验证的是
 * "不依赖种子数据也能走通"——对本缺陷而言这两者等价，因为缺的是写路径本身。
 * 真正的"空库 + prod"验证由部署时的冒烟检查承担。
 */
@SpringBootTest(properties = {
        "openim.demo-login-enabled=false",
        "openim.jwt.active-key-id=k1",
        "openim.jwt.keys.k1=production-test-secret-with-more-than-32-characters",
        "openim.client-origin=https://im.example.test",
        // 与其他集成测试共用同一个容器，迁移集合必须一致
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
        // prod 下必须显式关闭密码重置，否则应用拒绝启动（见 ProductionSecurityIntegrationTest）
        "openim.auth.password-reset-enabled=false",
        // 注册是这个测试的前提。生产可以用 REGISTRATION_ENABLED=false 走邀请制，
        // 但那时第一个账号仍需通过某个途径产生。
        "openim.auth.registration-enabled=true",
        // 一次测试里要注册多个账号并连续调用会话接口，默认配额会误伤
        "openim.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionBootstrapIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    /**
     * 发消息只有 WebSocket 入口（{@code message:send}），REST 没有对应端点，
     * 因此这一步直接调服务层。会话的创建与历史读取仍走 HTTP，
     * 保证"接口确实暴露了、鉴权确实通过了"。
     */
    @Autowired
    private MessageService messageService;

    @Test
    void aFreshlyRegisteredUserCanStartAConversationAndExchangeMessages() throws Exception {
        Account alice = register("prodalice");
        Account bob = register("prodbob");

        // 注册后会话列表必须是空的——若不为空说明测试意外用到了种子数据，
        // 那么这个测试就失去了意义
        assertThat(conversationCount(alice)).as("全新账号的会话列表应为空").isZero();

        // 1. 建单聊
        String conversationId = createDirect(alice, bob.userId());

        // 2. 双方都能在自己的列表里看到它
        assertThat(conversationCount(alice)).isEqualTo(1);
        assertThat(conversationCount(bob)).isEqualTo(1);

        // 3. 发消息
        ImMessage sent = messageService.createMessage(UUID.fromString(alice.userId()),
                new SendMessage(conversationId, "client-" + UUID.randomUUID(), "text",
                        Map.of("text", "prod 下的第一条消息")));

        // 4. 对端读到
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(sent.id()))
                .andExpect(jsonPath("$.data[0].content.text").value("prod 下的第一条消息"));
    }

    @Test
    void aFreshlyRegisteredUserCanCreateAGroupAndManageItsMembers() throws Exception {
        Account owner = register("prodowner");
        Account member = register("prodmember");
        Account latecomer = register("prodlate");

        // 1. 建群
        String body = mockMvc.perform(post("/api/v1/conversations/group")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "title", "prod 冒烟群",
                                "memberIds", java.util.List.of(member.userId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("group"))
                .andExpect(jsonPath("$.data.title").value("prod 冒烟群"))
                .andReturn().getResponse().getContentAsString();
        String conversationId = JSON.readTree(body).path("data").path("id").asText();

        // 2. 加人
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "userIds", java.util.List.of(latecomer.userId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.joined[0]").value(latecomer.userId()));

        // 3. 角色正确：创建者是 OWNER，其余是 MEMBER
        String members = mockMvc.perform(
                        get("/api/v1/conversations/" + conversationId + "/members")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode roster = JSON.readTree(members).path("data");
        assertThat(roster).hasSize(3);
        assertThat(roleOf(roster, owner.userId())).isEqualTo("OWNER");
        assertThat(roleOf(roster, member.userId())).isEqualTo("MEMBER");

        // 4. 普通成员不能加人——权限矩阵在 HTTP 层同样生效
        mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "userIds", java.util.List.of(register("prodextra").userId())))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_ROLE"));

        // 5. 非成员看不到成员列表
        Account outsider = register("prodoutsider");
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/members")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + outsider.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_A_MEMBER"));
    }

    /**
     * 两个陌生账号通过用户名互相找到并建立第一次对话。
     *
     * <b>这条路径是收窄用户目录之后的唯一入口。</b>`GET /users` 从"全站所有用户"
     * 改成"与我有会话往来的人"之后，全新账号的联系人列表是空的——没有按用户名
     * 查找的话，两个陌生人永远无法开始对话，那会把隐私改进变成功能缺失。
     */
    @Test
    void twoStrangersCanFindEachOtherByUsernameAndStartTalking() throws Exception {
        Account alice = register("prodfind_a");
        Account bob = register("prodfind_b");

        // 联系人列表是空的：没有任何往来
        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        // 按完整用户名找到对方
        mockMvc.perform(get("/api/v1/users/search")
                        .param("username", bob.username())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(bob.userId()));

        // 前缀搜不到——否则用户名可被枚举
        mockMvc.perform(get("/api/v1/users/search")
                        .param("username", bob.username().substring(0, 6))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        // 建立单聊后对方成为联系人
        createDirect(alice, bob.userId());
        mockMvc.perform(get("/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(bob.userId()));
    }

    /** 单聊幂等：重复"打开与某人的对话"不会产生第二个会话 */
    @Test
    void openingTheSameDirectConversationTwiceDoesNotDuplicateIt() throws Exception {
        Account alice = register("prodidem-a");
        Account bob = register("prodidem-b");

        String first = createDirect(alice, bob.userId());
        String second = createDirect(alice, bob.userId());
        String reversed = createDirect(bob, alice.userId());

        assertThat(second).isEqualTo(first);
        assertThat(reversed).isEqualTo(first);
        assertThat(conversationCount(alice)).isEqualTo(1);
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private record Account(String userId, String username, String accessToken) {
    }

    /**
     * 通过注册接口创建账号。
     *
     * 这是 prod 环境产生第一个账号的唯一途径（见 README 的"生产环境的第一个账号"），
     * 所以测试也必须走它，而不是直接 INSERT 用户——那样就绕过了注册链路本身。
     */
    private Account register(String usernamePrefix) throws Exception {
        // 用户名有 UNIQUE 约束，且只允许小写字母、数字和下划线
        String username = usernamePrefix.replace("-", "_") + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of(
                                "username", username,
                                "displayName", usernamePrefix,
                                "password", "prod-smoke-password-2026",
                                "deviceId", "device-" + UUID.randomUUID(),
                                "deviceName", "Smoke"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = JSON.readTree(response).path("data");
        String userId = data.path("user").path("id").asText();
        String accessToken = data.path("accessToken").asText();
        assertThat(userId).as("注册响应应包含用户 ID").isNotEmpty();
        assertThat(accessToken).as("注册响应应包含访问令牌").isNotEmpty();
        return new Account(userId, username, accessToken);
    }

    private String createDirect(Account actor, String peerId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/conversations/direct")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + actor.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("peerId", peerId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("direct"))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("data").path("id").asText();
    }

    private int conversationCount(Account account) throws Exception {
        String response = mockMvc.perform(get("/api/v1/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + account.accessToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).path("data").size();
    }

    private String roleOf(JsonNode roster, String userId) {
        for (JsonNode member : roster) {
            if (userId.equals(member.path("userId").asText())) {
                return member.path("role").asText();
            }
        }
        throw new AssertionError("成员列表里没有 " + userId);
    }
}
