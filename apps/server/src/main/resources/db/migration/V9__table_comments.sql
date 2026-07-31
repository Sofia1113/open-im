-- 补齐表级注释
--
-- 迁移前 23 张表里只有 4 张有 COMMENT ON TABLE（wallet_entries、audit_logs、
-- user_credentials、password_reset_tokens）——恰好是那几张"写的时候顺手加了"的表。
-- 其余 14 张业务表的建模意图只存在于迁移文件的 `--` 注释里，而 `--` 注释
-- **不进数据库**：任何用 psql \dt+、DataGrip、DBeaver 或数据字典工具连上库的人
-- 都看不到它们，只能回头翻 Git 仓库。
--
-- 表注释是 schema 自带的文档，跟着数据走：库被 dump/restore、被只读副本复制、
-- 被 BI 工具读取时它都在。这正是 `--` 注释做不到的事。
--
-- 内容取舍：只写**从表结构本身看不出来**的东西——这张表记录什么事实、
-- 为什么这样建模、有哪些约束不能违反。列的语义已有 COMMENT ON COLUMN 覆盖的
-- 不重复（见 V1~V8），已有表注释的四张表也不覆写。

-- ============================================================
-- 1. 用户与设备
-- ============================================================

COMMENT ON TABLE users IS
    '用户主体。被 14 处外键引用，因此注销走匿名化而非物理删除：'
    'status 置 DELETED、可识别信息清空，但结构性引用保留，'
    '否则账务流水会被连带删除、群聊历史消息会失去发送者。见 AccountDeletionService。';

COMMENT ON TABLE devices IS
    '设备会话。一台设备一行，承载刷新令牌轮换与远程下线。'
    '令牌只存哈希；撤销是软删（revoked_at 置位）而非删行——令牌重放检测'
    '需要已撤销设备仍然可查。';

-- ============================================================
-- 2. 会话与消息
-- ============================================================

COMMENT ON TABLE conversations IS
    '会话（单聊/群聊）。next_seq 是会话内消息序号的唯一来源，'
    '写消息时在事务内锁定本行后自增——序号的连续性由行锁而非应用层保证。';

COMMENT ON TABLE direct_conversation_keys IS
    '单聊去重键。两个用户 ID 按字典序规范化为 (low, high) 后作主键，'
    '使"相同两人只能有一个单聊"成为数据库约束而不是应用层的先查后写——'
    '后者在并发下会创建出两个单聊。';

COMMENT ON TABLE conversation_members IS
    '会话成员关系。退群是软删（left_at 置位）而非删行：read_seq / join_seq '
    '要保留到重新入群时按当时序号重置，使退群期间的消息同样不可见；'
    '软删还保留了"这个人曾经在群里"的事实，成员变更审计需要它。';

COMMENT ON TABLE messages IS
    '消息事实。只追加或改 status（RECALLED / DELETED），不物理删除。'
    '(conversation_id, seq) 唯一保证会话内有序不重，'
    '(sender_id, client_message_id) 唯一提供发送幂等——重试依赖约束去重而非先查后写。'
    '刻意不按时间分区，理由见 V7 的迁移注释。';

-- ============================================================
-- 3. 通话
-- ============================================================

COMMENT ON TABLE call_rooms IS
    '通话房间与生命周期。只记录元数据（媒体类型、拓扑、起止时间、结束原因）；'
    'SDP/ICE 等信令内容不落库，它们只在信令通道中传递。';

COMMENT ON TABLE call_participants IS
    '通话参与者状态。记录每个被邀请者 invited → ringing → joined / rejected / left / missed '
    '的流转，是通话记录展示与忙线判定的依据。';

-- ============================================================
-- 4. 钱包与红包（复式记账）
-- ============================================================

COMMENT ON TABLE wallet_accounts IS
    '账户余额。红包资金在"已发出未领取"期间挂在 SYSTEM 托管账户上，'
    '使任意时刻全部账户余额之和恒定、可对账。余额的每一次变动都必须有'
    '对应的 wallet_entries 流水，不存在只改余额不记账的写入路径。';

COMMENT ON TABLE wallet_transactions IS
    '账务交易。一笔业务一行，其下的 wallet_entries 借贷合计必须相等。'
    'idempotency_key 的唯一约束是重试去重的唯一依据——'
    '重复请求会撞约束失败，而不是产生第二笔资金变动。';

COMMENT ON TABLE red_packets IS
    '红包主体。claimed_amount / claimed_count 是领取的聚合状态，'
    '明细在 red_packet_shares 与 red_packet_claims，三者必须一致。'
    '过期未领由 Worker 按 expires_at 扫描并退款给发送者。';

COMMENT ON TABLE red_packet_shares IS
    '预分配份额。创建红包时一次性分完金额，领取只是把某一份标记为已领，'
    '使领取事务足够短。并发领取用 FOR UPDATE SKIP LOCKED 抢占未领份额，'
    '避免同一红包的领取请求相互阻塞。';

COMMENT ON TABLE red_packet_claims IS
    '领取凭证。(red_packet_id, user_id) 唯一是防并发套利的最后一道防线，'
    '即使应用层判断失效也会被数据库拒绝。属账务凭证，账号注销时保留。';

-- ============================================================
-- 5. 可靠事件
-- ============================================================

COMMENT ON TABLE outbox_events IS
    '事务性发件箱。领域变更与事件写入同一事务，由 Worker 轮询发布，'
    '保证至少一次投递——因此消费方必须幂等。'
    'attempts / next_attempt_at 支撑失败退避重试，last_error 留给排查。';

-- ============================================================
-- 6. audit_logs 的分区子表
-- ============================================================
--
-- 父表 audit_logs 的注释在 V7 已有，但 PostgreSQL 的分区子表**不继承**父表注释，
-- 因此每个分区都是空的。分区由 ensure_audit_log_partition 按月动态创建，
-- 只回填现有分区解决不了问题——下个月新建的分区又会没有注释。
-- 所以两件事一起做：改函数（管未来）+ 回填（管现在）。

COMMENT ON TABLE audit_logs_default IS
    'audit_logs 的兜底分区，正常应当为空。'
    '出现数据说明对应月份的分区漏建（见 ensure_audit_log_partition），'
    '此时写入虽未失败但分区裁剪已失效，需要排查而不是放任。';

-- 在原函数基础上只增加一句 COMMENT，其余逻辑与 V7 一致。
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

    -- 分区自带说明，使 \dt+ 能直接看出这张表是什么、谁在管它的生命周期
    EXECUTE format(
        'COMMENT ON TABLE %I IS %L',
        part_name,
        'audit_logs 的 ' || to_char(start_at, 'YYYY-MM') || ' 月分区。'
        || '由 ensure_audit_log_partition 创建，超出保留期后由 '
        || 'drop_audit_log_partitions_before 整表 DROP。'
    );

    RETURN part_name || ' (已创建)';
END;
$$;

-- 回填迁移前已存在的月分区
DO $$
DECLARE
    part_name text;
BEGIN
    FOR part_name IN
        SELECT c.relname
          FROM pg_class c
          JOIN pg_inherits i ON i.inhrelid = c.oid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.relname = 'audit_logs'
           AND c.relname ~ '^audit_logs_[0-9]{4}_[0-9]{2}$'
           AND obj_description(c.oid, 'pg_class') IS NULL
    LOOP
        EXECUTE format(
            'COMMENT ON TABLE %I IS %L',
            part_name,
            'audit_logs 的 ' || replace(substring(part_name from 12), '_', '-') || ' 月分区。'
            || '由 ensure_audit_log_partition 创建，超出保留期后由 '
            || 'drop_audit_log_partitions_before 整表 DROP。'
        );
    END LOOP;
END $$;

-- ============================================================
-- 7. 覆盖度断言
-- ============================================================
--
-- 断言的是"执行到这一步时没有任何表被漏掉"，包括本次动态回填的分区——
-- 一条 COMMENT 写错了表名、或者某张表在梳理时被忘了，都会让迁移直接失败，
-- 而不是留下一个要等到有人翻数据字典才会发现的缺口。
--
-- 它**管不到未来**：V10 建了没注释的新表，这里已经执行过了，不会再跑。
-- 让新表带上注释是建表迁移自己的责任，需要长期防线的话应当加 CI 检查。
--
-- 排除 flyway_schema_history：它由 Flyway 自己创建和维护，
-- repair/baseline 等操作会重建它，注释不会被保留，写了也留不住。
DO $$
DECLARE
    missing text;
BEGIN
    SELECT string_agg(c.relname, ', ' ORDER BY c.relname)
      INTO missing
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'public'
       AND c.relkind IN ('r', 'p')
       AND c.relname <> 'flyway_schema_history'
       AND obj_description(c.oid, 'pg_class') IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '以下表缺少 COMMENT ON TABLE：%', missing;
    END IF;
END $$;
