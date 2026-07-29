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

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        List<User> users = userRepository.findAllActive();
        if (users.isEmpty()) {
            return users;
        }
        Map<UUID, Boolean> online = presenceService.batchIsOnline(
                users.stream().map(u -> UUID.fromString(u.id())).collect(Collectors.toList()));
        return users.stream()
                .map(u -> withPresence(u, Boolean.TRUE.equals(online.get(UUID.fromString(u.id())))))
                .toList();
    }

    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> DomainErrors.notFound("用户不存在"));
        return withPresence(user, presenceService.isOnline(userId));
    }

    @Transactional(readOnly = true)
    public User getUser(String userId) {
        return getUser(DomainErrors.parseId(userId, "用户"));
    }

    @Transactional(readOnly = true)
    public User getUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> DomainErrors.notFound("用户不存在"));
        return withPresence(user, presenceService.isOnline(UUID.fromString(user.id())));
    }

    /** 鉴权链路使用的轻量校验，避免每个请求做一次完整对象映射 */
    @Transactional(readOnly = true)
    public boolean isActiveUser(UUID userId) {
        return userRepository.existsActive(userId);
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
