package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.User;
import com.openim.server.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 用户查询与在线状态
 */
@Service
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAllActive();
    }

    @Transactional(readOnly = true)
    public User getUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> DomainErrors.notFound("用户不存在"));
    }

    @Transactional(readOnly = true)
    public User getUser(String userId) {
        return getUser(DomainErrors.parseId(userId, "用户"));
    }

    @Transactional(readOnly = true)
    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> DomainErrors.notFound("用户不存在"));
    }

    /** 鉴权链路使用的轻量校验，避免每个请求都做一次完整对象映射 */
    @Transactional(readOnly = true)
    public boolean isActiveUser(UUID userId) {
        return userRepository.existsActive(userId);
    }

    @Transactional
    public void setUserOnline(UUID userId, boolean online) {
        userRepository.updatePresence(userId, online);
    }

    /**
     * 启动时清理残留的在线状态。
     *
     * 进程非正常退出时 WebSocket 的关闭回调不会执行，presence 会永久停在 online。
     * 单实例部署下启动即可安全清零；迁移到多实例后这里必须改为只清理本实例的连接，
     * 否则会把其他实例上真实在线的用户标记为离线。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void resetPresenceOnStartup() {
        int reset = userRepository.resetAllPresence();
        if (reset > 0) {
            log.info("Reset stale presence for {} users on startup", reset);
        }
    }
}
