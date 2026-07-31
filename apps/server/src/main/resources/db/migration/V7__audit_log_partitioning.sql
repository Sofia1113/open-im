-- audit_logs 按月分区
--
-- ── 为什么分区 audit_logs ──────────────────────────────────
--
-- 它是增长最快的表：每次登录、刷新、改密、红包收发都写一行，而没有任何
-- 业务查询会读它——它只在排查和合规审查时被按时间范围扫描。
--
-- 分区带来三个具体收益：
--   1. 按时间删除变成 DROP TABLE（瞬时、不产生 WAL 膨胀），
--      而不是 DELETE 数百万行再等 VACUUM 回收
--   2. 按时间范围的查询只扫命中的分区
--   3. 单个分区的索引足够小，能留在内存里
--
-- ── 为什么<b>不</b>分区 messages ───────────────────────────
--
-- 这一点与直觉相反，值得写清楚，否则下一个人会"顺手补上"：
--
--   * **查询模式不匹配**。消息查询全部是 `WHERE conversation_id = ? AND seq > ?`
--     （见 MessageRepository.listAfterSeq），走 idx_messages_conversation_seq。
--     按时间分区后，一次历史拉取会跨越多个分区——分区裁剪帮不上忙，
--     因为条件里没有时间。性能只会变差。
--
--   * **删除的价值用不上**。IM 用户期望消息永久保留，不存在"删除 90 天前的消息"
--     这种需求。分区最大的好处（DROP PARTITION）在这里没有场景。
--
--   * **会破坏外键完整性**。分区表的主键必须包含分区键，即 (id, sent_at)。
--     而 red_packets.message_id 引用 messages(id)——单列外键无法指向复合主键。
--     要分区就得放弃这个约束，用应用层校验替代。为了一个没有收益的改动
--     牺牲一条数据库级的完整性保证，是明确的负收益。
--
-- 如果未来 messages 真的大到需要处理，正确的方向是**归档**（把冷会话的消息
-- 移到独立的归档表或对象存储）或**按 conversation_id 哈希分区**，
-- 而不是按时间分区。
--
-- ── 迁移方式 ───────────────────────────────────────────────
--
-- PostgreSQL 不能把已有普通表原地改成分区表，必须新建后搬数据。
-- 这里的做法在数据量小时是安全的；若已有大量数据，应改为在线迁移：
-- 新表双写 → 回填 → 切换读 → 删旧表。
--
-- **顺序至关重要：必须先建好全部目标月分区，再插入数据。**
--
-- 这个脚本的第一版把数据先插进 DEFAULT 分区，然后才创建当月分区，
-- 结果在<b>已有数据</b>的库上必然失败：
--
--     ERROR: updated partition constraint for default partition
--            "audit_logs_default" would be violated by some row
--
-- 因为 PostgreSQL 创建新分区时会校验 DEFAULT 里没有属于新分区范围的行。
-- 而这个失败在测试里<b>永远不会出现</b>——集成测试用的是每次全新的
-- Testcontainers 数据库，audit_logs 是空的，DEFAULT 里没有任何行可以冲突。
--
-- 空库上通过、有数据的库上失败，正是生产环境会遇到的情况。
-- 这类迁移必须在一个有代表性数据的库副本上验证过才能上线
-- （CD 流水线的部署前备份可以用来做这件事）。

-- 1. 保留旧表，稍后搬完数据再删
ALTER TABLE audit_logs RENAME TO audit_logs_legacy;

-- 旧索引跟着改名，避免与新表的索引重名
ALTER INDEX idx_audit_logs_actor RENAME TO idx_audit_logs_legacy_actor;
ALTER INDEX idx_audit_logs_action RENAME TO idx_audit_logs_legacy_action;

-- 2. 新建分区表
--
-- 主键是 (id, created_at)：分区表要求主键包含分区键。
-- id 仍然是 UUID 且全局唯一，复合主键不影响任何现有查询——
-- audit_logs 没有被任何外键引用，这是它能被分区而 messages 不能的关键区别。
CREATE TABLE audit_logs (
    id           uuid         NOT NULL,
    actor_type   varchar(16)  NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM', 'ADMIN')),
    actor_id     uuid,
    action       varchar(64)  NOT NULL,
    target_type  varchar(32),
    target_id    uuid,
    reason       text,
    request_id   varchar(128),
    ip_hash      varchar(64),
    metadata     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

COMMENT ON TABLE audit_logs IS
    '只追加，按月分区。登录、设备变更、账务交易、冲正和策略变更必须留痕；ip 只存哈希，不存明文。';

-- 索引建在父表上，PostgreSQL 会自动在每个分区上创建对应的本地索引
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs (action, created_at DESC);

-- 3. 兜底分区
--
-- DEFAULT 分区接收所有不落在任何具体分区范围内的行。
-- 没有它的话，一旦维护任务漏建下个月的分区，**写入会直接失败**——
-- 那意味着登录、发红包这些带审计的操作全部报错。
-- 有了 DEFAULT，最坏情况只是数据都进了同一张表，功能不受影响。
CREATE TABLE audit_logs_default PARTITION OF audit_logs DEFAULT;

-- ============================================================
-- 4. 分区维护函数
-- ============================================================
--
-- 定义必须在搬迁数据<b>之前</b>：下一步要用它为历史数据涉及的每个月建分区。
--
-- 放在数据库里而不是应用里，理由：它需要执行 DDL。让应用账号持有 CREATE TABLE
-- 权限，只为了每月建一张分区表，是不必要的权限扩大。
-- 函数用 SECURITY DEFINER 以所有者权限执行，应用只需要 EXECUTE 权限。

CREATE OR REPLACE FUNCTION ensure_audit_log_partition(target_month date)
RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    start_at   date := date_trunc('month', target_month)::date;
    end_at     date := (date_trunc('month', target_month) + interval '1 month')::date;
    part_name  text := 'audit_logs_' || to_char(start_at, 'YYYY_MM');
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_class WHERE relname = part_name
    ) THEN
        RETURN part_name || ' (已存在)';
    END IF;

    EXECUTE format(
        'CREATE TABLE %I PARTITION OF audit_logs FOR VALUES FROM (%L) TO (%L)',
        part_name, start_at, end_at
    );
    RETURN part_name || ' (已创建)';
END;
$$;

COMMENT ON FUNCTION ensure_audit_log_partition(date) IS
    '为指定月份创建 audit_logs 分区，幂等。由 AuditPartitionWorker 定期调用。';

-- 删除早于保留期的分区。
--
-- 返回被删除的分区名，供调用方记录日志——静默删除审计数据是不可接受的，
-- 每一次都必须留下痕迹。
CREATE OR REPLACE FUNCTION drop_audit_log_partitions_before(cutoff date)
RETURNS SETOF text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    part record;
BEGIN
    FOR part IN
        SELECT c.relname
          FROM pg_class c
          JOIN pg_inherits i ON i.inhrelid = c.oid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.relname = 'audit_logs'
           AND c.relname ~ '^audit_logs_[0-9]{4}_[0-9]{2}$'
           -- 从分区名解析月份，早于 cutoff 所在月份才删
           AND to_date(substring(c.relname from 12), 'YYYY_MM')
               < date_trunc('month', cutoff)
    LOOP
        EXECUTE format('DROP TABLE %I', part.relname);
        RETURN NEXT part.relname;
    END LOOP;
END;
$$;

COMMENT ON FUNCTION drop_audit_log_partitions_before(date) IS
    '删除早于指定日期的 audit_logs 月分区，返回被删除的分区名。DEFAULT 分区永不删除。';

-- ============================================================
-- 5. 为历史数据涉及的每个月建分区
-- ============================================================
--
-- **必须在插入数据之前完成。** 反过来做的话，数据会先落进 DEFAULT，
-- 而后续创建月分区时 PostgreSQL 会因"DEFAULT 里存在属于新分区的行"而拒绝：
--
--     ERROR: updated partition constraint for default partition ... would be violated
--
-- 空库上不会触发（DEFAULT 里没有行），有数据的库上必然触发。
DO $$
DECLARE
    month_start date;
BEGIN
    FOR month_start IN
        SELECT DISTINCT date_trunc('month', created_at)::date
          FROM audit_logs_legacy
         ORDER BY 1
    LOOP
        PERFORM ensure_audit_log_partition(month_start);
    END LOOP;
END $$;

-- 当月与下月：即使旧表是空的也要建好，使迁移后的第一次写入就落进月分区
SELECT ensure_audit_log_partition(current_date);
SELECT ensure_audit_log_partition((current_date + interval '1 month')::date);

-- ============================================================
-- 6. 搬迁历史数据
-- ============================================================
--
-- 此时全部目标分区已存在，每一行都会被路由到对应的月分区，
-- DEFAULT 保持为空——这是它应有的状态（它只是漏建分区时的兜底）。
INSERT INTO audit_logs (
    id, actor_type, actor_id, action, target_type, target_id,
    reason, request_id, ip_hash, metadata, created_at
)
SELECT id, actor_type, actor_id, action, target_type, target_id,
       reason, request_id, ip_hash, metadata, created_at
  FROM audit_logs_legacy;

DROP TABLE audit_logs_legacy;
