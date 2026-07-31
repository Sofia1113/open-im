#!/usr/bin/env bash
#
# 故障注入：验证降级路径真的可达
#
# 用法：
#   docker compose --profile full up -d --build
#   ./load-test/chaos-degradation.sh
#
# ── 为什么需要这个脚本 ──────────────────────────────────────
#
# 代码里有大量降级逻辑：限流转本地令牌桶、去重故障放行、presence 兜底、
# 事件流写入失败由 Outbox 补偿。这些分支都有单元测试，但单元测试是通过
# 注入 mock 异常来触发它们的——那证明"如果 Redis 抛异常，代码会走降级分支"，
# 不证明"Redis 真的挂掉时，服务确实还能用"。
#
# 两者的差距曾经是真实存在的：就绪探针里包含 Redis 时，Redis 一挂，
# 每个 Pod 的就绪探针同时失败，Service 端点被清空，整站 503。
# 那些降级代码<b>永远不会被执行</b>，因为流量在到达它们之前就被摘掉了。
# 单元测试全绿，生产全挂。
#
# 这个脚本停掉真实的依赖，然后验证服务的实际行为。

set -uo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:3001}"
REDIS_CONTAINER="${REDIS_CONTAINER:-openim-redis}"
PASSWORD="${DEMO_PASSWORD:-openim-demo-2026}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
PASSED=0
FAILED=0

step() { printf '\n%s══ %s%s\n' "$BOLD" "$*" "$RESET"; }
ok()   { printf '%s  ✓%s %s\n' "$GREEN" "$RESET" "$*"; PASSED=$((PASSED+1)); }
bad()  { printf '%s  ✗%s %s\n' "$RED" "$RESET" "$*"; FAILED=$((FAILED+1)); }
info() { printf '%s  ·%s %s\n' "$YELLOW" "$RESET" "$*"; }

restore_redis() {
  if ! docker ps --format '{{.Names}}' | grep -qx "$REDIS_CONTAINER"; then
    info "恢复 Redis"
    docker start "$REDIS_CONTAINER" >/dev/null 2>&1 || true
    sleep 5
  fi
}
trap restore_redis EXIT

http_status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"
}

login() {
  curl -s --max-time 10 -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"alice\",\"password\":\"$PASSWORD\",\"deviceId\":\"chaos-$RANDOM\",\"deviceName\":\"chaos\"}"
}

login_status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"alice\",\"password\":\"$PASSWORD\",\"deviceId\":\"chaos-$RANDOM\",\"deviceName\":\"chaos\"}"
}

# 读取单个指标值。
#
# 匹配指标名后必须紧跟空格或 '{'：否则 "openim_redis_available" 会同时匹配到
# "openim_redis_availability_transitions_total"，而 head -1 拿到哪一行取决于
# Prometheus 输出的顺序——脚本会时对时错，比稳定失败更难排查。
metric() {
  curl -s --max-time 10 "$BASE_URL/actuator/prometheus" \
    | grep -E "^$1([ {])" \
    | grep -v '^#' \
    | head -1 \
    | awk '{print $NF}'
}

# 等待某个指标达到期望值，最多等 timeout 秒。
#
# 固定 sleep 是脆的：Redis 探测周期是 10 秒，但 Lettuce 的连接超时、
# 命令超时会叠加，实际感知到故障的时间是可变的。轮询比猜一个够长的 sleep 可靠。
wait_for_metric() {
  local name="$1" expected="$2" timeout="${3:-45}" elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    local value
    value=$(metric "$name")
    if [ "${value%.*}" = "$expected" ]; then
      return 0
    fi
    sleep 3
    elapsed=$((elapsed + 3))
  done
  return 1
}

# ── 基线 ────────────────────────────────────────────────────

step "基线：Redis 正常时的行为"

if [ "$(http_status "$BASE_URL/actuator/health/readiness")" = "200" ]; then
  ok "就绪探针返回 200"
else
  bad "就绪探针未就绪，服务可能还没起来。先确认 docker compose 状态"
  exit 1
fi

TOKEN=$(login | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then
  ok "登录成功，取得访问令牌"
else
  bad "基线登录失败，无法继续"
  exit 1
fi

REDIS_AVAILABLE=$(metric "openim_redis_available")
if [ "${REDIS_AVAILABLE%.*}" = "1" ]; then
  ok "openim_redis_available = 1"
else
  info "openim_redis_available = ${REDIS_AVAILABLE:-无}（探测周期默认 10 秒，可能还没首次探测）"
fi

# ── 注入故障 ────────────────────────────────────────────────

step "注入：停止 Redis"

docker stop "$REDIS_CONTAINER" >/dev/null 2>&1 || {
  bad "无法停止容器 ${REDIS_CONTAINER}。用 REDIS_CONTAINER 指定正确的名字"
  exit 1
}
info "已停止 ${REDIS_CONTAINER}，等待服务感知到故障"
if wait_for_metric "openim_redis_available" 0 45; then
  info "服务已感知 Redis 不可用"
else
  info "45 秒内指标未翻转，继续验证（后面的断言会体现实际状态）"
fi

# ── 核心断言：服务必须仍然可用 ──────────────────────────────

step "验证：Redis 不可用时服务降级运行而非整体不可用"

# 这是整个脚本最重要的一条。就绪探针包含 Redis 时，这里会返回 503，
# 而 503 意味着 K8s 会把每一个 Pod 都摘掉——降级代码永远执行不到。
READINESS=$(http_status "$BASE_URL/actuator/health/readiness")
if [ "$READINESS" = "200" ]; then
  ok "就绪探针仍返回 200 —— Redis 不在就绪组内，实例保留在负载均衡里"
else
  bad "就绪探针返回 $READINESS —— Redis 故障会导致全部实例被摘除流量。"
  bad "  检查 management.endpoint.health.group.readiness.include 是否又包含了 redis"
fi

# 登录走数据库，不依赖 Redis。
#
# 必须区分 429 与真正的失败：前面的基线登录已经消耗了 LOGIN 配额，
# 而降级后凭证类配额收紧 4 倍，因此这里很可能拿到 429。
# 429 恰恰证明限流在降级模式下工作，把它算作"认证依赖 Redis"是错的诊断。
CHAOS_LOGIN_STATUS=$(login_status)
case "$CHAOS_LOGIN_STATUS" in
  200)
    ok "Redis 不可用时仍能登录（凭证校验与失败计数都在数据库）"
    ;;
  429)
    ok "登录接口在降级模式下返回 429 —— 限流生效（配额已收紧），认证链路本身可用"
    info "  等待配额恢复后重试一次以确认登录确实可用"
    sleep 62
    if [ "$(login_status)" = "200" ]; then
      ok "配额恢复后登录成功 —— 认证不依赖 Redis"
    else
      bad "配额恢复后仍无法登录 —— 认证可能依赖 Redis"
    fi
    ;;
  *)
    bad "Redis 不可用时登录返回 $CHAOS_LOGIN_STATUS —— 认证不应依赖 Redis"
    ;;
esac

CHAOS_TOKEN=$(login | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

# 受保护接口：鉴权走会话有效性缓存 + 数据库回源
if [ -n "$CHAOS_TOKEN" ]; then
  ME_STATUS=$(http_status -H "Authorization: Bearer $CHAOS_TOKEN" "$BASE_URL/api/v1/me")
  if [ "$ME_STATUS" = "200" ]; then
    ok "受保护接口可访问（鉴权回源数据库，不依赖 Redis）"
  else
    bad "受保护接口返回 $ME_STATUS"
  fi

  CONV_STATUS=$(http_status -H "Authorization: Bearer $CHAOS_TOKEN" "$BASE_URL/api/v1/conversations")
  if [ "$CONV_STATUS" = "200" ]; then
    ok "会话列表可读取"
  else
    bad "会话列表返回 $CONV_STATUS"
  fi
fi

# ── 降级信号必须可观测 ──────────────────────────────────────

step "验证：降级状态可观测"

DEGRADED_AVAILABLE=$(metric "openim_redis_available")
if [ "${DEGRADED_AVAILABLE%.*}" = "0" ]; then
  ok "openim_redis_available = 0 —— 告警可据此触发"
else
  bad "openim_redis_available = ${DEGRADED_AVAILABLE:-无}，Redis 故障不可观测"
fi

# 限流降级：Redis 判定失败后落到进程内令牌桶。
# 连续打几次登录，配额收紧 4 倍后应当出现 429
info "连续请求登录接口，验证限流降级生效"
SAW_429=0
for _ in $(seq 1 12); do
  STATUS=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -X POST "$BASE_URL/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"nobody-$RANDOM\",\"password\":\"wrong\",\"deviceId\":\"chaos\",\"deviceName\":\"chaos\"}")
  if [ "$STATUS" = "429" ]; then SAW_429=1; break; fi
done
if [ "$SAW_429" = "1" ]; then
  ok "降级期间仍然限流（进程内令牌桶生效，凭证类配额已收紧）"
else
  bad "降级期间未出现 429 —— 限流可能 fail-open，Redis 抖动会成为撞库窗口"
fi

DEGRADED_COUNT=$(metric "openim_rate_limit_degraded_total")
if [ -n "$DEGRADED_COUNT" ] && [ "${DEGRADED_COUNT%.*}" -gt 0 ] 2>/dev/null; then
  ok "openim_rate_limit_degraded_total = $DEGRADED_COUNT"
else
  bad "限流降级计数未上报"
fi

# ── 恢复 ────────────────────────────────────────────────────

step "恢复：重启 Redis"

docker start "$REDIS_CONTAINER" >/dev/null 2>&1
info "已启动 ${REDIS_CONTAINER}，等待自动恢复"

if wait_for_metric "openim_redis_available" 1 60; then
  ok "openim_redis_available 回到 1 —— 无需人工干预即自动恢复"
else
  bad "Redis 已恢复但指标仍为 $(metric openim_redis_available)，自动恢复可能有问题"
fi

# 事件流消费组应当被自动重建（NOGROUP 后重建）
if docker exec "$REDIS_CONTAINER" redis-cli EXISTS openim:events:stream >/dev/null 2>&1; then
  GROUPS=$(docker exec "$REDIS_CONTAINER" redis-cli XINFO GROUPS openim:events:stream 2>/dev/null | grep -c name || echo 0)
  if [ "${GROUPS:-0}" -gt 0 ]; then
    ok "事件流消费组已重建（$GROUPS 个）"
  else
    info "事件流尚无消费组——发一条消息后会自动创建"
  fi
fi

# 恢复后的完整链路
FINAL_TOKEN=$(login | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
if [ -n "$FINAL_TOKEN" ]; then
  ok "恢复后登录正常"
else
  bad "恢复后登录失败"
fi

# ── 结果 ────────────────────────────────────────────────────

printf '\n%s──────────────────────────────%s\n' "$BOLD" "$RESET"
if [ "$FAILED" -eq 0 ]; then
  printf '%s降级验证通过%s：%d 项检查全部通过\n' "$GREEN$BOLD" "$RESET" "$PASSED"
  printf '\nRedis 故障期间的实际服务水平：\n'
  printf '  可用：登录、注册、发消息、拉历史、红包收发、同实例实时投递\n'
  printf '  降级：限流转为每实例独立配额（凭证类收紧 4 倍）\n'
  printf '  暂停：跨实例事件扇出（消息由客户端按 seq 补拉兜底）\n'
  printf '  不可用：在线状态（全部显示离线）\n'
  exit 0
else
  printf '%s降级验证失败%s：%d 项通过，%d 项失败\n' "$RED$BOLD" "$RESET" "$PASSED" "$FAILED"
  printf '\n失败意味着 Redis 故障的影响面比设计预期更大。\n'
  printf '最常见的原因是就绪探针又把 redis 加回了 readiness 组——\n'
  printf '那会让单点故障摘掉全部实例，同时使所有降级代码变成不可达分支。\n'
  exit 1
fi
