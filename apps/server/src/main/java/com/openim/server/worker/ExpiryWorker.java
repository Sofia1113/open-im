package com.openim.server.worker;

import com.openim.server.domain.service.CallService;
import com.openim.server.domain.service.RedPacketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 过期收敛 Worker
 *
 * 迁移前红包过期退款是"懒惰"的：只有当有人恰好访问该红包时才会触发检查，
 * 没人访问就永远不退款。这里改由定时任务驱动，使过期退款成为一个确定会发生的事实。
 *
 * 每个红包在独立事务中处理，单个失败不影响本轮其余红包，失败的会在下一轮重新扫描到。
 */
@Component
public class ExpiryWorker {
    private static final Logger log = LoggerFactory.getLogger(ExpiryWorker.class);

    /** 单轮处理上限，避免积压时一次拉取过多导致事务过长 */
    private static final int BATCH_SIZE = 100;

    private final RedPacketService redPacketService;
    private final CallService callService;

    public ExpiryWorker(RedPacketService redPacketService, CallService callService) {
        this.redPacketService = redPacketService;
        this.callService = callService;
    }

    /**
     * 退还过期红包中未领取的金额。
     *
     * 固定延迟而非固定频率：上一轮没跑完时不会堆叠新的执行。
     */
    @Scheduled(fixedDelayString = "${openim.worker.red-packet-expiry-interval:30000}")
    public void refundExpiredRedPackets() {
        List<UUID> expired = redPacketService.findExpiredPacketIds(BATCH_SIZE);
        if (expired.isEmpty()) {
            return;
        }
        int refunded = 0;
        for (UUID packetId : expired) {
            try {
                if (redPacketService.refundExpiredPacket(packetId).isPresent()) {
                    refunded++;
                }
            } catch (RuntimeException exception) {
                // 单个红包失败不能中断整批；账务异常必须留下完整堆栈供排查
                log.error("Failed to refund expired red packet redPacketId={}", packetId, exception);
            }
        }
        if (refunded > 0) {
            log.info("Refunded {} expired red packets", refunded);
        }
    }

    /** 结束超时未接听的通话邀请，避免房间永远停留在 ringing */
    @Scheduled(fixedDelayString = "${openim.worker.call-expiry-interval:15000}")
    public void expireTimedOutCalls() {
        try {
            List<UUID> ended = callService.expireTimedOutCalls(BATCH_SIZE);
            if (!ended.isEmpty()) {
                log.info("Ended {} timed-out call rooms", ended.size());
            }
        } catch (RuntimeException exception) {
            log.error("Failed to expire timed-out calls", exception);
        }
    }
}
