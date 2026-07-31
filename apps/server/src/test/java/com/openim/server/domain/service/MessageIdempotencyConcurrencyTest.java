package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息幂等在并发重发下是否成立
 *
 * 客户端重试发送时依赖 {@code (sender_id, client_message_id)} 唯一约束去重。
 * 单线程重发有测试覆盖，但真正会出问题的是<b>并发</b>重发：弱网下客户端超时重试，
 * 而第一个请求其实还在处理中，两个事务同时通过"先查有没有"的预检查。
 *
 * 此时约束会拒绝其中一个，实现依赖 {@code catch (DuplicateKeyException)} 之后
 * 回查首次写入的结果。<b>这个写法在 PostgreSQL 上需要验证</b>：唯一约束冲突是
 * 语句级错误，它会把整个事务置为 aborted，此后同一事务内的任何语句都返回
 * {@code 25P02 current transaction is aborted}——那次回查自己就会失败。
 *
 * （补会话创建时刻意避开了这个模式，改用 {@code ON CONFLICT DO NOTHING} + 回查，
 * 见 {@code ConversationRepository.tryInsertDirectKey}。这个测试确认消息路径
 * 是否也需要同样的处理。）
 */
@SpringBootTest
class MessageIdempotencyConcurrencyTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * 同一个 clientMessageId 并发发两次：两次都应返回同一条消息，库里只有一条。
     *
     * 不允许其中一次抛异常：客户端拿到 5xx 会继续重试，而消息其实已经发出去了。
     */
    @Test
    void concurrentResendWithTheSameClientMessageIdYieldsExactlyOneMessage() throws Exception {
        UUID sender = createUser("idem-sender");
        UUID peer = createUser("idem-peer");
        Conversation conversation =
                conversationService.createDirectConversation(sender, peer.toString());
        UUID conversationId = UUID.fromString(conversation.id());

        String clientMessageId = "client-" + UUID.randomUUID();
        SendMessage input = new SendMessage(
                conversation.id(), clientMessageId, "text", Map.of("text", "并发重发"));

        List<Attempt> attempts = runConcurrently(List.of(
                () -> attemptSend(sender, input),
                () -> attemptSend(sender, input)
        ));

        List<Attempt> failures = attempts.stream().filter(attempt -> !attempt.success()).toList();
        assertThat(failures)
                .as("并发重发不应有任何一次失败——消息已经存在，正确行为是返回它。"
                        + "失败信息：%s", failures.stream().map(Attempt::error).toList())
                .isEmpty();

        assertThat(attempts.stream().map(Attempt::messageId).distinct())
                .as("两次调用必须返回同一条消息")
                .hasSize(1);

        assertThat(countMessages(conversationId, clientMessageId))
                .as("库里只能有一条")
                .isEqualTo(1);

        // seq 不能出现空洞：客户端按 seq 连续性判断是否漏收消息，
        // 被回滚的那次写入若已经消耗掉一个序号，客户端会永远等一条不存在的消息
        assertThat(maxSeq(conversationId))
                .as("失败的那次写入不应留下 seq 空洞")
                .isEqualTo(1);
    }

    private record Attempt(boolean success, String messageId, String error) {
    }

    private Attempt attemptSend(UUID sender, SendMessage input) {
        try {
            ImMessage message = messageService.createMessage(sender, input);
            return new Attempt(true, message.id(), null);
        } catch (RuntimeException exception) {
            return new Attempt(false, null,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private int countMessages(UUID conversationId, String clientMessageId) {
        return jdbcClient.sql("""
                        SELECT count(*) FROM messages
                         WHERE conversation_id = :conversationId
                           AND client_message_id = :clientMessageId
                        """)
                .param("conversationId", conversationId)
                .param("clientMessageId", clientMessageId)
                .query(Integer.class)
                .single();
    }

    private long maxSeq(UUID conversationId) {
        return jdbcClient.sql("""
                        SELECT COALESCE(max(seq), 0) FROM messages
                         WHERE conversation_id = :conversationId
                        """)
                .param("conversationId", conversationId)
                .query(Long.class)
                .single();
    }

    private UUID createUser(String prefix) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO users (id, username, display_name, avatar, status)
                        VALUES (:id, :username, :displayName, '幂', 'ACTIVE')
                        """)
                .param("id", id)
                .param("username", prefix + "-" + id.toString().substring(0, 8))
                .param("displayName", prefix)
                .update();
        return id;
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = tasks.stream()
                    .map(task -> pool.submit(() -> {
                        start.await();
                        return task.call();
                    }))
                    .toList();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
