package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.DomainModels.Wallet;
import com.openim.server.domain.DomainResults.MessagePage;
import com.openim.server.domain.service.ConversationService;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.domain.service.MessageService;
import com.openim.server.domain.service.RedPacketService;
import com.openim.server.domain.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 通用数据查询控制器
 * 提供健康检查、用户列表、会话列表、消息历史和钱包余额等只读接口。
 */
@Tag(name = "通用", description = "健康检查、用户与会话列表")
@RestController
public class GeneralController {
    private final UserService userService;
    private final ConversationService conversationService;
    private final MessageService messageService;
    private final RedPacketService redPacketService;

    public GeneralController(
            UserService userService,
            ConversationService conversationService,
            MessageService messageService,
            RedPacketService redPacketService
    ) {
        this.userService = userService;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.redPacketService = redPacketService;
    }

    /** 健康检查。无需认证，供负载均衡与存活探针使用 */
    @GetMapping("/health")
    public ApiEnvelope.Success<Map<String, Object>> health(HttpServletRequest request) {
        return ApiEnvelope.success(Map.of(
                "status", "ok",
                "version", "0.1.0-java",
                "now", Instant.now()
        ), request);
    }

    /** 当前登录用户自己的资料。需 Bearer 令牌 */
    @GetMapping("/api/v1/me")
    public ApiEnvelope.Success<User> me(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(userService.getUser(auth.userUuid()), request);
    }

    /**
     * 我的联系人：与我至少共处过一个会话的人。
     *
     * <b>此前这个接口返回全站所有 ACTIVE 用户</b>，无分页、无可见性边界，
     * 且客户端加载时就会请求它。任何登录用户可以枚举全部注册者的用户名与在线状态，
     * 响应体还随注册量线性增长——十万用户时这一个接口就能打满连接池。
     *
     * 路径保持 {@code /api/v1/users} 不变：它是已发布的契约，改路径会让所有
     * 现有客户端在同一次发版里失效。返回的仍是 {@code User[]}，只是内容收窄了。
     */
    @GetMapping("/api/v1/users")
    public ApiEnvelope.Success<List<User>> users(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(userService.listContacts(auth.userUuid()), request);
    }

    /**
     * 按<b>完整</b>用户名查找一个人，用于与还没有往来的人建立第一次对话。
     *
     * 联系人列表收窄后必须有这条路径，否则两个陌生用户永远无法开始第一次对话。
     * 刻意不做前缀/模糊匹配——那允许用几百次请求枚举出大部分用户名，
     * 等于把全站列表还回去了，只是慢一点。
     *
     * 返回数组而不是单个对象：与 {@code /users} 同构，客户端可以复用同一段
     * 渲染逻辑；找不到时是空数组而不是 404（"不存在"与"存在但不可见"
     * 在语义上无法区分，而两者对调用方的处理方式相同）。
     */
    @GetMapping("/api/v1/users/search")
    public ApiEnvelope.Success<List<User>> searchUsers(
            @RequestParam String username,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                userService.searchUsers(username, auth.userUuid()), request);
    }

    /**
     * 按 ID 批量取用户资料，用于渲染<b>已知</b>的人。
     *
     * 消息发送者、红包领取者、通话参与者的 ID 由服务端在各自的载荷里给出，
     * 客户端需要把它们换成昵称和头像。没有这个接口的话，收窄联系人列表会让
     * 历史消息里已退群成员的昵称退化成一串 UUID。
     *
     * 用 GET + 逗号分隔的查询参数而不是 POST：这是一次纯查询，
     * 用 POST 会让它无法被 HTTP 缓存，也让"读操作用 POST"这个反例进入代码库。
     * ID 是 36 字符的 UUID，200 个约 7.4KB，在常见的 8KB 请求行上限内——
     * 上限本身由服务端强制（超出部分截断），不依赖 URL 长度来约束。
     */
    @GetMapping("/api/v1/users/lookup")
    public ApiEnvelope.Success<List<User>> lookupUsers(
            @RequestParam List<String> ids,
            HttpServletRequest request
    ) {
        List<java.util.UUID> parsed = ids.stream()
                .filter(id -> !id.isBlank())
                // 格式非法的 ID 直接跳过而不是报 400：这是批量补齐资料的路径，
                // 一个坏 ID 让整批失败会导致整屏昵称都显示不出来。
                .map(id -> {
                    try {
                        return java.util.UUID.fromString(id.trim());
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return ApiEnvelope.success(userService.lookupUsers(parsed), request);
    }

    /** 我的会话列表。需 Bearer 令牌 */
    @GetMapping("/api/v1/conversations")
    public ApiEnvelope.Success<List<Conversation>> conversations(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(conversationService.listConversations(auth.userUuid()), request);
    }

    /**
     * 会话历史，向<b>后</b>拉取（断线补拉）。
     *
     * 保留原样：这是已发布的契约，客户端的重连补拉逻辑依赖它按 {@code seq} 升序
     * 返回、以末条 seq 作为下一次游标。打开会话改用 {@code /messages/latest}，
     * 向上翻阅用 {@code /messages/history}。
     *
     * 三个端点而不是给这一个加参数：三种取数方向的<b>游标语义不同</b>
     * （afterSeq 向后、beforeSeq 向前、latest 无游标），塞进一个端点会得到
     * "afterSeq 和 beforeSeq 同时传时以谁为准"这类只能靠读代码回答的问题，
     * 而每一种组合都是一个可以单独出错的分支。
     */
    @GetMapping("/api/v1/conversations/{id}/messages")
    public ApiEnvelope.Success<List<ImMessage>> messages(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") long afterSeq,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                messageService.listMessages(
                        DomainErrors.parseId(id, "会话"),
                        auth.userUuid(),
                        Math.max(0, afterSeq)),
                request
        );
    }

    /**
     * 会话最新的一页消息，用于<b>打开会话</b>。
     *
     * 此前打开会话是从 {@code afterSeq=0} 一页页翻到底，即把整个会话的全部历史
     * 拉进内存——五万条消息的会话点开就是 500 次串行请求。
     */
    @GetMapping("/api/v1/conversations/{id}/messages/latest")
    public ApiEnvelope.Success<MessagePage> latestMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int limit,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                messageService.listLatestMessages(
                        DomainErrors.parseId(id, "会话"), auth.userUuid(), limit),
                request
        );
    }

    /**
     * 比 {@code beforeSeq} 更早的一页消息，用于向上翻阅历史。
     *
     * {@code hasMore} 由服务端给出而非让客户端按"条数不足一页"推断——
     * 那个推断在最后一页恰好装满时会多发一次请求，用户看到加载指示器闪一下
     * 却没有新内容。翻到 {@code join_seq} 就停住，成员看不到入群前的消息。
     */
    @GetMapping("/api/v1/conversations/{id}/messages/history")
    public ApiEnvelope.Success<MessagePage> messageHistory(
            @PathVariable String id,
            @RequestParam long beforeSeq,
            @RequestParam(defaultValue = "0") int limit,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(
                messageService.listMessagesBefore(
                        DomainErrors.parseId(id, "会话"), auth.userUuid(), beforeSeq, limit),
                request
        );
    }

    /** 我的钱包余额。需 Bearer 令牌 */
    @GetMapping("/api/v1/wallet")
    public ApiEnvelope.Success<Wallet> wallet(HttpServletRequest request) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        return ApiEnvelope.success(redPacketService.getWallet(auth.userUuid()), request);
    }
}
