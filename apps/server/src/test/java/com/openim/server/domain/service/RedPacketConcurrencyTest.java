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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 红包并发领取
 *
 * 这是全项目并发风险最高的路径：多人同时抢同一个红包，既不能超发（发出去的钱多于总额），
 * 也不能少发（份额被锁死没人能领），更不能让同一个人领到两份。
 *
 * 单线程测试无法覆盖这些，因为出问题的正是"两个事务同时读到相同状态"的窗口。
 * 这里用真实线程池并发提交，让数据库的行锁与唯一约束在真实竞争下接受检验。
 */
@SpringBootTest
class RedPacketConcurrencyTest extends AbstractPostgresIntegrationTest {
    @Autowired
    private RedPacketService redPacketService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcClient jdbcClient;

    /**
     * 抢的人多于份数：份额必须恰好发完，且金额分毫不差。
     *
     * 群聊有 3 名成员，发 3 份；让 3 个用户同时抢，全部应该抢到。
     */
    @Test
    void concurrentClaimsDistributeEveryShareExactlyOnce() throws Exception {
        long totalBefore = walletRepository.totalBalance();
        CreatedRedPacket created = redPacketService.createRedPacket(
                DemoUsers.ALICE,
                new CreateRedPacket(
                        DemoUsers.GROUP_CONVERSATION.toString(), "random", 999, 3,
                        "拼手气", "client-" + UUID.randomUUID(), "idem-" + UUID.randomUUID()));
        UUID packetId = UUID.fromString(created.redPacket().id());

        List<UUID> claimers = List.of(DemoUsers.ALICE, DemoUsers.BOB, DemoUsers.CAROL);
        List<ClaimAttempt> results = runConcurrently(claimers.stream()
                .map(userId -> (Callable<ClaimAttempt>) () -> attemptClaim(packetId, userId))
                .toList());

        long succeeded = results.stream().filter(ClaimAttempt::success).count();
        long claimedSum = results.stream().filter(ClaimAttempt::success)
                .mapToLong(ClaimAttempt::amount).sum();

        assertThat(succeeded).as("3 份红包应恰好被 3 人领走").isEqualTo(3);
        assertThat(claimedSum).as("领取金额之和必须等于红包总额").isEqualTo(999);

        var packet = redPacketService.getRedPacket(packetId, DemoUsers.ALICE);
        assertThat(packet.status()).isEqualTo("exhausted");
        assertThat(packet.claimedCount()).isEqualTo(3);
        assertThat(packet.claimedAmount()).isEqualTo(999);

        // 资金既没有凭空产生也没有消失
        assertThat(walletRepository.totalBalance()).isEqualTo(totalBefore);
        assertThat(walletRepository.findUnbalancedTransactions()).isEmpty();
    }

    /**
     * 同一个人并发重复提交：只能领到一份。
     *
     * 这是防套利的核心场景——攻击者用脚本同时打出多个领取请求，
     * 试图在应用层判断"是否已领取"之前挤进多个事务。
     */
    @Test
    void aSingleUserClaimingConcurrentlyOnlyEverReceivesOneShare() throws Exception {
        long bobBefore = redPacketService.getWallet(DemoUsers.BOB).balance();
        CreatedRedPacket created = redPacketService.createRedPacket(
                DemoUsers.ALICE,
                new CreateRedPacket(
                        DemoUsers.GROUP_CONVERSATION.toString(), "equal", 300, 3,
                        "拼手气", "client-" + UUID.randomUUID(), "idem-" + UUID.randomUUID()));
        UUID packetId = UUID.fromString(created.redPacket().id());

        // 同一用户并发发起 8 次领取
        List<ClaimAttempt> results = runConcurrently(java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> (Callable<ClaimAttempt>) () -> attemptClaim(packetId, DemoUsers.BOB))
                .toList());

        // 幂等重入会把"已领取"也算作成功返回，因此按实际入账金额判断
        long bobAfter = redPacketService.getWallet(DemoUsers.BOB).balance();
        assertThat(bobAfter - bobBefore)
                .as("并发重复领取只能入账一份（300 分 3 等份 = 100）")
                .isEqualTo(100);

        Integer claimRows = jdbcClient.sql("""
                        SELECT count(*) FROM red_packet_claims
                        WHERE red_packet_id = :packetId AND user_id = :userId
                        """)
                .param("packetId", packetId)
                .param("userId", DemoUsers.BOB)
                .query(Integer.class)
                .single();
        assertThat(claimRows).as("同一红包每人只应有一条领取记录").isEqualTo(1);
        assertThat(walletRepository.findUnbalancedTransactions()).isEmpty();

        // 未被领走的份额仍然可用，没有因并发竞争被锁死
        assertThat(results.stream().anyMatch(ClaimAttempt::success)).isTrue();
    }

    /**
     * 抢的人多于份数时，多余的请求必须被明确拒绝而不是拿到空份额。
     */
    @Test
    void claimsBeyondCapacityAreRejectedWithoutOverpaying() throws Exception {
        long totalBefore = walletRepository.totalBalance();
        CreatedRedPacket created = redPacketService.createRedPacket(
                DemoUsers.ALICE,
                new CreateRedPacket(
                        DemoUsers.GROUP_CONVERSATION.toString(), "equal", 100, 1,
                        "手快有", "client-" + UUID.randomUUID(), "idem-" + UUID.randomUUID()));
        UUID packetId = UUID.fromString(created.redPacket().id());

        // 1 份红包，Bob 与 Carol 同时抢
        List<ClaimAttempt> results = runConcurrently(List.of(
                () -> attemptClaim(packetId, DemoUsers.BOB),
                () -> attemptClaim(packetId, DemoUsers.CAROL)));

        long succeeded = results.stream().filter(ClaimAttempt::success).count();
        assertThat(succeeded).as("只有一份，因此只能有一人领到").isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.success()).map(ClaimAttempt::errorCode))
                .allMatch(code -> code.contains("EXHAUSTED") || code.contains("DUPLICATE"));

        assertThat(walletRepository.totalBalance()).isEqualTo(totalBefore);
        assertThat(walletRepository.findUnbalancedTransactions()).isEmpty();
    }

    /**
     * 并发发送红包：余额不足时不能透支。
     */
    @Test
    void concurrentSendsCannotOverdrawTheSenderBalance() throws Exception {
        long totalBefore = walletRepository.totalBalance();
        long carolBalance = redPacketService.getWallet(DemoUsers.CAROL).balance();

        // 每笔都接近全部余额，并发提交时最多只有一笔能成功
        long amount = carolBalance - 10;
        AtomicInteger succeeded = new AtomicInteger();
        List<ClaimAttempt> results = runConcurrently(java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> (Callable<ClaimAttempt>) () -> {
                    try {
                        redPacketService.createRedPacket(DemoUsers.CAROL, new CreateRedPacket(
                                DemoUsers.GROUP_CONVERSATION.toString(), "fixed", amount, 1,
                                "全部身家", "client-" + UUID.randomUUID(), "idem-" + UUID.randomUUID()));
                        succeeded.incrementAndGet();
                        return new ClaimAttempt(true, 0, "");
                    } catch (RuntimeException exception) {
                        return new ClaimAttempt(false, 0, exception.getMessage());
                    }
                })
                .toList());

        assertThat(succeeded.get()).as("余额只够一笔，并发提交不能透支").isEqualTo(1);
        assertThat(redPacketService.getWallet(DemoUsers.CAROL).balance())
                .as("余额不得为负").isGreaterThanOrEqualTo(0);
        assertThat(walletRepository.totalBalance()).isEqualTo(totalBefore);
        assertThat(walletRepository.findUnbalancedTransactions()).isEmpty();
        assertThat(results).hasSize(4);
    }

    /**
     * 并发执行并等待全部完成。
     *
     * 用 CountDownLatch 让所有线程尽量在同一时刻发起请求，
     * 否则线程池的启动开销会把它们错开，竞争窗口就测不到了。
     */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        int count = tasks.size();
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
            List<Future<T>> futures = tasks.stream()
                    .map(task -> pool.submit(() -> {
                        ready.countDown();
                        start.await(10, TimeUnit.SECONDS);
                        return task.call();
                    }))
                    .toList();

            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            List<T> results = new java.util.ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private ClaimAttempt attemptClaim(UUID packetId, UUID userId) {
        try {
            ClaimedRedPacket claimed = redPacketService.claimRedPacket(
                    packetId, userId, "claim-" + UUID.randomUUID());
            return new ClaimAttempt(true, claimed.amount(), "");
        } catch (ApiException exception) {
            // 取错误码而不是消息：消息是面向用户的中文文案，不适合作为断言依据
            return new ClaimAttempt(false, 0, exception.code());
        } catch (RuntimeException exception) {
            return new ClaimAttempt(false, 0, exception.getClass().getSimpleName());
        }
    }

    private record ClaimAttempt(boolean success, long amount, String errorCode) {
    }
}
