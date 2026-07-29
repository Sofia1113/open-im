-- OpenIM 基线 Schema
--
-- 对应 docs/01-architecture/DATA_MODEL.md。建模约定：
--   * 主键使用 uuid，时间使用 timestamptz（UTC）
--   * 金额使用 bigint 整数分，禁止浮点数
--   * 枚举使用 varchar + CHECK 约束而非 PG enum 类型，便于后续增加取值时无需 ALTER TYPE
--   * 核心事实（消息、账务、审计）只追加或改状态，不物理删除

-- ============================================================
-- 1. 用户与设备
-- ============================================================

CREATE TABLE users (
    id            uuid        PRIMARY KEY,
    username      varchar(64) NOT NULL UNIQUE,
    display_name  varchar(64) NOT NULL,
    avatar        varchar(16),
    status        varchar(16) NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE', 'FROZEN', 'DELETED')),
    presence      varchar(16) NOT NULL DEFAULT 'offline'
                              CHECK (presence IN ('online', 'offline')),
    last_seen_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN users.presence IS '实时在线状态。单实例由连接注册表维护，多实例部署后应迁移到 Redis。';

-- 设备表承载刷新令牌轮换与远程下线能力。
CREATE TABLE devices (
    id                  varchar(128) PRIMARY KEY,
    user_id             uuid         NOT NULL REFERENCES users (id),
    platform            varchar(16)  NOT NULL DEFAULT 'WEB'
                                     CHECK (platform IN ('WEB', 'DESKTOP', 'IOS', 'ANDROID')),
    name                varchar(128),
    refresh_token_hash  text,
    last_active_at      timestamptz  NOT NULL DEFAULT now(),
    revoked_at          timestamptz,
    created_at          timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN devices.refresh_token_hash IS '只保存刷新令牌哈希，不保存明文。重用已轮换的令牌应触发设备撤销。';

CREATE INDEX idx_devices_user_active ON devices (user_id, last_active_at DESC);

-- ============================================================
-- 2. 会话与消息
-- ============================================================

CREATE TABLE conversations (
    id               uuid         PRIMARY KEY,
    type             varchar(16)  NOT NULL CHECK (type IN ('direct', 'group')),
    title            varchar(64),
    avatar           varchar(16),
    owner_id         uuid         REFERENCES users (id),
    next_seq         bigint       NOT NULL DEFAULT 1 CHECK (next_seq >= 1),
    last_message     text,
    last_active_at   timestamptz  NOT NULL DEFAULT now(),
    created_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT chk_group_has_title CHECK (type <> 'group' OR title IS NOT NULL)
);

COMMENT ON COLUMN conversations.next_seq IS '下一个消息序号。写消息时在事务内锁定会话行后自增，保证会话内 seq 连续且唯一。';

-- 保证相同两人只有一个有效单聊：写入时按字典序规范化 (low, high)。
CREATE TABLE direct_conversation_keys (
    user_low_id      uuid NOT NULL REFERENCES users (id),
    user_high_id     uuid NOT NULL REFERENCES users (id),
    conversation_id  uuid NOT NULL REFERENCES conversations (id),

    PRIMARY KEY (user_low_id, user_high_id),
    CONSTRAINT chk_direct_key_order CHECK (user_low_id < user_high_id)
);

CREATE TABLE conversation_members (
    conversation_id  uuid         NOT NULL REFERENCES conversations (id),
    user_id          uuid         NOT NULL REFERENCES users (id),
    role             varchar(16)  NOT NULL DEFAULT 'MEMBER'
                                  CHECK (role IN ('MEMBER', 'ADMIN', 'OWNER')),
    joined_at        timestamptz  NOT NULL DEFAULT now(),
    left_at          timestamptz,
    read_seq         bigint       NOT NULL DEFAULT 0 CHECK (read_seq >= 0),
    muted            boolean      NOT NULL DEFAULT false,
    pinned_at        timestamptz,

    PRIMARY KEY (conversation_id, user_id)
);

COMMENT ON COLUMN conversation_members.read_seq IS '已读游标。未读数由 conversations.next_seq - 1 - read_seq 推导，不单独存储计数。';

CREATE INDEX idx_conversation_members_user ON conversation_members (user_id)
    WHERE left_at IS NULL;

CREATE TABLE messages (
    id                 uuid         PRIMARY KEY,
    conversation_id    uuid         NOT NULL REFERENCES conversations (id),
    seq                bigint       NOT NULL CHECK (seq >= 1),
    sender_id          uuid         NOT NULL REFERENCES users (id),
    client_message_id  varchar(128) NOT NULL,
    type               varchar(16)  NOT NULL
                                    CHECK (type IN ('text', 'image', 'file', 'system', 'call', 'red_packet')),
    content            jsonb        NOT NULL,
    status             varchar(16)  NOT NULL DEFAULT 'ACTIVE'
                                    CHECK (status IN ('ACTIVE', 'RECALLED', 'DELETED')),
    sent_at            timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_messages_conversation_seq UNIQUE (conversation_id, seq),
    CONSTRAINT uq_messages_sender_client_id UNIQUE (sender_id, client_message_id)
);

COMMENT ON CONSTRAINT uq_messages_sender_client_id ON messages
    IS '客户端幂等键。重试发送时依赖该约束去重，而不是先查后写。';

CREATE INDEX idx_messages_conversation_seq ON messages (conversation_id, seq DESC);

-- ============================================================
-- 3. 通话
-- ============================================================

CREATE TABLE call_rooms (
    id               uuid         PRIMARY KEY,
    conversation_id  uuid         NOT NULL REFERENCES conversations (id),
    creator_id       uuid         NOT NULL REFERENCES users (id),
    media_type       varchar(16)  NOT NULL CHECK (media_type IN ('audio', 'video')),
    topology         varchar(16)  NOT NULL DEFAULT 'MESH' CHECK (topology IN ('MESH', 'SFU')),
    status           varchar(16)  NOT NULL
                                  CHECK (status IN ('ringing', 'active', 'ended')),
    idempotency_key  varchar(128) NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at       timestamptz  NOT NULL,
    started_at       timestamptz,
    ended_at         timestamptz,
    end_reason       varchar(64),

    CONSTRAINT uq_call_rooms_creator_idempotency UNIQUE (creator_id, idempotency_key)
);

CREATE INDEX idx_call_rooms_conversation ON call_rooms (conversation_id, created_at DESC);

-- 未结束通话的忙线检测索引
CREATE INDEX idx_call_rooms_open ON call_rooms (status) WHERE status <> 'ended';

CREATE TABLE call_participants (
    room_id           uuid         NOT NULL REFERENCES call_rooms (id),
    user_id           uuid         NOT NULL REFERENCES users (id),
    state             varchar(16)  NOT NULL
                                   CHECK (state IN ('invited', 'ringing', 'joined', 'rejected', 'left', 'missed')),
    joined_device_id  varchar(128),
    joined_at         timestamptz,
    left_at           timestamptz,

    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX idx_call_participants_user ON call_participants (user_id)
    WHERE state = 'joined';

-- ============================================================
-- 4. 钱包与红包（复式记账）
-- ============================================================

-- 账户区分用户账户与系统账户。红包资金在"已发出未领取"期间挂在托管账户上，
-- 使得任意时刻全部账户余额之和恒定，可对账。
CREATE TABLE wallet_accounts (
    id                 uuid        PRIMARY KEY,
    owner_type         varchar(16) NOT NULL CHECK (owner_type IN ('USER', 'SYSTEM')),
    user_id            uuid        REFERENCES users (id),
    purpose            varchar(32) NOT NULL DEFAULT 'GENERAL',
    currency           char(3)     NOT NULL DEFAULT 'CNY',
    available_balance  bigint      NOT NULL DEFAULT 0 CHECK (available_balance >= 0),
    frozen_balance     bigint      NOT NULL DEFAULT 0 CHECK (frozen_balance >= 0),
    version            bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT chk_user_account_has_user CHECK (owner_type <> 'USER' OR user_id IS NOT NULL),
    CONSTRAINT chk_system_account_has_no_user CHECK (owner_type <> 'SYSTEM' OR user_id IS NULL)
);

CREATE UNIQUE INDEX uq_wallet_accounts_user ON wallet_accounts (user_id, currency)
    WHERE owner_type = 'USER';
CREATE UNIQUE INDEX uq_wallet_accounts_system ON wallet_accounts (purpose, currency)
    WHERE owner_type = 'SYSTEM';

-- 一笔业务对应一个 transaction，其下的 entries 借贷必须相等。
CREATE TABLE wallet_transactions (
    id               uuid         PRIMARY KEY,
    type             varchar(32)  NOT NULL
                                  CHECK (type IN ('RED_PACKET_SEND', 'RED_PACKET_CLAIM',
                                                  'RED_PACKET_REFUND', 'REVERSAL')),
    business_id      uuid,
    idempotency_key  varchar(160) NOT NULL UNIQUE,
    status           varchar(16)  NOT NULL DEFAULT 'COMMITTED'
                                  CHECK (status IN ('PENDING', 'COMMITTED', 'REVERSED')),
    created_at       timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_wallet_transactions_business ON wallet_transactions (business_id);

CREATE TABLE wallet_entries (
    id              uuid        PRIMARY KEY,
    transaction_id  uuid        NOT NULL REFERENCES wallet_transactions (id),
    account_id      uuid        NOT NULL REFERENCES wallet_accounts (id),
    direction       varchar(8)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount          bigint      NOT NULL CHECK (amount > 0),
    balance_after   bigint      NOT NULL CHECK (balance_after >= 0),
    created_at      timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE wallet_entries IS '不可变流水。DEBIT 表示账户资金减少，CREDIT 表示增加；同一 transaction_id 下两者合计必须相等。';

CREATE INDEX idx_wallet_entries_account ON wallet_entries (account_id, created_at DESC);
CREATE INDEX idx_wallet_entries_transaction ON wallet_entries (transaction_id);

CREATE TABLE red_packets (
    id               uuid         PRIMARY KEY,
    conversation_id  uuid         NOT NULL REFERENCES conversations (id),
    sender_id        uuid         NOT NULL REFERENCES users (id),
    message_id       uuid         NOT NULL REFERENCES messages (id),
    mode             varchar(16)  NOT NULL CHECK (mode IN ('fixed', 'equal', 'random')),
    total_amount     bigint       NOT NULL CHECK (total_amount > 0),
    total_count      int          NOT NULL CHECK (total_count > 0),
    claimed_amount   bigint       NOT NULL DEFAULT 0 CHECK (claimed_amount >= 0),
    claimed_count    int          NOT NULL DEFAULT 0 CHECK (claimed_count >= 0),
    greeting         varchar(60)  NOT NULL,
    status           varchar(24)  NOT NULL DEFAULT 'published'
                                  CHECK (status IN ('published', 'partially_claimed', 'exhausted',
                                                    'expired', 'refunded')),
    expires_at       timestamptz  NOT NULL,
    idempotency_key  varchar(128) NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_red_packets_sender_idempotency UNIQUE (sender_id, idempotency_key),
    CONSTRAINT chk_red_packet_claimed_within_total
        CHECK (claimed_amount <= total_amount AND claimed_count <= total_count)
);

-- 过期退款 Worker 的扫描索引
CREATE INDEX idx_red_packets_pending_expiry ON red_packets (expires_at)
    WHERE status IN ('published', 'partially_claimed');

-- 份额在创建时一次性预分配，领取只是把某一份标记为已领，使领取事务足够短。
CREATE TABLE red_packet_shares (
    id             uuid        PRIMARY KEY,
    red_packet_id  uuid        NOT NULL REFERENCES red_packets (id),
    ordinal        int         NOT NULL CHECK (ordinal >= 0),
    amount         bigint      NOT NULL CHECK (amount > 0),
    claimed_by     uuid        REFERENCES users (id),
    claimed_at     timestamptz,

    CONSTRAINT uq_red_packet_shares_ordinal UNIQUE (red_packet_id, ordinal)
);

-- 领取时用 FOR UPDATE SKIP LOCKED 抢占未领份额，避免并发领取相互阻塞。
CREATE INDEX idx_red_packet_shares_unclaimed ON red_packet_shares (red_packet_id)
    WHERE claimed_by IS NULL;

CREATE TABLE red_packet_claims (
    id               uuid         PRIMARY KEY,
    red_packet_id    uuid         NOT NULL REFERENCES red_packets (id),
    share_id         uuid         NOT NULL UNIQUE REFERENCES red_packet_shares (id),
    user_id          uuid         NOT NULL REFERENCES users (id),
    amount           bigint       NOT NULL CHECK (amount > 0),
    idempotency_key  varchar(128) NOT NULL,
    claimed_at       timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uq_red_packet_claims_packet_user UNIQUE (red_packet_id, user_id),
    CONSTRAINT uq_red_packet_claims_user_idempotency UNIQUE (user_id, idempotency_key)
);

COMMENT ON CONSTRAINT uq_red_packet_claims_packet_user ON red_packet_claims
    IS '同一红包每人只能领一次。这是防并发套利的最后一道防线，即使应用层判断失效也会被数据库拒绝。';

-- ============================================================
-- 5. 可靠事件与审计
-- ============================================================

-- 领域变更与事件写入同一事务，由 Worker 轮询发布，保证至少一次投递。
CREATE TABLE outbox_events (
    id               uuid         PRIMARY KEY,
    aggregate_type   varchar(32)  NOT NULL,
    aggregate_id     uuid         NOT NULL,
    event_type       varchar(64)  NOT NULL,
    payload          jsonb        NOT NULL,
    occurred_at      timestamptz  NOT NULL DEFAULT now(),
    published_at     timestamptz,
    attempts         int          NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz  NOT NULL DEFAULT now(),
    last_error       text
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (next_attempt_at)
    WHERE published_at IS NULL;

CREATE TABLE audit_logs (
    id           uuid         PRIMARY KEY,
    actor_type   varchar(16)  NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM', 'ADMIN')),
    actor_id     uuid,
    action       varchar(64)  NOT NULL,
    target_type  varchar(32),
    target_id    uuid,
    reason       text,
    request_id   varchar(128),
    ip_hash      varchar(64),
    metadata     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_logs IS '只追加。登录、设备变更、账务交易、冲正和策略变更必须留痕；ip 只存哈希，不存明文。';

CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs (action, created_at DESC);

-- ============================================================
-- 6. 系统账户初始化
-- ============================================================

INSERT INTO wallet_accounts (id, owner_type, user_id, purpose, currency, available_balance)
VALUES ('00000000-0000-0000-0000-00000000e5c0', 'SYSTEM', NULL, 'RED_PACKET_ESCROW', 'CNY', 0);
