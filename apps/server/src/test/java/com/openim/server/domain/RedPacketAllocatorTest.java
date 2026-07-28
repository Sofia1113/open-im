package com.openim.server.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedPacketAllocator 单元测试
 * 验证等额分配包含余数、随机分配保持金额总和守恒且每份为正。
 */
class RedPacketAllocatorTest {
    @Test
    void allocatesEqualSharesIncludingRemainder() {
        RedPacketAllocator allocator = new RedPacketAllocator(new Random(1));
        assertThat(allocator.equal(10, 3)).containsExactly(4L, 3L, 3L);
    }

    @Test
    void randomSharesStayPositiveAndConserveTotal() {
        RedPacketAllocator allocator = new RedPacketAllocator(new Random(42));
        for (int total = 1; total <= 200; total++) {
            for (int count = 1; count <= Math.min(total, 20); count++) {
                List<Long> shares = allocator.random(total, count);
                assertThat(shares).hasSize(count).allMatch(amount -> amount >= 1);
                assertThat(shares.stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
            }
        }
    }
}
