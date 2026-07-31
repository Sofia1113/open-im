package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.repository.UserRepository;
import com.openim.server.realtime.PresenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 用户查询与在线状态
 *
 * 在线状态（presence）以 Redis 为真实来源：
 * <ul>
 *   <li>连接建立 / 心跳 pong → Redis key 带 TTL，自动续期</li>
 *   <li>连接断开 → 立即删除 Redis key，同时更新 DB 的 last_seen_at</li>
 *   <li>实例崩溃 → Redis key 自动过期，无需清理任务</li>
 * </ul>
 *
 * 删除了之前的 {@code resetPresenceOnStartup()} 启动清理：那个实现假设
 * 全局只有一个实例（清空所有用户的 online 状态），在多实例部署下会把
 * 其他实例上真实在线的用户标记为离线。
 */
@Service
public class UserService {
    private final UserRepository userRepository;
    private final PresenceService presenceService;

    public UserService(UserRepository userRepository, PresenceService presenceService) {
        this.userRepository = userRepository;
        this.presenceService = presenceService;
    }

    /**
     * 单次返回的联系人上限。
     *
     * 这不是分页——联系人列表是"与我有会话往来的人"，它的规模由我参与的会话数
     * 决定，而群成员上限是 500，所以正常账号远低于这个值。上限的作用是
     * <b>防止一个异常账号（加入了几百个大群）拖垮这个接口</b>，
     * 而不是提供翻页能力。真需要翻页时应当引入游标，而不是把这个值调大。
     */
    private static final int CONTACT_LIMIT = 500;

    /**
     * 批量取用户资料的单次上限。
     *
     * 客户端按需请求"这些 ID 是谁"，一次请求携带的 ID 数应当与一屏能显示的
     * 人数同级。上限防止把它当成变相的全量导出接口。
     */
    private static final int LOOKUP_LIMIT = 200;

    /**
     * 我的联系人：与我至少共处过一个会话的人。
     *
     * <b>此前这个方法返回全站所有 ACTIVE 用户</b>（无 LIMIT 的全表扫描），
     * 而它是客户端加载时就会调用的接口。任何登录用户可以枚举全部注册者的用户名与
     * 在线状态，且响应体随注册量线性增长。
     *
     * 现在的可见集是<b>可推导的</b>：这些人的昵称在会话成员列表里本来就对我可见，
     * 所以收窄之后这个接口不再暴露任何"我本来看不到"的信息。
     */
    @Transactional(readOnly = true)
    public List<User> listContacts(UUID userId) {
        return withPresence(userRepository.findContacts(userId, CONTACT_LIMIT));
    }

    /**
     * 按完整用户名查找一个人，用于主动发起对话。
     *
     * 联系人列表收窄后必须有这条路径，否则两个从未有过往来的人永远无法建立
     * 第一次对话——那会把"没有全站列表"从隐私改进变成功能缺失。
     *
     * 不做模糊匹配：前缀搜索允许用几百次请求枚举出大部分用户名，
     * 等于把全站列表还回去了。找不到时返回空列表而不是 404——
     * 404 与"存在但不可见"在语义上无法区分，而空结果对调用方的处理方式相同。
     */
    @Transactional(readOnly = true)
    public List<User> searchUsers(String username, UUID requesterId) {
        return userRepository.searchByUsername(username)
                .filter(user -> !user.id().equals(requesterId.toString()))
                .map(user -> List.of(
                        withPresence(user, presenceService.isOnline(UUID.fromString(user.id())))))
                .orElseGet(List::of);
    }

    /**
     * 按 ID 批量取用户资料，用于渲染已知的人。
     *
     * 收窄联系人列表之后，历史消息里已退群成员的昵称会取不到——他们不在我的
     * 联系人里，而消息还在。客户端用这个接口按需补齐。
     *
     * 不做可见性校验：这些 ID 是服务端通过消息、红包、通话等鉴权过的接口
     * 交给客户端的，再校验一次等于要求客户端证明它是怎么拿到这些 ID 的。
     * 超出上限的部分被截断而不是报错——调用方拿到的是它请求的一个子集，
     * 这与"某个 ID 不存在"的处理路径相同。
     */
    @Transactional(readOnly = true)
    public List<User> lookupUsers(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<UUID> bounded = ids.size() > LOOKUP_LIMIT ? ids.subList(0, LOOKUP_LIMIT) : ids;
        return withPresence(userRepository.findByIds(bounded));
    }

    /** 批量填充在线状态。一次 pipeline 而不是逐个查 Redis */
    private List<User> withPresence(List<User> users) {
        if (users.isEmpty()) {
            return users;
        }
        Map<UUID, Boolean> online = presenceService.batchIsOnline(
                users.stream().map(u -> UUID.fromString(u.id())).collect(Collectors.toList()));
        return users.stream()
                .map(u -> withPresence(u, Boolean.TRUE.equals(online.get(UUID.fromString(u.id())))))
                .toList();
    }

    /** 按 ID 查单个用户（含在线状态）。用户不存在时抛 404 */
    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> DomainErrors.notFound("用户不存在"));
        return withPresence(user, presenceService.isOnline(userId));
    }

    /** 按 ID 字符串查单个用户。格式非法与不存在都按 404 处理（见 {@link DomainErrors#parseId}） */
    @Transactional(readOnly = true)
    public User getUser(String userId) {
        return getUser(DomainErrors.parseId(userId, "用户"));
    }

    /** 按用户名查单个用户（含在线状态）。用户名是唯一键，不存在时抛 404 */
    @Transactional(readOnly = true)
    public User getUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> DomainErrors.notFound("用户不存在"));
        return withPresence(user, presenceService.isOnline(UUID.fromString(user.id())));
    }

    /**
     * 设置用户在线/离线。
     * <ul>
     *   <li>上线：在 Redis 写入带 TTL 的 presence key</li>
     *   <li>下线：删除 Redis key 并将 last_seen_at 写回数据库</li>
     * </ul>
     */
    @Transactional
    public void setUserOnline(UUID userId, boolean online) {
        if (online) {
            presenceService.markOnline(userId);
        } else {
            presenceService.markOffline(userId);
            // 离线时才更新数据库，减少写入频率
            userRepository.updateLastSeenAt(userId, Instant.now());
        }
    }

    /** 心跳刷新 presence TTL，保持用户在线状态 */
    public void refreshPresence(UUID userId) {
        presenceService.markOnline(userId);
    }

    /** 用 Redis presence 覆盖 DB 里可能已过时的 status 字段 */
    private static User withPresence(User user, boolean online) {
        return new User(
                user.id(),
                user.username(),
                user.displayName(),
                user.avatar(),
                online ? "online" : "offline",
                online ? null : user.lastSeenAt()
        );
    }
}
