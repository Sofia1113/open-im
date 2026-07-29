package com.openim.server.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 当前实例的唯一标识。
 *
 * 在 Pub/Sub 跨实例事件分发中用于区分消息来源，
 * 避免实例处理自己发出的消息造成重复投递。
 *
 * K8s 部署时可通过 INSTANCE_ID 注入 Pod 名称，便于日志关联。
 */
@Component
public class InstanceId {
    private final String value;

    public InstanceId(
            @Value("${openim.instance-id:}") String configuredId
    ) {
        this.value = configuredId == null || configuredId.isBlank()
                ? UUID.randomUUID().toString()
                : configuredId;
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
