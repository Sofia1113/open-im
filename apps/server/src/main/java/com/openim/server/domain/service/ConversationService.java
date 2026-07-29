package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.repository.ConversationRepository;
import com.openim.server.domain.repository.ConversationRepository.ConversationMeta;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 会话查询与成员关系校验
 *
 * 所有涉及会话资源的操作都必须先经过 {@link #requireMembership}，
 * 权限判断以服务端成员关系为准，不信任客户端传入的任何角色信息。
 */
@Service
public class ConversationService {
    private final ConversationRepository conversationRepository;

    public ConversationService(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @Transactional(readOnly = true)
    public List<Conversation> listConversations(UUID userId) {
        return conversationRepository.listForUser(userId);
    }

    /**
     * 校验用户是会话的有效成员。
     *
     * 会话不存在与无权访问返回不同状态码，与迁移前的行为保持一致：
     * 前端依赖 NOT_A_MEMBER 区分"退群了"和"会话被删了"两种提示。
     */
    @Transactional(readOnly = true)
    public void requireMembership(UUID conversationId, UUID userId) {
        if (!conversationRepository.exists(conversationId)) {
            throw DomainErrors.notFound("会话不存在");
        }
        if (!conversationRepository.isActiveMember(conversationId, userId)) {
            throw DomainErrors.forbidden("NOT_A_MEMBER", "你不是该会话成员");
        }
    }

    @Transactional(readOnly = true)
    public ConversationMeta requireMeta(UUID conversationId, UUID userId) {
        requireMembership(conversationId, userId);
        return conversationRepository.findMeta(conversationId)
                .orElseThrow(() -> DomainErrors.notFound("会话不存在"));
    }

    @Transactional(readOnly = true)
    public List<UUID> listActiveMemberIds(UUID conversationId) {
        return conversationRepository.listActiveMemberIds(conversationId);
    }

    /** 推进已读游标。未读数由游标推导，因此这是唯一需要写入的已读状态 */
    @Transactional
    public void markRead(UUID conversationId, UUID userId, long readSeq) {
        requireMembership(conversationId, userId);
        conversationRepository.advanceReadSeq(conversationId, userId, Math.max(0, readSeq));
    }
}
