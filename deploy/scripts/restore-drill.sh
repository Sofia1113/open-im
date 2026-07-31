#!/usr/bin/env bash
#
# 恢复演练：把最近一次备份恢复到一个临时数据库，并验证数据可用。
#
# 用法：
#   ./deploy/scripts/restore-drill.sh                    # 用最近一次备份
#   ./deploy/scripts/restore-drill.sh openim-2026...dump # 指定备份文件
#
# ── 为什么演练必须包含"验证"这一步 ────────────────────────────
#
# "pg_restore 返回 0" 只证明文件能解析，不证明数据是对的。一个只备份了 schema
# 而没有数据的 dump、一个在事务中途快照的不一致 dump，都能恢复成功。
# 因此这个脚本在恢复之后还要跑一组不变量检查——尤其是账务平衡，
# 那是这个系统里唯一"错了必须立刻知道"的数据。
#
# 演练在本地 Docker 里进行，不碰任何生产资源。
#
# 宿主端口分配见下方 DRILL_PORT 处的注释——本仓库有多个 profile 会占用端口，
# 演练端口必须与它们错开。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

CONTAINER="openim-restore-drill"

# ── 本仓库的宿主端口分配 ────────────────────────────────────
#   5432  / 6379   开发用 PostgreSQL / Redis（docker-compose 默认 profile）
#   55432 / 56379  E2E 专用（docker-compose 的 e2e profile）
#   55433          恢复演练（本脚本）
#   3001  / 3002   服务端实例 A / B（multi profile）
#
# 演练端口刻意避开 55432：那个已经分配给 e2e profile 的 postgres。
# 第一版用了 55432，在 e2e 容器运行时演练直接失败（port is already allocated）。
DRILL_PORT="${DRILL_PORT:-55433}"
DRILL_PASSWORD="drill-only-not-a-secret"
PG_IMAGE="postgres:17-alpine"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'

log()  { printf '%s==>%s %s\n' "$BOLD" "$RESET" "$*"; }
ok()   { printf '%s  ✓%s %s\n' "$GREEN" "$RESET" "$*"; }
warn() { printf '%s  !%s %s\n' "$YELLOW" "$RESET" "$*"; }
fail() { printf '%s  ✗%s %s\n' "$RED" "$RESET" "$*" >&2; }

cleanup() {
  if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    log "清理演练容器"
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# ── 1. 取得备份文件 ──────────────────────────────────────────

DUMP_FILE="${1:-}"

if [ -z "$DUMP_FILE" ]; then
  log "未指定备份文件，从对象存储拉取最近一次备份"
  : "${S3_ENDPOINT:?需要设置 S3_ENDPOINT}"
  : "${S3_BUCKET:?需要设置 S3_BUCKET}"
  : "${S3_ACCESS_KEY:?需要设置 S3_ACCESS_KEY}"
  : "${S3_SECRET_KEY:?需要设置 S3_SECRET_KEY}"

  if ! command -v mc >/dev/null 2>&1; then
    fail "需要 MinIO client（mc）。安装：brew install minio/stable/mc"
    exit 1
  fi

  mc alias set drill "$S3_ENDPOINT" "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null
  LATEST=$(mc ls "drill/${S3_BUCKET}/daily/" | sort -k1,2 | tail -1 | awk '{print $NF}')
  if [ -z "$LATEST" ]; then
    fail "对象存储中没有任何备份"
    exit 1
  fi
  DUMP_FILE="/tmp/${LATEST}"
  log "下载 ${LATEST}"
  mc cp "drill/${S3_BUCKET}/daily/${LATEST}" "$DUMP_FILE"
fi

if [ ! -f "$DUMP_FILE" ]; then
  fail "备份文件不存在：$DUMP_FILE"
  exit 1
fi

DUMP_SIZE=$(wc -c < "$DUMP_FILE" | tr -d ' ')
DUMP_AGE_NOTE=""
if command -v stat >/dev/null 2>&1; then
  # 备份年龄是恢复演练的关键信息：能恢复但数据是两周前的，RPO 就没达标
  MODIFIED=$(stat -f %Sm -t '%Y-%m-%d %H:%M' "$DUMP_FILE" 2>/dev/null \
             || stat -c '%y' "$DUMP_FILE" 2>/dev/null | cut -d. -f1)
  DUMP_AGE_NOTE=" (生成于 $MODIFIED)"
fi
ok "备份文件：$DUMP_FILE — ${DUMP_SIZE} 字节${DUMP_AGE_NOTE}"

# ── 2. 启动干净的目标数据库 ──────────────────────────────────

cleanup
log "启动演练数据库（端口 ${DRILL_PORT}）"
docker run -d --name "$CONTAINER" \
  -e POSTGRES_PASSWORD="$DRILL_PASSWORD" \
  -e POSTGRES_USER=openim \
  -e POSTGRES_DB=openim \
  -p "${DRILL_PORT}:5432" \
  "$PG_IMAGE" >/dev/null

log "等待数据库就绪"
for i in $(seq 1 30); do
  if docker exec "$CONTAINER" pg_isready -U openim -d openim >/dev/null 2>&1; then
    ok "数据库就绪（${i} 秒）"
    break
  fi
  if [ "$i" -eq 30 ]; then
    fail "数据库 30 秒内未就绪"
    exit 1
  fi
  sleep 1
done

# ── 3. 恢复 ─────────────────────────────────────────────────

log "执行恢复"
RESTORE_START=$(date +%s)
docker cp "$DUMP_FILE" "$CONTAINER:/tmp/restore.dump"

# --exit-on-error 让恢复在第一个错误处停下。默认行为是继续，
# 那会产生一个"部分恢复"的数据库并返回 0——比直接失败更危险。
if docker exec "$CONTAINER" pg_restore \
      -U openim -d openim --no-owner --no-privileges \
      --exit-on-error /tmp/restore.dump 2>/tmp/restore-errors.log; then
  RESTORE_SECONDS=$(( $(date +%s) - RESTORE_START ))
  ok "恢复完成，耗时 ${RESTORE_SECONDS} 秒"
else
  fail "恢复失败："
  cat /tmp/restore-errors.log >&2
  exit 1
fi

# ── 4. 验证不变量 ───────────────────────────────────────────
#
# 恢复成功不等于数据可用。这一组检查回答的是"这份备份真的能用来重建服务吗"。

psql_query() {
  docker exec "$CONTAINER" psql -U openim -d openim -tAc "$1"
}

FAILURES=0
check() {
  local label="$1" actual="$2" expected="$3"
  if [ "$actual" = "$expected" ]; then
    ok "${label}：$actual"
  else
    fail "${label}：期望 ${expected}，实际 $actual"
    FAILURES=$((FAILURES + 1))
  fi
}

log "验证数据完整性"

TABLE_COUNT=$(psql_query "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
if [ "$TABLE_COUNT" -ge 15 ]; then
  ok "表数量：$TABLE_COUNT"
else
  fail "表数量只有 ${TABLE_COUNT}，schema 不完整"
  FAILURES=$((FAILURES + 1))
fi

# ── 迁移历史必须完整 ──
#
# 这是一条**断言**而不只是信息展示：备份若不含 flyway_schema_history，
# 恢复出来的数据库对 Flyway 来说是一个"已有表但无迁移记录"的库，
# 下次启动时它会尝试从 V1 开始重新应用全部迁移并立即失败
# （表已存在）。那样的备份能通过 pg_restore，却不能用来重建服务。
#
# 注意查询里的 `version IS NOT NULL`：可重复迁移（R__demo_seed）的 version 是
# NULL 且 installed_rank 最大，不加这个条件会取到它并显示"无版本"——
# 脚本第一版就是这样，看起来像"备份丢了迁移历史"，实际是查询写错了。
MIGRATION_COUNT=$(psql_query "
  SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")
if [ "${MIGRATION_COUNT:-0}" -ge 1 ]; then
  ok "迁移历史完整：${MIGRATION_COUNT} 条已成功应用的版本化迁移"
else
  fail "备份中没有迁移历史 —— 恢复后 Flyway 会尝试重新应用全部迁移并失败"
  FAILURES=$((FAILURES + 1))
fi

SCHEMA_VERSION=$(psql_query "
  SELECT version FROM flyway_schema_history
   WHERE success AND version IS NOT NULL
   ORDER BY installed_rank DESC LIMIT 1")
ok "Flyway 最新版本：${SCHEMA_VERSION:-无}"

# 失败的迁移不该出现在备份里——它意味着备份取自一个 schema 不一致的库
FAILED_MIGRATIONS=$(psql_query "
  SELECT count(*) FROM flyway_schema_history WHERE NOT success")
check "失败的迁移记录数" "$FAILED_MIGRATIONS" "0"

USER_COUNT=$(psql_query "SELECT count(*) FROM users")
ok "用户数：$USER_COUNT"

MESSAGE_COUNT=$(psql_query "SELECT count(*) FROM messages")
ok "消息数：$MESSAGE_COUNT"

# ── 账务不变量：这个系统里唯一"错了必须立刻知道"的数据 ──
#
# 复式记账要求每笔交易借贷相等，且所有账户余额之和恒定。
# 备份如果是在事务中途拍的快照，这两条会不成立——而这正是
# "备份看起来成功但实际不可用"最隐蔽的形式。

log "验证账务不变量"

# 借贷平衡必须按 direction 分别求和后比较。
# wallet_entries.amount 有 CHECK (amount > 0) 约束，方向由 direction 列表示——
# 用 sum(amount) <> 0 判断是错的，它对任何一份正确的备份都会报失败。
# 这条查询与生产代码的 WalletRepository.findUnbalancedTransactions 保持一致。
UNBALANCED=$(psql_query "
  SELECT count(*) FROM (
    SELECT t.id
      FROM wallet_transactions t
      JOIN wallet_entries e ON e.transaction_id = t.id
     GROUP BY t.id
    HAVING sum(CASE WHEN e.direction = 'DEBIT'  THEN e.amount ELSE 0 END)
        <> sum(CASE WHEN e.direction = 'CREDIT' THEN e.amount ELSE 0 END)
  ) t")
check "借贷不平衡的交易数" "$UNBALANCED" "0"

ORPHAN_ENTRIES=$(psql_query "
  SELECT count(*) FROM wallet_entries e
   WHERE NOT EXISTS (SELECT 1 FROM wallet_transactions t WHERE t.id = e.transaction_id)")
check "孤立流水条目" "$ORPHAN_ENTRIES" "0"

ORPHAN_MESSAGES=$(psql_query "
  SELECT count(*) FROM messages m
   WHERE NOT EXISTS (SELECT 1 FROM conversations c WHERE c.id = m.conversation_id)")
check "孤立消息（会话不存在）" "$ORPHAN_MESSAGES" "0"

# 每条分录的 balance_after 必须与该账户当前余额一致（取最后一条）。
# 这比"总额等于某个固定值"更有意义——总额会随业务变化，
# 而"流水的最终余额与账户余额一致"是一个恒为真的不变量。
BALANCE_DRIFT=$(psql_query "
  SELECT count(*) FROM wallet_accounts a
   WHERE EXISTS (SELECT 1 FROM wallet_entries e WHERE e.account_id = a.id)
     AND a.available_balance <> (
           SELECT e.balance_after
             FROM wallet_entries e
            WHERE e.account_id = a.id
            ORDER BY e.created_at DESC, e.id DESC
            LIMIT 1
         )")
check "账户余额与最新流水不一致的账户数" "$BALANCE_DRIFT" "0"

TOTAL_BALANCE=$(psql_query "
  SELECT coalesce(sum(available_balance + frozen_balance), 0) FROM wallet_accounts")
ok "全部账户余额之和：${TOTAL_BALANCE}（应与备份时刻的业务总额一致）"

# 红包份额与领取记录必须对得上
CLAIM_MISMATCH=$(psql_query "
  SELECT count(*) FROM red_packets p
   WHERE p.claimed_count <> (
     SELECT count(*) FROM red_packet_claims c WHERE c.red_packet_id = p.id
   )")
check "红包领取计数与流水不一致的记录" "$CLAIM_MISMATCH" "0"

# ── 5. 结果 ─────────────────────────────────────────────────

echo
if [ "$FAILURES" -eq 0 ]; then
  printf '%s演练通过%s — 备份可恢复且数据一致\n' "$GREEN$BOLD" "$RESET"
  echo
  echo "请把结果记入 deploy/observability/BACKUP.md 的演练记录表："
  echo "  日期：$(date -u +%Y-%m-%d)"
  echo "  备份文件：$(basename "$DUMP_FILE")"
  echo "  恢复耗时：${RESTORE_SECONDS} 秒"
  echo "  数据量：${USER_COUNT} 用户 / ${MESSAGE_COUNT} 消息"
  exit 0
else
  printf '%s演练失败%s — %d 项检查未通过\n' "$RED$BOLD" "$RESET" "$FAILURES"
  echo
  echo "这份备份不能用于恢复。排查方向："
  echo "  * 账务不平衡 → 备份可能在事务中途拍摄，检查 pg_dump 是否使用了一致性快照"
  echo "  * 表数量不足 → schema 未完整导出，检查 pg_dump 的权限与参数"
  echo "  * 孤立记录   → 备份期间有并发写入且外键约束未生效"
  exit 1
fi
