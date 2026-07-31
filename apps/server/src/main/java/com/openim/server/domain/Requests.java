package com.openim.server.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 请求体定义
 * 包含所有 REST API 和 WebSocket 请求的校验约束。
 */
public final class Requests {
    private Requests() {
    }

    /**
     * 演示登录。
     *
     * 用 username 而非 userId：迁移到数据库后用户主键是 uuid，
     * 让客户端硬编码 uuid 既不可读也会在重建演示数据后失效。
     */
    public record DemoLogin(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String deviceId,
            @NotBlank @Size(max = 128) String deviceName
    ) {
    }

    public record Refresh(@NotBlank String refreshToken) {
    }

    /**
     * 凭证登录。
     *
     * identifier 同时接受用户名和邮箱，由服务端判断——让客户端先猜是哪一种
     * 只会在用户输入含 @ 的用户名时出错。
     *
     * 密码的长度约束在这里刻意宽松（只防超大 payload），真正的强度校验在
     * {@link com.openim.server.auth.PasswordPolicy}：注册时需要明确的错误提示，
     * 而登录时不该因为"密码太短"这种提示泄漏出策略细节。
     */
    public record CredentialLogin(
            @NotBlank @Size(max = 254) String identifier,
            @NotBlank @Size(max = 256) String password,
            @NotBlank @Size(min = 3, max = 128) String deviceId,
            @NotBlank @Size(max = 128) String deviceName
    ) {
    }

    /**
     * 申请密码重置。
     *
     * 只要邮箱——不要用户名。用户名是公开可见的（会话成员列表里就有），
     * 允许按用户名申请重置意味着任何人都能对任意账号触发重置邮件。
     * 邮箱只有账号所有者知道，这本身就是第一道验证。
     */
    public record ForgotPassword(
            @NotBlank @Email @Size(max = 254) String email
    ) {
    }

    /**
     * 提交重置令牌与新密码。
     *
     * 令牌长度上限放宽到 128：当前实现是 Base64URL 编码的 32 字节（43 字符），
     * 留出余量以便将来换用更长的令牌而不必改契约。
     */
    public record ResetPassword(
            @NotBlank @Size(max = 128) String token,
            @NotBlank @Size(max = 256) String newPassword
    ) {
    }

    /**
     * 注销账号。
     *
     * 需要当前密码——注销不可逆，不能只凭访问令牌授权。
     */
    public record DeleteAccount(
            @NotBlank @Size(max = 256) String password
    ) {
    }

    /** 注册。邮箱可选——允许纯用户名注册，后续在设置里补充 */
    public record Register(
            @NotBlank
            @Size(min = 3, max = 32)
            @Pattern(regexp = "^[a-z0-9_]+$",
                     message = "用户名只能包含小写字母、数字和下划线")
            String username,

            // 邮箱可选：允许纯用户名注册，后续在设置里补充
            @Email @Size(max = 254) String email,

            @NotBlank @Size(min = 1, max = 64) String displayName,
            @NotBlank @Size(max = 256) String password,
            @NotBlank @Size(min = 3, max = 128) String deviceId,
            @NotBlank @Size(max = 128) String deviceName
    ) {
    }

    public record ChangePassword(
            @NotBlank @Size(max = 256) String currentPassword,
            @NotBlank @Size(max = 256) String newPassword
    ) {
    }

    /**
     * 发送消息。
     *
     * {@code clientMessageId} 由客户端生成，与发送者共同构成幂等键：网络重试时
     * 服务端据此返回首次写入的结果，而不是产生重复消息。
     */
    public record SendMessage(
            @NotBlank String conversationId,
            @NotBlank @Size(min = 8, max = 128) String clientMessageId,
            @NotBlank String type,
            Map<String, Object> content
    ) {
    }

    /**
     * 创建单聊。
     *
     * 单聊与群聊<b>刻意分成两个端点</b>而不是一个带 {@code type} 字段的请求：
     * 两者的必填项完全不同（单聊要对端、群聊要标题），合成一个 record 后
     * Bean Validation 表达不了"type=group 时 title 必填"，只能退化成服务端
     * 手写条件校验——那意味着约束不再出现在契约里，客户端也就无法据此生成校验。
     *
     * 幂等由服务端保证：与同一个人重复创建返回已存在的那个会话，而不是报错。
     * 这个接口的语义是"打开与某人的对话"，不是"新建一条记录"。
     */
    public record CreateDirectConversation(
            @NotBlank String peerId
    ) {
    }

    /**
     * 创建群聊。
     *
     * {@code memberIds} 不含创建者——创建者自动成为 OWNER。把自己列进去
     * 也不会出错（会被去重），但契约上不要求。
     *
     * 上限 499 而不是 500：加上创建者刚好是
     * {@link com.openim.server.domain.service.ConversationService#MAX_GROUP_MEMBERS}。
     * 这里只挡明显超限的请求，真正的上限校验在服务端——客户端可以分多次加人绕过
     * 单次请求的限制。
     */
    public record CreateGroupConversation(
            @NotBlank @Size(min = 1, max = 64) String title,

            // 可选。会话头像是一个短字符标签（数据库列为 varchar(16)），
            // 不传时服务端取标题首字符
            @Size(max = 16) String avatar,

            @NotEmpty @Size(max = 499) List<@NotBlank String> memberIds
    ) {
    }

    /**
     * 批量加人。
     *
     * 单次上限 50：这个接口会为每个成员做一次存在性校验和一次写入，
     * 且要在同一个事务里完成。放开到几百个会让单个事务长时间持有会话行锁，
     * 期间该会话的消息发送全部阻塞在 {@code allocateNextSeq} 上。
     */
    public record AddConversationMembers(
            @NotEmpty @Size(max = 50) List<@NotBlank String> userIds
    ) {
    }

    /** 指派或撤销管理员。取值为 MemberRole 的名字，OWNER 不接受——转让走独立端点 */
    public record UpdateMemberRole(
            @NotBlank @Size(max = 16) String role
    ) {
    }

    /**
     * 转让群主。
     *
     * 独立于改角色的端点：两者的权限规则和副作用完全不同。转让会同时降级
     * 原群主并改写 {@code conversations.owner_id}，把它塞进"改角色"里意味着
     * 一个端点在 role 取值不同时走两条差别极大的分支——那正是权限漏洞的
     * 典型藏身处。
     */
    public record TransferOwnership(
            @NotBlank String userId
    ) {
    }

    /** 修改群资料。目前只有标题；头像与公告后续扩展时加字段 */
    public record UpdateConversation(
            @NotBlank @Size(min = 1, max = 64) String title
    ) {
    }

    /**
     * 上报已读位置。
     *
     * 未读数由 {@code conversations.next_seq - 1 - conversation_members.read_seq}
     * 推导，不单独维护计数字段，所以推进这个游标是<b>唯一</b>能让未读数下降的途径。
     * {@code advanceReadSeq} 早就实现了，但在补上这个端点之前没有任何调用方——
     * 于是未读数只增不减，用户看完消息后角标依然挂在那里。
     *
     * 游标只能前进不能后退（服务端取 {@code GREATEST}），因此乱序到达的上报是安全的。
     */
    public record MarkRead(
            @Min(0) long seq
    ) {
    }

    /**
     * 创建红包。
     *
     * {@code mode} 决定金额分配方式（等额或拼手气）；{@code idempotencyKey}
     * 是幂等键，重复点击或网络重试不会重复扣款。
     */
    public record CreateRedPacket(
            @NotBlank String conversationId,
            @NotBlank String mode,
            @Min(1) @Max(20_000) long totalAmount,
            @Min(1) @Max(100) int totalCount,
            @NotBlank @Size(max = 60) String greeting,
            @NotBlank @Size(min = 8, max = 128) String clientMessageId,
            @NotBlank @Size(min = 8, max = 128) String idempotencyKey
    ) {
    }

    /** 领取红包。{@code idempotencyKey} 防止重复点击或网络重试导致重复领取 */
    public record ClaimRedPacket(
            @NotBlank @Size(min = 8, max = 128) String idempotencyKey
    ) {
    }

    /**
     * 发起通话。
     *
     * {@code inviteeIds} 必须是会话成员；{@code idempotencyKey} 保证重试不会
     * 创建第二个通话房间。
     */
    public record CreateCall(
            @NotBlank String conversationId,
            @NotBlank String mediaType,
            @NotEmpty @Size(max = 5) List<String> inviteeIds,
            @NotBlank @Size(min = 8, max = 128) String idempotencyKey
    ) {
    }

    /** WebRTC 信令：把 SDP 或 ICE candidate 定向转发给同房间的目标用户 */
    public record RtcSignal(
            @NotBlank String roomId,
            @NotBlank String targetUserId,
            JsonNode sdp,
            JsonNode candidate
    ) {
    }
}
