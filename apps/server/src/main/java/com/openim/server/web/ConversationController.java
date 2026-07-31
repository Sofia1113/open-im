package com.openim.server.web;

import com.openim.server.auth.AuthPrincipal;
import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainResults.MembershipChange;
import com.openim.server.domain.Requests.AddConversationMembers;
import com.openim.server.domain.Requests.CreateDirectConversation;
import com.openim.server.domain.Requests.CreateGroupConversation;
import com.openim.server.domain.Requests.MarkRead;
import com.openim.server.domain.Requests.TransferOwnership;
import com.openim.server.domain.Requests.UpdateConversation;
import com.openim.server.domain.Requests.UpdateMemberRole;
import com.openim.server.domain.repository.ConversationRepository.ConversationMember;
import com.openim.server.domain.service.ConversationService;
import com.openim.server.domain.service.DomainErrors;
import com.openim.server.realtime.RealtimeBroadcastService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话与成员管理
 *
 * <b>这个控制器补的是主流程里缺的一段</b>：在它之前，服务端没有任何创建会话的
 * 接口——会话全部来自演示种子，而种子在 {@code prod} profile 下不加载。
 * 于是生产环境注册出来的账号打开的是一个空会话列表，且无法与任何人建立对话，
 * 一条消息都发不出去。
 *
 * <b>会话的只读端点仍在 {@link GeneralController}</b>（会话列表、消息历史）。
 * 按职责它们应当归到这里，但那是一次纯重构：路径不变、行为不变，只是把方法
 * 搬个位置。本轮不做，以免把"补上缺失的功能"和"挪动既有代码"混在一个改动里。
 *
 * 每个成员变更都在 Service 的事务里完成，广播发生在事务提交之后（Service 方法
 * 返回即已提交）。顺序很重要：成员缓存的失效也挂在提交后，若在提交前广播，
 * 其他实例会回源读到旧的成员表并缓存下来。
 */
@Tag(name = "会话", description = "创建单聊与群聊、管理成员与角色")
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final RealtimeBroadcastService broadcast;

    public ConversationController(
            ConversationService conversationService,
            RealtimeBroadcastService broadcast
    ) {
        this.conversationService = conversationService;
        this.broadcast = broadcast;
    }

    /**
     * 单个会话视图。
     *
     * 成员变更事件只带 {@code conversationId}，客户端据此拉取这一个会话而不是
     * 重新拉整个列表。没有这个端点的话，一个 500 人群里加一个人会让 500 个客户端
     * 各自拉一次全量会话列表。
     */
    @GetMapping("/{id}")
    public ApiEnvelope.Success<Conversation> get(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        return ApiEnvelope.success(
                conversationService.getConversation(conversationId, auth.userUuid()), request);
    }

    /**
     * 创建或复用与某人的单聊。
     *
     * 幂等：与同一个人重复调用返回已存在的会话，而不是 409。这个接口的语义是
     * "打开与某人的对话"。
     */
    @PostMapping("/direct")
    public ApiEnvelope.Success<Conversation> createDirect(
            @Valid @RequestBody CreateDirectConversation input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        Conversation conversation =
                conversationService.createDirectConversation(auth.userUuid(), input.peerId());
        // 对端需要立刻看到这个会话出现在列表里。推给会话内全体成员（含创建者，
        // 他的其他设备也需要同步）。
        broadcast.broadcastToConversation(conversation.id(), "conversation:created",
                Map.of("conversationId", conversation.id()));
        return ApiEnvelope.success(conversation, request);
    }

    /** 创建群聊。需 Bearer 令牌；创建者成为群主，事件推送给会话内全体成员（含创建者的其他设备） */
    @PostMapping("/group")
    public ApiEnvelope.Success<Conversation> createGroup(
            @Valid @RequestBody CreateGroupConversation input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        Conversation conversation =
                conversationService.createGroupConversation(auth.userUuid(), input);
        broadcast.broadcastToConversation(conversation.id(), "conversation:created",
                Map.of("conversationId", conversation.id()));
        return ApiEnvelope.success(conversation, request);
    }

    /** 修改群资料。群主与管理员可改 */
    @PatchMapping("/{id}")
    public ApiEnvelope.Success<Conversation> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateConversation input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        Conversation conversation = conversationService.updateProfile(
                conversationId, auth.userUuid(), input.title());
        broadcast.broadcastToConversation(conversation.id(), "conversation:updated",
                Map.of("conversationId", conversation.id()));
        return ApiEnvelope.success(conversation, request);
    }

    /**
     * 上报已读位置。
     *
     * 未读数由序号差推导，因此这是唯一能让它下降的途径。{@code advanceReadSeq}
     * 早就实现了，但在补上这个端点之前<b>没有任何调用方</b>——未读角标只增不减。
     *
     * 返回更新后的会话视图，客户端据此直接替换本地那一项，不必再拉一次。
     */
    @PostMapping("/{id}/read")
    public ApiEnvelope.Success<Conversation> markRead(
            @PathVariable String id,
            @Valid @RequestBody MarkRead input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        conversationService.markRead(conversationId, auth.userUuid(), input.seq());
        return ApiEnvelope.success(
                conversationService.getConversation(conversationId, auth.userUuid()), request);
    }

    /** 会话成员列表。需 Bearer 令牌，仅会话成员可见 */
    @GetMapping("/{id}/members")
    public ApiEnvelope.Success<List<ConversationMember>> members(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        return ApiEnvelope.success(
                conversationService.listMembers(conversationId, auth.userUuid()), request);
    }

    /** 批量加人。已在群内的用户被静默跳过，返回值里只有真正加入的 */
    @PostMapping("/{id}/members")
    public ApiEnvelope.Success<MembershipChange> addMembers(
            @PathVariable String id,
            @Valid @RequestBody AddConversationMembers input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        MembershipChange change =
                conversationService.addMembers(conversationId, auth.userUuid(), input.userIds());
        publishMembershipChange(change);
        return ApiEnvelope.success(change, request);
    }

    /**
     * 移出成员。
     *
     * 路径参数是被移出者的 ID。移出自己会被拒绝并提示改用退群接口——
     * 两者的规则不同（群主不能退群，但可以移出别人）。
     */
    @DeleteMapping("/{id}/members/{userId}")
    public ApiEnvelope.Success<MembershipChange> removeMember(
            @PathVariable String id,
            @PathVariable String userId,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        MembershipChange change =
                conversationService.removeMember(conversationId, auth.userUuid(), userId);
        publishMembershipChange(change);
        return ApiEnvelope.success(change, request);
    }

    /**
     * 主动退群。
     *
     * 路径用 {@code /membership} 而不是 {@code /members/me}：后者要靠"精确路径
     * 优先于路径变量"这条匹配规则才不会落进 {@code /members/{userId}}，
     * 而那条规则一旦因为框架升级或路径改写发生变化，"me" 就会被当成一个用户 ID
     * 去解析——静默地变成 404，或者更糟，匹配到某个真实用户。
     */
    @DeleteMapping("/{id}/membership")
    public ApiEnvelope.Success<MembershipChange> leave(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        MembershipChange change = conversationService.leave(conversationId, auth.userUuid());
        publishMembershipChange(change);
        return ApiEnvelope.success(change, request);
    }

    /** 指派或撤销管理员。仅群主，且不能改自己 */
    @PatchMapping("/{id}/members/{userId}")
    public ApiEnvelope.Success<List<ConversationMember>> updateMemberRole(
            @PathVariable String id,
            @PathVariable String userId,
            @Valid @RequestBody UpdateMemberRole input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        conversationService.updateMemberRole(
                conversationId, auth.userUuid(), userId, input.role());
        broadcast.broadcastToConversation(id, "conversation:updated",
                Map.of("conversationId", id));
        return ApiEnvelope.success(
                conversationService.listMembers(conversationId, auth.userUuid()), request);
    }

    /** 转让群主。原群主降为管理员 */
    @PostMapping("/{id}/transfer-owner")
    public ApiEnvelope.Success<List<ConversationMember>> transferOwner(
            @PathVariable String id,
            @Valid @RequestBody TransferOwnership input,
            HttpServletRequest request
    ) {
        AuthPrincipal auth = AuthPrincipal.from(request);
        UUID conversationId = DomainErrors.parseId(id, "会话");
        conversationService.transferOwnership(conversationId, auth.userUuid(), input.userId());
        broadcast.broadcastToConversation(id, "conversation:updated",
                Map.of("conversationId", id));
        return ApiEnvelope.success(
                conversationService.listMembers(conversationId, auth.userUuid()), request);
    }

    /**
     * 广播成员变更。
     *
     * 两条推送，缺一不可：
     * <ul>
     *   <li>给<b>变更后</b>的成员发 {@code conversation:members-changed}——
     *       新加入的人在这个列表里，因此扇出天然覆盖他们</li>
     *   <li>给<b>被移出</b>的人单独发 {@code conversation:removed}——他们已经不在
     *       成员列表里，扇出到不了。少了这条，被踢的人的客户端会继续显示这个会话，
     *       直到下次操作全部返回 403 才发现</li>
     * </ul>
     *
     * 没有任何实际变更时（比如加的人全都已在群内）不发事件：Service 返回的
     * joined/removed 都是空列表，两个分支都不会执行。
     */
    private void publishMembershipChange(MembershipChange change) {
        if (change.joined().isEmpty() && change.removed().isEmpty()) {
            return;
        }
        broadcast.broadcastToConversation(change.conversationId(),
                "conversation:members-changed",
                Map.of("conversationId", change.conversationId(),
                        "joined", change.joined(),
                        "removed", change.removed()));
        for (String removedUserId : change.removed()) {
            broadcast.broadcastToUser(removedUserId, "conversation:removed",
                    Map.of("conversationId", change.conversationId()));
        }
    }
}
