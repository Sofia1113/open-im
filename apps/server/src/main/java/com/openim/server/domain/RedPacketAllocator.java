package com.openim.server.domain;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 红包金额分配器
 * 支持等额分配和随机分配（拼手气）两种模式。
 */
@Component
public class RedPacketAllocator {
    private final RandomGenerator random;

    public RedPacketAllocator() {
        this(new SecureRandom());
    }

    RedPacketAllocator(RandomGenerator random) {
        this.random = random;
    }

    /** 等额分配：尽量平均，余数分配给前几位 */
    public List<Long> equal(long totalAmount, int totalCount) {
        validate(totalAmount, totalCount);
        long base = totalAmount / totalCount;
        long remainder = totalAmount % totalCount;
        List<Long> shares = new ArrayList<>(totalCount);
        for (int index = 0; index < totalCount; index++) {
            shares.add(base + (index < remainder ? 1 : 0));
        }
        return List.copyOf(shares);
    }

    /**
     * 随机分配（拼手气）
     * 每次在 [1, 剩余金额均值×2] 范围内随机，最后一位自动补齐，
     * 完成后 Fisher-Yates 洗牌打乱顺序。
     */
    public List<Long> random(long totalAmount, int totalCount) {
        validate(totalAmount, totalCount);
        List<Long> shares = new ArrayList<>(totalCount);
        long remainingAmount = totalAmount;

        for (int remainingCount = totalCount; remainingCount > 1; remainingCount--) {
            long guaranteedRemainder = remainingCount - 1L;
            long averageDouble = remainingAmount * 2 / remainingCount;
            long maximum = Math.max(1, Math.min(
                    remainingAmount - guaranteedRemainder,
                    averageDouble
            ));
            long amount = random.nextLong(1, maximum + 1);
            shares.add(amount);
            remainingAmount -= amount;
        }

        shares.add(remainingAmount);
        for (int index = shares.size() - 1; index > 0; index--) {
            int swapWith = random.nextInt(index + 1);
            Long current = shares.get(index);
            shares.set(index, shares.get(swapWith));
            shares.set(swapWith, current);
        }
        assertValid(shares, totalAmount, totalCount);
        return List.copyOf(shares);
    }

    private void validate(long totalAmount, int totalCount) {
        if (totalAmount < 1 || totalCount < 1 || totalAmount < totalCount) {
            throw new IllegalArgumentException("Invalid red packet allocation input");
        }
    }

    private void assertValid(List<Long> shares, long totalAmount, int totalCount) {
        long total = shares.stream().mapToLong(Long::longValue).sum();
        if (shares.size() != totalCount || total != totalAmount ||
                shares.stream().anyMatch(amount -> amount < 1)) {
            throw new IllegalStateException("Generated an invalid red packet allocation");
        }
    }
}
