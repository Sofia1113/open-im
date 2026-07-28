package com.openim.server.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 请求体定义
 * 包含所有 REST API 和 WebSocket 请求的校验约束。
 */
public final class Requests {
    private Requests() {
    }

    public record DemoLogin(
            @NotBlank String userId,
            @NotBlank @Size(max = 128) String deviceId,
            @NotBlank @Size(max = 128) String deviceName
    ) {
    }

    public record Refresh(@NotBlank String refreshToken) {
    }

    public record SendMessage(
            @NotBlank String conversationId,
            @NotBlank @Size(min = 8, max = 128) String clientMessageId,
            @NotBlank String type,
            Map<String, Object> content
    ) {
    }

    public record CreateRedPacket(
            @NotBlank String conversationId,
            @NotBlank String mode,
            @Min(1) @Max(20_000) long totalAmount,
            @Min(1) @Max(100) int totalCount,
            @NotBlank @Size(max = 60) String greeting,
            @NotBlank @Size(min = 8, max = 128) String clientMessageId,
            @NotBlank @Size(min = 8, max = 128) String idempotencyKey
    ) {
    }

    public record ClaimRedPacket(
            @NotBlank @Size(min = 8, max = 128) String idempotencyKey
    ) {
    }

    public record CreateCall(
            @NotBlank String conversationId,
            @NotBlank String mediaType,
            @NotEmpty @Size(max = 5) List<String> inviteeIds,
            @NotBlank @Size(min = 8, max = 128) String idempotencyKey
    ) {
    }

    public record RtcSignal(
            @NotBlank String roomId,
            @NotBlank String targetUserId,
            JsonNode sdp,
            JsonNode candidate
    ) {
    }
}
