package com.openim.server.domain.service;

import com.openim.server.domain.DomainResults.ClaimedRedPacket;
import com.openim.server.domain.DomainResults.CreatedRedPacket;
import com.openim.server.domain.Requests.CreateRedPacket;
import com.openim.server.domain.repository.WalletRepository;
import com.openim.server.support.AbstractPostgresIntegrationTest;
import com.openim.server.support.DemoUsers;
import com.openim.server.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 红包账务的端到端集成测试
 *
 * 重点验证迁移到复式记账后资金守恒：每一步之后都断言全部账户余额之和不变，
 * 且所有交易借贷平衡。这是"钱有没有凭空产生或消失"的直接检验。
 */
@SpringBootTest
class RedPacketServiceIntegrationTest extends AbstractPostgresIntegrationTest {
    @Autowired
    private RedPacketService redPacketService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void sendingAndClaimingKeepsTheLedgerBalanced() {
        long totalBefore = walletRepository.totalBalance();
        long aliceBefore = balanceOf(DemoUsers.ALICE);
        long bobBefore = balanceOf(DemoUsers.BOB);

        CreatedRedPacket created = redPacketService.createRedPacket(
                DemoUsers.ALICE, request(DemoUsers.DIRECT_CONVERSATION, "fixed", 100, 1));

        assertThat(created.redPacket().totalAmount()).isEqualTo(100);
        assertThat(created.message().type()).isEqualTo("red_packet");
        // 发出后钱从 Alice 转到托管账户，总额不变
        assertThat(balanceOf(DemoUsers.ALICE)).isEqualTo(aliceBefore - 100);
        assertThat(walletRepository.totalBalance()).isEqualTo(totalBefore);

        UUID packetId = UUID.fromString(created.redPacket().id());
        ClaimedRedPacket claimed = redPacketService.claimRedPacket(
                packetId, DemoUsers.BOB, "claim-" + packetId);

        assertThat(claimed.alreadyClaimed()).isFalse();
        assertThat(claimed.amount()).isEqualTo(100);
        assertThat(claimed.redPacket().status()).isEqualTo("exhausted");
        assertThat(balanceOf(DemoUsers.BOB)).isEqualTo(bobBefore + 100);
        assertThat(walletRepository.totalBalance()).isEqualTo(totalBefore);
        assertThat(walletRepository.findUnbalancedTransactions()).isEmpty();
    }

    @Test
    void claimingTwiceReturnsTheFirstResultWithoutPayingAgain() {
        CreatedRedPacket created = redPacketService.createRedPacket(
                DemoUsers.ALICE, request(DemoUsers.DIRECT_CONVERSATION, "fixed", 50, 1));
        UUID packetId = UUID.fromString(created.redPacket().id());

        ClaimedRedPacket first = redPacketService.claimRedPacket(
                packetId, DemoUsers.BOB, "claim-first-" + packetId);
        long afterFirstClaim = balanceOf(DemoUsers.BOB);

        ClaimedRedPacket second = redPacketService.claimRedPacket(
                packetId, DemoUsers.BOB, "claim-second-" + packetId);

        assertThat(first.alreadyClaimed()).isFalse();
        assertThat(second.alreadyClaimed()).isTrue();
        assertThat(second.amount()).isEqualTo(first.amount());
        // 关键断言：第二次领取不得再次入账
        assertThat(balanceOf(DemoUsers.BOB)).isEqualTo(afterFirstClaim);
        assertThat(walletRepository.findUnbalancedTransactions()).isEmpty();
    }

    @Test
    void resendingWithTheSameIdempotencyKeyDoesNotDebitTwice() {
        String idempotencyKey = "idem-" + UUID.randomUUID();
        CreateRedPacket input = new CreateRedPacket(
                DemoUsers.DIRECT_CONVERSATION.toString(), "fixed", 80, 1,
                "恭喜发财", "client-" + UUID.randomUUID(), idempotencyKey);

        CreatedRedPacket first = redPacketService.createRedPacket(DemoUsers.ALICE, input);
        long afterFirstSend = balanceOf(DemoUsers.ALICE);

        CreatedRedPacket second = redPacketService.createRedPacket(DemoUsers.ALICE, input);

        assertThat(second.redPacket().id()).isEqualTo(first.redPacket().id());
        assertThat(balanceOf(DemoUsers.ALICE)).isEqualTo(afterFirstSend);
    }

    @Test
    void expiredRedPacketRefundsTheUnclaimedRemainderToTheSender() {
        long totalBefore = walletRepository.totalBalance();
        CreatedRedPacket created = redPacketService.createRedPacket(
                DemoUsers.ALICE, request(DemoUsers.GROUP_CONVERSATION, "equal", 90, 3));
        UUID packetId = UUID.fromString(created.redPacket().id());

        redPacketService.claimRedPacket(packetId, DemoUsers.BOB, "claim-" + packetId);
        long aliceAfterSend = balanceOf(DemoUsers.ALICE);

        expire(packetId);
        assertThat(redPacketService.findExpiredPacketIds(10)).contains(packetId);
        assertThat(redPacketService.refundExpiredPacket(packetId)).isPresent();

        // 3 份共 90 分，Bob 领走 30，剩余 60 应退回 Alice
        assertThat(balanceOf(DemoUsers.ALICE)).isEqualTo(aliceAfterSend + 60);
        assertThat(walletRepository.totalBalance()).isEqualTo(totalBefore);
        assertThat(walletRepository.findUnbalancedTransactions()).isEmpty();
    }

    @Test
    void claimingAnExpiredRedPacketIsRejected() {
        CreatedRedPacket created = redPacketService.createRedPacket(
                DemoUsers.ALICE, request(DemoUsers.GROUP_CONVERSATION, "equal", 60, 2));
        UUID packetId = UUID.fromString(created.redPacket().id());
        expire(packetId);

        assertThatThrownBy(() -> redPacketService.claimRedPacket(
                packetId, DemoUsers.BOB, "claim-" + packetId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("红包已过期");
    }

    @Test
    void nonMembersCannotClaimFromAConversationTheyDoNotBelongTo() {
        CreatedRedPacket created = redPacketService.createRedPacket(
                DemoUsers.ALICE, request(DemoUsers.DIRECT_CONVERSATION, "fixed", 40, 1));
        UUID packetId = UUID.fromString(created.redPacket().id());

        // Carol 不在 Alice 与 Bob 的单聊里
        assertThatThrownBy(() -> redPacketService.claimRedPacket(
                packetId, DemoUsers.CAROL, "claim-" + packetId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("你不是该会话成员");
    }

    private CreateRedPacket request(UUID conversationId, String mode, long amount, int count) {
        return new CreateRedPacket(
                conversationId.toString(),
                mode,
                amount,
                count,
                "恭喜发财",
                "client-" + UUID.randomUUID(),
                "idem-" + UUID.randomUUID()
        );
    }

    /** 把红包的过期时间推到过去，避免测试为了等待过期而真的睡 24 小时 */
    private void expire(UUID packetId) {
        jdbcClient.sql("UPDATE red_packets SET expires_at = now() - interval '1 minute' WHERE id = :id")
                .param("id", packetId)
                .update();
    }

    private long balanceOf(UUID userId) {
        return redPacketService.getWallet(userId).balance();
    }
}
