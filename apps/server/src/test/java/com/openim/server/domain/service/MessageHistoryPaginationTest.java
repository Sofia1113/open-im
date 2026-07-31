package com.openim.server.domain.service;

import com.openim.server.domain.DomainModels.Conversation;
import com.openim.server.domain.DomainModels.ImMessage;
import com.openim.server.domain.DomainResults.MessagePage;
import com.openim.server.domain.Requests.CreateGroupConversation;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消息历史分页
 *
 * <b>本轮补的是"向前翻"这个方向。</b>此前只有 {@code listAfterSeq}——
 * {@code WHERE seq > :afterSeq ORDER BY seq LIMIT 100}，那是<b>断线补拉</b>的语义
 * （"我离线期间漏了什么"），不是历史回溯。没有任何接口能取比某条消息更早的消息，
 * 而"往上滚动加载更早的消息"是 IM 的基础交互。
 *
 * 顺带修的是打开会话的代价：此前客户端从 {@code afterSeq=0} 一页页翻到底，
 * 也就是把<b>整个会话的全部历史</b>拉进内存。五万条消息的会话点开就是 500 次
 * 串行请求。演示数据里每个会话两条消息，这个问题永远不会显现。
 */
@SpringBootTest
class MessageHistoryPaginationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MessageService messageService;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * 打开会话取到的是<b>最新</b>的一页，而不是最早的一页。
     *
     * 这条断言区分了"取最新一页"与"从头开始取一页"。后者在只有一页数据时
     * 完全等价，因此必须造出多于一页的消息才测得到。
     */
    @Test
    void openingAConversationReturnsTheNewestPageNotTheOldest() {
        Fixture fixture = conversationWithMessages(25);

        MessagePage page = messageService.listLatestMessages(fixture.id(), fixture.owner(), 10);

        assertThat(page.messages()).hasSize(10);
        assertThat(seqs(page.messages()))
                .as("应当是最新的 10 条（16..25），而不是最早的 10 条")
                .containsExactly(16L, 17L, 18L, 19L, 20L, 21L, 22L, 23L, 24L, 25L);
        assertThat(page.hasMore()).isTrue();
    }

    /** 返回顺序必须是升序，调用方可直接渲染 */
    @Test
    void pagesAreReturnedInAscendingOrder() {
        Fixture fixture = conversationWithMessages(5);

        assertThat(seqs(messageService.listLatestMessages(fixture.id(), fixture.owner(), 10).messages()))
                .isSorted();
        assertThat(seqs(messageService.listMessagesBefore(
                fixture.id(), fixture.owner(), 5, 10).messages()))
                .isSorted();
    }

    /**
     * 向前翻页能一路走到会话开头，且不重不漏。
     *
     * 这是分页最容易出错的地方：边界用 {@code <} 还是 {@code <=} 决定了每页之间
     * 是重一条还是漏一条，而单页测试看不出来。
     */
    @Test
    void pagingBackwardsCoversEveryMessageExactlyOnce() {
        Fixture fixture = conversationWithMessages(23);

        List<Long> collected = new ArrayList<>();
        MessagePage page = messageService.listLatestMessages(fixture.id(), fixture.owner(), 5);
        collected.addAll(0, seqs(page.messages()));

        int guard = 0;
        while (page.hasMore()) {
            if (++guard > 20) {
                throw new AssertionError("翻页没有收敛，可能是游标没有前进");
            }
            long oldest = page.messages().get(0).seq();
            page = messageService.listMessagesBefore(fixture.id(), fixture.owner(), oldest, 5);
            collected.addAll(0, seqs(page.messages()));
        }

        assertThat(collected)
                .as("翻到底应恰好覆盖 1..23，无重复无遗漏")
                .containsExactlyElementsOf(rangeClosed(1, 23));
    }

    /**
     * {@code hasMore} 在最后一页恰好装满时必须是 false。
     *
     * 让客户端按"返回条数 < 页大小"推断的话，这里会推出"还有更多"，
     * 于是多发一次请求拿到空数组——用户看到加载指示器闪一下却没有新内容。
     */
    @Test
    void hasMoreIsFalseWhenTheLastPageIsExactlyFull() {
        Fixture fixture = conversationWithMessages(10);

        MessagePage page = messageService.listLatestMessages(fixture.id(), fixture.owner(), 10);

        assertThat(page.messages()).hasSize(10);
        assertThat(page.hasMore())
                .as("恰好取完全部消息时不该说还有更多")
                .isFalse();
    }

    @Test
    void anEmptyConversationReturnsAnEmptyPage() {
        Fixture fixture = conversationWithMessages(0);

        MessagePage page = messageService.listLatestMessages(fixture.id(), fixture.owner(), 10);

        assertThat(page.messages()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    /**
     * 向前翻页同样受 {@code join_seq} 约束——翻到入群那一刻就停住。
     *
     * 这是本轮新增端点最需要验证的一格：历史裁剪此前只加在 {@code listAfterSeq}
     * 上，新增一个取数方向就是新增一条可能绕过它的路径。
     */
    @Test
    void pagingBackwardsStopsAtTheJoinBoundary() {
        Fixture fixture = conversationWithMessages(10);
        UUID latecomer = createUser("history-latecomer");
        conversationService.addMembers(fixture.id(), fixture.owner(), List.of(latecomer.toString()));
        // 入群后又来了 3 条
        for (int index = 0; index < 3; index++) {
            send(fixture.id(), fixture.owner(), "入群后 " + index);
        }

        MessagePage latest = messageService.listLatestMessages(fixture.id(), latecomer, 10);
        assertThat(seqs(latest.messages()))
                .as("只看得到入群后的 3 条")
                .containsExactly(11L, 12L, 13L);
        assertThat(latest.hasMore())
                .as("入群前的消息不算'还有更多'——它们对这个成员根本不存在")
                .isFalse();

        // 即使显式请求更早的消息，也拿不到入群前的内容
        MessagePage earlier = messageService.listMessagesBefore(fixture.id(), latecomer, 11, 10);
        assertThat(earlier.messages()).isEmpty();
        assertThat(earlier.hasMore()).isFalse();

        // 原有成员不受影响
        assertThat(messageService.listLatestMessages(fixture.id(), fixture.owner(), 100).messages())
                .hasSize(13);
    }

    /** 页大小由服务端裁剪：客户端不能通过 limit 把整个会话一次拉走 */
    @Test
    void theRequestedLimitIsClampedByTheServer() {
        Fixture fixture = conversationWithMessages(150);

        assertThat(messageService.listLatestMessages(fixture.id(), fixture.owner(), 10_000).messages())
                .as("超出上限的请求被裁剪到 100")
                .hasSize(100);
        assertThat(messageService.listLatestMessages(fixture.id(), fixture.owner(), 0).messages())
                .as("limit=0 取默认页大小，而不是空转返回 0 条")
                .hasSize(100);
        assertThat(messageService.listLatestMessages(fixture.id(), fixture.owner(), -5).messages())
                .hasSize(100);
    }

    @Test
    void beforeSeqMustBePositive() {
        Fixture fixture = conversationWithMessages(3);

        assertThatThrownBy(() ->
                messageService.listMessagesBefore(fixture.id(), fixture.owner(), 0, 10))
                .hasMessageContaining("beforeSeq");
    }

    /** 两个新端点同样需要鉴权，不能成为绕过成员校验的旁路 */
    @Test
    void bothEndpointsRequireMembership() {
        Fixture fixture = conversationWithMessages(3);
        UUID outsider = createUser("history-outsider");

        assertThatThrownBy(() -> messageService.listLatestMessages(fixture.id(), outsider, 10))
                .hasMessageContaining("不是该会话成员");
        assertThatThrownBy(() -> messageService.listMessagesBefore(fixture.id(), outsider, 3, 10))
                .hasMessageContaining("不是该会话成员");
    }

    // ── 工具 ────────────────────────────────────────────────────────

    private record Fixture(UUID id, UUID owner, UUID member) {
    }

    private Fixture conversationWithMessages(int count) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID owner = createUser("hist-own-" + suffix);
        UUID member = createUser("hist-mem-" + suffix);
        Conversation group = conversationService.createGroupConversation(owner,
                new CreateGroupConversation("分页测试群", "页", List.of(member.toString())));
        UUID id = UUID.fromString(group.id());
        for (int index = 1; index <= count; index++) {
            send(id, owner, "第 " + index + " 条");
        }
        return new Fixture(id, owner, member);
    }

    private void send(UUID conversationId, UUID senderId, String text) {
        messageService.createMessage(senderId, new SendMessage(
                conversationId.toString(), "client-" + UUID.randomUUID(), "text",
                Map.of("text", text)));
    }

    private List<Long> seqs(List<ImMessage> messages) {
        return messages.stream().map(ImMessage::seq).toList();
    }

    private List<Long> rangeClosed(long from, long to) {
        List<Long> values = new ArrayList<>();
        for (long value = from; value <= to; value++) {
            values.add(value);
        }
        return values;
    }

    private UUID createUser(String prefix) {
        UUID id = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO users (id, username, display_name, avatar, status)
                        VALUES (:id, :username, :displayName, '页', 'ACTIVE')
                        """)
                .param("id", id)
                .param("username", prefix + "-" + id.toString().substring(0, 8))
                .param("displayName", prefix)
                .update();
        return id;
    }
}
