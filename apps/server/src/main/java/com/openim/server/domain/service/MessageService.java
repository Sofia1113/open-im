package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.domain.repository.ConversationRepository;
import com.openim.server.domain.repository.MessageRepository;
import com.openim.server.domain.repository.OutboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 消息写入与历史查询
 */
@Service
public class MessageService {
    /** 单次拉取上限，与客户端补拉逻辑约定一致 */
    private static final int PAGE_SIZE = 100;
    private static final int MAX_TEXT_LENGTH = 4_000;

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationService conversationService;
    private final OutboxRepository outboxRepository;

    public MessageService(
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            ConversationService conversationService,
            OutboxRepository outboxRepository
    ) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public List<ImMessage> listMessages(UUID conversationId, UUID userId, long afterSeq) {
        conversationService.requireMembership(conversationId, userId);
        return messageRepository.listAfterSeq(conversationId, Math.max(0, afterSeq), PAGE_SIZE);
    }

    /**
     * 写入文本消息。
     *
     * 幂等策略：先按 (senderId, clientMessageId) 查已有消息，未命中再写入。
     * 并发重试可能让两个事务同时通过预检查，此时由唯一约束拒绝其中一个，
     * 捕获后回查返回首次写入的结果——预检查是为了省一次写冲突，约束才是正确性来源。
     */
    @Transactional
    public ImMessage createMessage(UUID senderId, SendMessage input) {
        UUID conversationId = DomainErrors.parseId(input.conversationId(), "会话");
        conversationService.requireMembership(conversationId, senderId);

        if (!"text".equals(input.type())) {
            throw DomainErrors.validation("当前接口只接受文本消息");
        }
        String text = extractText(input);

        ImMessage existing = messageRepository
                .findByClientMessageId(senderId, input.clientMessageId())
                .orElse(null);
        if (existing != null) {
            return existing;
        }

        try {
            return writeMessage(conversationId, senderId, input.clientMessageId(), text);
        } catch (DuplicateKeyException duplicate) {
            return messageRepository
                    .findByClientMessageId(senderId, input.clientMessageId())
                    .orElseThrow(() -> duplicate);
        }
    }

    private ImMessage writeMessage(
            UUID conversationId,
            UUID senderId,
            String clientMessageId,
            String text
    ) {
        long seq = conversationRepository.allocateNextSeq(conversationId);
        Instant sentAt = Instant.now();
        ImMessage message = messageRepository.insert(
                UUID.randomUUID(),
                conversationId,
                seq,
                senderId,
                clientMessageId,
                "text",
                Map.of("text", text),
                sentAt
        );
        conversationRepository.updateSummary(conversationId, text, sentAt);
        outboxRepository.append("conversation", conversationId, "message:created",
                Map.of("messageId", message.id(), "seq", seq));
        return message;
    }

    private String extractText(SendMessage input) {
        String text = input.content() == null
                ? null
                : Objects.toString(input.content().get("text"), null);
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            throw DomainErrors.validation("消息正文长度必须为 1–4000 字符");
        }
        return text.trim();
    }
}
