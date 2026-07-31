package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainResults.MessagePage;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.domain.repository.ConversationRepository;
import com.openim.server.domain.repository.MessageRepository;
import com.openim.server.domain.repository.OutboxRepository;
import com.openim.server.observability.DomainMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final DomainMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public MessageService(
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            ConversationService conversationService,
            OutboxRepository outboxRepository,
            DomainMetrics metrics,
            PlatformTransactionManager transactionManager
    ) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.conversationService = conversationService;
        this.outboxRepository = outboxRepository;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 会话历史，向<b>后</b>拉取（断线补拉）。
     *
     * 语义是"我从 afterSeq 之后漏了什么"。打开会话不该用它——见
     * {@link #listLatestMessages}。
     *
     * <b>下界取 max(afterSeq, join_seq)</b>：成员看不到入群之前的消息。没有这个
     * 裁剪时，"把某人加进群"等同于"把这个群的全部历史交给他"——而加人只需要
     * 管理员权限，被泄露方（群里其他人）没有任何感知。客户端传 {@code afterSeq=0}
     * 就能拿到全部历史，所以裁剪必须在服务端做。
     *
     * {@code requireJoinSeq} 同时完成鉴权（不是成员时抛 403/404），
     * 因此这里不再单独调用 {@code requireMembership}——那会是同一张表上的第二次查询。
     */
    @Transactional(readOnly = true)
    public List<ImMessage> listMessages(UUID conversationId, UUID userId, long afterSeq) {
        long joinSeq = conversationService.requireJoinSeq(conversationId, userId);
        long lowerBound = Math.max(Math.max(0, afterSeq), joinSeq);
        return messageRepository.listAfterSeq(conversationId, lowerBound, PAGE_SIZE);
    }

    /**
     * 会话<b>最新</b>的一页消息，用于打开会话。
     *
     * 此前打开会话是从 {@code afterSeq=0} 开始一页页往后翻到底，也就是把整个会话的
     * 全部历史拉进内存：五万条消息的会话点开就是 500 次串行请求。演示数据里每个
     * 会话两条消息，这个问题永远不会显现。
     */
    @Transactional(readOnly = true)
    public MessagePage listLatestMessages(UUID conversationId, UUID userId, int limit) {
        long joinSeq = conversationService.requireJoinSeq(conversationId, userId);
        int pageSize = clampLimit(limit);
        // 多取一条用于判断"还有更早的"，返回时去掉。判断"有没有下一页"的
        // 另一种做法是再发一次 COUNT，但那要扫描整个可见范围。
        List<ImMessage> probe = messageRepository.listLatest(
                conversationId, joinSeq, pageSize + 1);
        return trimOldest(probe, pageSize);
    }

    /**
     * 比 {@code beforeSeq} 更早的一页消息，用于向上翻阅历史。
     *
     * 这是本轮补上的能力。此前只有 {@code listAfterSeq}——那是断线补拉的语义，
     * 没有任何接口能向前翻，而"往上滚动加载更早消息"是 IM 的基础交互。
     *
     * 可见下界仍是 {@code join_seq}：翻到入群那一刻就停住，{@code hasMore} 为 false。
     */
    @Transactional(readOnly = true)
    public MessagePage listMessagesBefore(
            UUID conversationId,
            UUID userId,
            long beforeSeq,
            int limit
    ) {
        long joinSeq = conversationService.requireJoinSeq(conversationId, userId);
        if (beforeSeq <= 0) {
            throw DomainErrors.validation("beforeSeq 必须为正整数");
        }
        int pageSize = clampLimit(limit);
        List<ImMessage> probe = messageRepository.listBeforeSeq(
                conversationId, beforeSeq, joinSeq, pageSize + 1);
        return trimOldest(probe, pageSize);
    }

    /**
     * 从升序结果中去掉多取的那条。
     *
     * 多取的一条在<b>头部</b>——两个查询都是 {@code ORDER BY seq DESC} 取 limit+1
     * 条再反转，所以多出来的是最早的那条。从尾部截断会丢掉最新的消息，
     * 而那种错误在只有一页数据时完全看不出来。
     */
    private MessagePage trimOldest(List<ImMessage> ascending, int pageSize) {
        if (ascending.size() <= pageSize) {
            return MessagePage.of(ascending, false);
        }
        return MessagePage.of(ascending.subList(ascending.size() - pageSize, ascending.size()), true);
    }

    /** 页大小由客户端建议、服务端裁剪。上限保护数据库，下限避免 limit=0 空转 */
    private int clampLimit(int limit) {
        if (limit <= 0) {
            return PAGE_SIZE;
        }
        return Math.min(limit, PAGE_SIZE);
    }

    /**
     * 写入文本消息。
     *
     * 幂等策略：先按 (senderId, clientMessageId) 查已有消息，未命中再写入。
     * 并发重试可能让两个事务同时通过预检查，此时由唯一约束拒绝其中一个，
     * 捕获后回查返回首次写入的结果——预检查是为了省一次写冲突，约束才是正确性来源。
     *
     * <h3>为什么这个方法不带 {@code @Transactional}</h3>
     *
     * 回查<b>必须在写入事务之外</b>执行。PostgreSQL 的唯一约束冲突是语句级错误，
     * 它会把整个事务置为 aborted，此后同一事务内的任何语句都返回
     * {@code 25P02 current transaction is aborted, commands ignored until end of
     * transaction block}。原先的写法（整个方法一个事务 + 捕获
     * {@code DuplicateKeyException} 后回查）因此在并发重发下必然失败——
     * 抛出的不再是可识别的重复键异常，而是一个 500，
     * 而消息其实<b>已经成功写入</b>了。客户端拿到 5xx 会继续重试。
     *
     * 由 {@code MessageIdempotencyConcurrencyTest} 实测确认。单线程重发看不出
     * 任何问题（预检查就命中了，走不到 catch 分支），所以这条路径此前一直"看起来"是对的。
     *
     * 把写入放进 {@link TransactionTemplate} 之后：冲突使那个事务回滚并结束，
     * 随后的回查在一个干净的新事务里执行。<b>顺带解决了 seq 空洞</b>——
     * {@code allocateNextSeq} 的自增随事务一起回滚，失败的那次尝试不会占用序号。
     * 若改用 {@code ON CONFLICT DO NOTHING} 让写入本身幂等，序号就已经被消耗掉了，
     * 而客户端按 seq 连续性判断是否漏收消息，空洞会让它永远等一条不存在的消息。
     *
     * 用 {@code TransactionTemplate} 而不是把 {@code writeMessage} 拆到另一个 bean：
     * 同类内部调用不经过 Spring 代理，那样标注的 {@code @Transactional} 是无效的。
     */
    public ImMessage createMessage(UUID senderId, SendMessage input) {
        UUID conversationId = DomainErrors.parseId(input.conversationId(), "会话");
        // 鉴权与校验都是只读的，放在写入事务之外不影响正确性：
        // 真正的并发保护来自唯一约束与会话行锁，不是这两次查询。
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
            return transactionTemplate.execute(status ->
                    writeMessage(conversationId, senderId, input.clientMessageId(), text));
        } catch (DuplicateKeyException duplicate) {
            // 事务已回滚并结束，这次查询在新事务里执行——这正是原先做不到的那一步
            return messageRepository
                    .findByClientMessageId(senderId, input.clientMessageId())
                    .orElseThrow(() -> duplicate);
        }
    }

    /** 在事务内完成消息写入：分配序号、落库、更新会话摘要并追加 outbox 事件。调用方负责幂等与异常兜底 */
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
        // conversationId 必须进载荷：Outbox Worker 靠它决定投递给哪个会话。
        // 此前只写 messageId 和 seq，Worker 取不到会话 ID 便静默跳过，
        // 补偿路径实际上从未生效。
        outboxRepository.append("conversation", conversationId, "message:created",
                Map.of(
                        "messageId", message.id(),
                        "conversationId", conversationId.toString(),
                        "seq", seq
                ));
        metrics.recordMessageSent();
        return message;
    }

    /** 从消息内容中提取文本并校验长度（1–4000 字符）。当前接口只接受文本消息 */
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
