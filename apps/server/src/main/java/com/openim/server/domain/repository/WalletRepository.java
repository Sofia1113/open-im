package com.openim.server.domain.repository;

import com.openim.server.domain.DomainModels.Wallet;
import com.openim.server.domain.DomainModels.WalletEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 钱包数据访问（复式记账）
 *
 * 资金变动一律通过 {@link #transfer} 完成：一笔业务生成一个 transaction 和借贷各一条 entry，
 * 因此任意时刻全部账户余额之和恒定，可以独立对账。红包在"已发出未领取"期间挂在系统托管账户上，
 * 而不是凭空消失又凭空出现。
 *
 * 对外暴露的 {@link WalletEntry#amount()} 沿用带符号语义（正数入账、负数出账），
 * 由 direction 转换而来，避免客户端感知复式记账细节。
 */
@Repository
public class WalletRepository {
    /** 红包托管账户，见 V1__baseline_schema.sql 末尾的初始化语句 */
    public static final UUID RED_PACKET_ESCROW_ACCOUNT =
            UUID.fromString("00000000-0000-0000-0000-00000000e5c0");

    private static final int MAX_ENTRIES = 50;

    private final JdbcClient jdbcClient;

    public WalletRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UUID> findAccountIdByUserId(UUID userId) {
        return jdbcClient.sql("""
                        SELECT id FROM wallet_accounts
                        WHERE owner_type = 'USER' AND user_id = :userId AND currency = 'CNY'
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> UUID.fromString(rs.getString("id")))
                .optional();
    }

    /** 读取钱包视图：可用余额 + 最近流水 */
    public Optional<Wallet> findWalletByUserId(UUID userId) {
        Optional<Long> balance = jdbcClient.sql("""
                        SELECT available_balance FROM wallet_accounts
                        WHERE owner_type = 'USER' AND user_id = :userId AND currency = 'CNY'
                        """)
                .param("userId", userId)
                .query(Long.class)
                .optional();
        if (balance.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Wallet(userId.toString(), balance.get(), listEntries(userId)));
    }

    /**
     * 最近流水。
     *
     * DEBIT 表示账户资金减少，转换为负数金额；CREDIT 转换为正数。
     */
    public List<WalletEntry> listEntries(UUID userId) {
        return jdbcClient.sql("""
                        SELECT e.id,
                               t.type,
                               e.direction,
                               e.amount,
                               e.balance_after,
                               t.business_id,
                               e.created_at
                          FROM wallet_entries e
                          JOIN wallet_transactions t ON t.id = e.transaction_id
                          JOIN wallet_accounts a ON a.id = e.account_id
                         WHERE a.owner_type = 'USER' AND a.user_id = :userId
                         ORDER BY e.created_at DESC, e.id DESC
                         LIMIT :limit
                        """)
                .param("userId", userId)
                .param("limit", MAX_ENTRIES)
                .query(WalletRepository::mapEntry)
                .list();
    }

    /**
     * 执行一次转账并写入借贷流水。
     *
     * 为避免并发转账因加锁顺序不同产生死锁，先按账户 ID 排序批量加行锁再更新。
     * 余额不足时返回 false，由调用方转换为业务错误并回滚事务。
     *
     * @return 转账是否成功；false 表示付款账户余额不足
     */
    public boolean transfer(
            UUID transactionId,
            String type,
            UUID businessId,
            String idempotencyKey,
            UUID fromAccountId,
            UUID toAccountId,
            long amount
    ) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive");
        }
        lockAccountsInDeterministicOrder(fromAccountId, toAccountId);

        Optional<Long> fromBalance = jdbcClient.sql("""
                        UPDATE wallet_accounts
                        SET available_balance = available_balance - :amount,
                            version = version + 1,
                            updated_at = now()
                        WHERE id = :accountId AND available_balance >= :amount
                        RETURNING available_balance
                        """)
                .param("amount", amount)
                .param("accountId", fromAccountId)
                .query(Long.class)
                .optional();
        if (fromBalance.isEmpty()) {
            return false;
        }

        long toBalance = jdbcClient.sql("""
                        UPDATE wallet_accounts
                        SET available_balance = available_balance + :amount,
                            version = version + 1,
                            updated_at = now()
                        WHERE id = :accountId
                        RETURNING available_balance
                        """)
                .param("amount", amount)
                .param("accountId", toAccountId)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                        INSERT INTO wallet_transactions (id, type, business_id, idempotency_key, status)
                        VALUES (:id, :type, :businessId, :idempotencyKey, 'COMMITTED')
                        """)
                .param("id", transactionId)
                .param("type", type)
                .param("businessId", businessId)
                .param("idempotencyKey", idempotencyKey)
                .update();

        insertEntry(transactionId, fromAccountId, "DEBIT", amount, fromBalance.get());
        insertEntry(transactionId, toAccountId, "CREDIT", amount, toBalance);
        return true;
    }

    /**
     * 按固定顺序锁定两个账户。
     *
     * 死锁的成因是两个事务以相反顺序请求同一对行锁；统一按 UUID 升序加锁即可消除环路。
     */
    private void lockAccountsInDeterministicOrder(UUID first, UUID second) {
        UUID low = first.compareTo(second) <= 0 ? first : second;
        UUID high = first.compareTo(second) <= 0 ? second : first;
        jdbcClient.sql("""
                        SELECT id FROM wallet_accounts
                        WHERE id IN (:low, :high)
                        ORDER BY id
                        FOR UPDATE
                        """)
                .param("low", low)
                .param("high", high)
                .query(String.class)
                .list();
    }

    private void insertEntry(
            UUID transactionId,
            UUID accountId,
            String direction,
            long amount,
            long balanceAfter
    ) {
        jdbcClient.sql("""
                        INSERT INTO wallet_entries
                            (id, transaction_id, account_id, direction, amount, balance_after)
                        VALUES
                            (:id, :transactionId, :accountId, :direction, :amount, :balanceAfter)
                        """)
                .param("id", UUID.randomUUID())
                .param("transactionId", transactionId)
                .param("accountId", accountId)
                .param("direction", direction)
                .param("amount", amount)
                .param("balanceAfter", balanceAfter)
                .update();
    }

    /**
     * 对账：校验所有 transaction 的借贷是否平衡。
     *
     * 供定时对账任务和集成测试调用，返回不平衡的 transaction ID 列表，正常情况下应为空。
     */
    public List<String> findUnbalancedTransactions() {
        return jdbcClient.sql("""
                        SELECT t.id::text AS id
                          FROM wallet_transactions t
                          JOIN wallet_entries e ON e.transaction_id = t.id
                         GROUP BY t.id
                        HAVING sum(CASE WHEN e.direction = 'DEBIT' THEN e.amount ELSE 0 END)
                             <> sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END)
                        """)
                .query(String.class)
                .list();
    }

    /** 全部账户余额之和，用于对账断言资金没有凭空产生或消失 */
    public long totalBalance() {
        return jdbcClient.sql("SELECT COALESCE(sum(available_balance + frozen_balance), 0) FROM wallet_accounts")
                .query(Long.class)
                .single();
    }

    private static WalletEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        long amount = rs.getLong("amount");
        boolean debit = "DEBIT".equals(rs.getString("direction"));
        return new WalletEntry(
                rs.getString("id"),
                toClientType(rs.getString("type")),
                debit ? -amount : amount,
                rs.getLong("balance_after"),
                rs.getString("business_id"),
                Mappers.instant(rs, "created_at")
        );
    }

    /** 保持既有客户端契约的小写下划线命名 */
    private static String toClientType(String transactionType) {
        return switch (transactionType) {
            case "RED_PACKET_SEND" -> "red_packet_send";
            case "RED_PACKET_CLAIM" -> "red_packet_claim";
            case "RED_PACKET_REFUND" -> "red_packet_refund";
            case "REVERSAL" -> "reversal";
            default -> transactionType.toLowerCase(java.util.Locale.ROOT);
        };
    }
}
