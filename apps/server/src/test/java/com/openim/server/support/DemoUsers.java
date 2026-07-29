package com.openim.server.support;

import java.util.UUID;

/**
 * 演示种子数据中的固定标识
 *
 * 与 db/seed/R__demo_seed.sql 保持一致。集中在此处，避免 UUID 字面量散落在各测试中。
 */
public final class DemoUsers {
    public static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID CAROL = UUID.fromString("33333333-3333-3333-3333-333333333333");

    public static final UUID DIRECT_CONVERSATION = UUID.fromString("c1111111-1111-1111-1111-111111111111");
    public static final UUID GROUP_CONVERSATION = UUID.fromString("c2222222-2222-2222-2222-222222222222");

    private DemoUsers() {
    }
}
