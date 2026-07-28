package com.openim.server.domain;

import com.openim.server.domain.DomainResults.ClaimedRedPacket;
import com.openim.server.domain.DomainResults.CreatedRedPacket;
import com.openim.server.domain.Requests.CreateRedPacket;
import com.openim.server.domain.Requests.SendMessage;
import com.openim.server.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InMemoryStore 单元测试
 * 覆盖消息幂等去重、红包重复领取防护和成员权限校验。
 */
class InMemoryStoreTest {
    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore(new RedPacketAllocator(new Random(42)));
    }

    @Test
    void deduplicatesMessagesBySenderAndClientId() {
        SendMessage input = new SendMessage(
                "conv-alice-bob",
                "message-idempotency-01",
                "text",
                Map.of("text", "hello")
        );
        var first = store.createMessage("alice", input);
        var second = store.createMessage("alice", input);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(store.listMessages("conv-alice-bob", "alice", 0)).hasSize(3);
    }

    @Test
    void returnsHistoryFromTheCursorWithoutSkippingOlderMessages() {
        for (int index = 1; index <= 110; index++) {
            store.createMessage("alice", new SendMessage(
                    "conv-alice-bob",
                    "history-message-" + index,
                    "text",
                    Map.of("text", "message " + index)
            ));
        }

        var firstPage = store.listMessages("conv-alice-bob", "alice", 0);
        var secondPage = store.listMessages(
                "conv-alice-bob",
                "alice",
                firstPage.getLast().seq()
        );

        assertThat(firstPage).hasSize(100);
        assertThat(firstPage.getFirst().seq()).isEqualTo(1);
        assertThat(firstPage.getLast().seq()).isEqualTo(100);
        assertThat(secondPage).hasSize(12);
        assertThat(secondPage.getFirst().seq()).isEqualTo(101);
        assertThat(secondPage.getLast().seq()).isEqualTo(112);
    }

    @Test
    void neverLetsAUserClaimTheSamePacketTwice() {
        CreatedRedPacket created = store.createRedPacket("alice", new CreateRedPacket(
                "conv-group",
                "random",
                100,
                2,
                "测试红包",
                "red-message-0001",
                "red-request-0001"
        ));
        ClaimedRedPacket first = store.claimRedPacket(
                created.redPacket().id(),
                "bob",
                "claim-request-0001"
        );
        ClaimedRedPacket second = store.claimRedPacket(
                created.redPacket().id(),
                "bob",
                "claim-request-0002"
        );

        assertThat(second.alreadyClaimed()).isTrue();
        assertThat(second.amount()).isEqualTo(first.amount());
        assertThat(second.redPacket().claimedCount()).isEqualTo(1);
    }

    @Test
    void preventsNonMembersFromReadingMessages() {
        assertThatThrownBy(() ->
                store.listMessages("conv-alice-bob", "carol", 0)
        ).isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("NOT_A_MEMBER");
    }
}
