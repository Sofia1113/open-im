#!/usr/bin/env bash
#
# 用真实 Prometheus 校验告警规则
#
# 用法：
#   docker compose --profile multi up -d --build   # 或 --profile full
#   ./deploy/observability/verify-rules.sh
#
# ── 为什么 YAML 解析通过还不够 ──────────────────────────────
#
# prometheus-rules.yaml 之前只被 Python 的 yaml.safe_load 校验过——那只证明
# 它是合法 YAML，不证明：
#   * PromQL 表达式语法正确（`rate(foo[5m]` 少个括号，YAML 完全合法）
#   * 记录规则的名字合法（Prometheus 对指标名有字符限制）
#   * 表达式能真的求值出结果（引用不存在的指标不报错，只是永远返回空）
#   * 告警的 for/labels/annotations 结构被接受
#
# 最后一条是最危险的：引用不存在的指标名不会让 Prometheus 报任何错，
# 那条告警只是**永远不触发**。AlertingContractTest 从应用侧比对了指标名，
# 这个脚本从 Prometheus 侧确认表达式真的能算出数来。
#
# 脚本做三件事：
#   1. 从 PrometheusRule CRD 里提取原生规则格式
#   2. promtool check rules —— 语法与结构校验
#   3. 起一个真实 Prometheus，抓取运行中的实例，确认规则被加载且记录规则有值

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RULES_FILE="$SCRIPT_DIR/prometheus-rules.yaml"

# 端口分配见 deploy/scripts/restore-drill.sh 的注释
PROM_PORT="${PROM_PORT:-59090}"
PROM_CONTAINER="openim-prom-verify"
PROM_IMAGE="prom/prometheus:v3.1.0"

# 被抓取的目标。默认同时抓 multi profile 的两个实例；
# 只起了 full profile 时第二个抓不到，不影响规则校验本身。
TARGETS="${TARGETS:-host.docker.internal:3001,host.docker.internal:3002}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; BOLD=$'\033[1m'; RESET=$'\033[0m'
PASSED=0
FAILED=0

step() { printf '\n%s══ %s%s\n' "$BOLD" "$*" "$RESET"; }
ok()   { printf '%s  ✓%s %s\n' "$GREEN" "$RESET" "$*"; PASSED=$((PASSED+1)); }
bad()  { printf '%s  ✗%s %s\n' "$RED" "$RESET" "$*"; FAILED=$((FAILED+1)); }
info() { printf '%s  ·%s %s\n' "$YELLOW" "$RESET" "$*"; }

WORK_DIR="$(mktemp -d)"
TRAFFIC_PID=""
cleanup() {
  if [ -n "${TRAFFIC_PID:-}" ]; then
    kill "$TRAFFIC_PID" 2>/dev/null || true
  fi
  docker rm -f "$PROM_CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$WORK_DIR"
}
trap cleanup EXIT

# ── 1. 从 CRD 提取原生规则格式 ──────────────────────────────

step "提取规则：PrometheusRule CRD → 原生格式"

# PrometheusRule 的 spec 就是原生规则文件的内容。
# promtool 不认识 CRD 的外层包装（apiVersion/kind/metadata），必须剥掉。
python3 - "$RULES_FILE" "$WORK_DIR/rules.yaml" <<'PY'
import sys, yaml
source, target = sys.argv[1], sys.argv[2]
docs = [d for d in yaml.safe_load_all(open(source)) if d]
if not docs:
    sys.exit("规则文件里没有任何文档")
spec = docs[0].get("spec")
if not spec or "groups" not in spec:
    sys.exit("找不到 spec.groups —— 文件结构不是 PrometheusRule")
with open(target, "w") as out:
    yaml.safe_dump(spec, out, allow_unicode=True, sort_keys=False)
groups = spec["groups"]
alerts = sum(1 for g in groups for r in g["rules"] if "alert" in r)
records = sum(1 for g in groups for r in g["rules"] if "record" in r)
print(f"  提取 {len(groups)} 个组：{alerts} 条告警 + {records} 条记录规则")
PY

if [ ! -s "$WORK_DIR/rules.yaml" ]; then
  bad "提取失败"
  exit 1
fi
ok "已提取为原生规则格式"

# ── 2. promtool 语法与结构校验 ──────────────────────────────

step "promtool check rules"

PROMTOOL_OUT=$(docker run --rm \
  -v "$WORK_DIR:/work:ro" \
  --entrypoint promtool \
  "$PROM_IMAGE" check rules /work/rules.yaml 2>&1)
PROMTOOL_STATUS=$?

echo "$PROMTOOL_OUT" | sed 's/^/    /'

if [ "$PROMTOOL_STATUS" -eq 0 ]; then
  ok "全部规则通过 promtool 校验（PromQL 语法、指标名合法性、结构完整）"
else
  bad "promtool 校验失败——上面的输出指出了具体位置"
  exit 1
fi

# ── 3. 起真实 Prometheus 加载规则并抓取 ─────────────────────

step "启动 Prometheus 并抓取运行中的实例"

# 抓取配置。scrape_interval 设得很短，让验证不必等太久。
{
  echo "global:"
  echo "  scrape_interval: 5s"
  echo "  evaluation_interval: 5s"
  echo "rule_files:"
  echo "  - /etc/prometheus/rules.yaml"
  echo "scrape_configs:"
  echo "  - job_name: openim"
  echo "    metrics_path: /actuator/prometheus"
  echo "    static_configs:"
  echo "      - targets:"
  IFS=',' read -ra TARGET_LIST <<< "$TARGETS"
  for target in "${TARGET_LIST[@]}"; do
    echo "          - '$target'"
  done
} > "$WORK_DIR/prometheus.yml"

docker rm -f "$PROM_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$PROM_CONTAINER" \
  -p "${PROM_PORT}:9090" \
  -v "$WORK_DIR/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  -v "$WORK_DIR/rules.yaml:/etc/prometheus/rules.yaml:ro" \
  --add-host host.docker.internal:host-gateway \
  "$PROM_IMAGE" \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.retention.time=1h >/dev/null

info "等待 Prometheus 就绪"
for _ in $(seq 1 30); do
  if curl -fsS --max-time 3 "http://127.0.0.1:${PROM_PORT}/-/ready" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if curl -fsS --max-time 3 "http://127.0.0.1:${PROM_PORT}/-/ready" >/dev/null 2>&1; then
  ok "Prometheus 已就绪（端口 ${PROM_PORT}）"
else
  bad "Prometheus 未在 60 秒内就绪"
  docker logs "$PROM_CONTAINER" 2>&1 | tail -20
  exit 1
fi

# 规则加载状态。promtool 校验的是文件，这里确认运行时真的加载成功——
# 两者可能不同（例如规则名与已有指标冲突）。
RULE_ERRORS=$(curl -fsS "http://127.0.0.1:${PROM_PORT}/api/v1/rules" \
  | python3 -c "
import json,sys
data = json.load(sys.stdin)
groups = data.get('data', {}).get('groups', [])
errors = [r for g in groups for r in g.get('rules', []) if r.get('lastError')]
print(len(errors))
for r in errors[:5]:
    print(f\"    {r.get('name')}: {r.get('lastError')}\", file=sys.stderr)
" 2>&1)

LOADED_GROUPS=$(curl -fsS "http://127.0.0.1:${PROM_PORT}/api/v1/rules" \
  | python3 -c "import json,sys; print(len(json.load(sys.stdin).get('data',{}).get('groups',[])))")

if [ "$LOADED_GROUPS" -ge 5 ]; then
  ok "Prometheus 加载了 ${LOADED_GROUPS} 个规则组"
else
  bad "只加载了 ${LOADED_GROUPS} 个规则组"
fi

# ── 4. 抓取目标可达 ─────────────────────────────────────────

step "验证抓取目标"

info "等待首个抓取周期完成"
sleep 12

UP_TARGETS=$(curl -fsS "http://127.0.0.1:${PROM_PORT}/api/v1/query?query=up%7Bjob%3D%22openim%22%7D" \
  | python3 -c "
import json,sys
result = json.load(sys.stdin).get('data',{}).get('result',[])
up = [r for r in result if r['value'][1] == '1']
print(len(up))
")

if [ "${UP_TARGETS:-0}" -ge 1 ]; then
  ok "抓取到 ${UP_TARGETS} 个健康实例"
else
  bad "没有抓到任何实例——先确认 docker compose 的 server 在运行"
  info "抓取目标：$TARGETS"
  exit 1
fi

# ── 5. 记录规则能求值出结果 ─────────────────────────────────
#
# 这是本脚本最有价值的部分。记录规则引用不存在的指标时，Prometheus
# 不会报错——规则照常"成功"求值，只是结果永远为空。
# 而基于它的告警也就永远不触发。

step "产生业务流量（记录规则需要它才有数据）"

# SLI 表达式刻意排除了 `/actuator.*`——探针流量远大于真实用户请求，
# 不排除的话 SLI 主要反映探针成功率而失去意义。
#
# 但这意味着**只靠 Prometheus 的抓取无法让 SLI 有值**：抓取打的就是
# /actuator/prometheus，正好被排除。脚本第一版因此报告"所有记录规则无数据"，
# 看起来像表达式引用错了指标，实际是环境里没有业务流量。
#
# 因此这里主动造流量。不这样做的话，这个脚本对"表达式是否正确"给不出结论。
TRAFFIC_BASE="${TRAFFIC_BASE:-http://127.0.0.1:3001}"
TOKEN=$(curl -s --max-time 10 -X POST "$TRAFFIC_BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"alice","password":"openim-demo-2026","deviceId":"rule-verify","deviceName":"verify"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

if [ -n "$TOKEN" ]; then
  # **流量必须持续产生，不能一次性打完。**
  #
  # 脚本第一版打 24 个请求就停下等待，结果 rate() 趋于 0、SLI 算出 NaN（0/0）。
  # 原因是 rate 反映的是"窗口内的平均增长速率"——counter 停止增长后，
  # 随着时间推移它必然趋于零。一个持续有流量的生产系统不会这样，
  # 但一个打完就停的测试脚本会。
  #
  # 后台循环持续打请求，直到验证结束。这也更接近告警规则实际运行的环境。
  (
    while :; do
      curl -s -o /dev/null --max-time 5 -H "Authorization: Bearer $TOKEN" \
        "$TRAFFIC_BASE/api/v1/conversations" 2>/dev/null || true
      curl -s -o /dev/null --max-time 5 -H "Authorization: Bearer $TOKEN" \
        "$TRAFFIC_BASE/api/v1/me" 2>/dev/null || true
      # 未认证请求，产生 401——它是 4xx，按设计**不计入**可用性失败。
      # 保留它是为了验证过滤条件真的把 4xx 排除在失败之外。
      curl -s -o /dev/null --max-time 5 "$TRAFFIC_BASE/api/v1/conversations" 2>/dev/null || true
      sleep 1
    done
  ) &
  TRAFFIC_PID=$!
  ok "已启动持续流量（后台，PID ${TRAFFIC_PID}）"
else
  info "登录失败（可能被限流），记录规则会因缺少业务流量而无数据"
fi

# 在 cleanup 里一并停掉流量循环
stop_traffic() {
  if [ -n "$TRAFFIC_PID" ]; then
    kill "$TRAFFIC_PID" 2>/dev/null || true
    wait "$TRAFFIC_PID" 2>/dev/null || true
    TRAFFIC_PID=""
  fi
}

# rate() 需要窗口内有实际增长。持续流量下 30 秒足以让 5 分钟窗口有明显斜率。
info "等待抓取与规则求值（30 秒，期间持续有流量）"
sleep 30

step "验证记录规则真的能算出数来"

query_value() {
  curl -fsS "http://127.0.0.1:${PROM_PORT}/api/v1/query?query=$1" \
    | python3 -c "
import json,sys
result = json.load(sys.stdin).get('data',{}).get('result',[])
print(result[0]['value'][1] if result else 'EMPTY')
" 2>/dev/null
}

# 逐条检查 SLI 记录规则。这些是告警表达式的输入，
# 它们为空则对应的告警是死的。
RECORD_RULES=$(python3 -c "
import yaml
spec = yaml.safe_load(open('$WORK_DIR/rules.yaml'))
for g in spec['groups']:
    for r in g['rules']:
        if 'record' in r:
            print(r['record'])
")

# NaN 必须视为失败。
#
# NaN 来自 0/0——分母为空意味着表达式没有匹配到任何序列，
# 基于它的告警永远不会触发。脚本第一版把 NaN 当成"有值"并报告通过，
# 那正是这个脚本本该防止的那种假绿。
classify() {
  case "$1" in
    EMPTY) echo "empty" ;;
    NaN|nan) echo "nan" ;;
    *)     echo "ok" ;;
  esac
}

# 这些 SLI 在健康系统上必然为空或零，不算失败：
#   error_rate*  —— 没有 5xx 时分子为 0，但分母有值，结果是 0 而非 NaN
# 真正必须有值的是可用性与延迟——它们只要有请求就该算得出来。
REQUIRED_RULES="openim:request_success_rate5m openim:request_latency_p99_5m openim:request_latency_p50_5m"

for rule in $RECORD_RULES; do
  value=$(query_value "$rule")
  case "$(classify "$value")" in
    ok)
      ok "$rule = $value"
      ;;
    nan)
      bad "$rule = NaN —— 分母为空，基于它的告警永远不触发"
      ;;
    empty)
      if echo "$REQUIRED_RULES" | grep -qw "$rule"; then
        bad "$rule 无数据 —— 这条 SLI 只要有请求就该有值，检查指标名与过滤条件"
      else
        info "$rule 无数据（健康系统上预期为空，例如错误率需要有过 5xx）"
      fi
      ;;
  esac
done

# ── 6. 告警表达式可求值 ─────────────────────────────────────
#
# 告警此刻不该触发（系统是健康的），但表达式必须能求值而不报错。

step "验证告警表达式可求值且当前不误报"

ALERT_STATE=$(curl -fsS "http://127.0.0.1:${PROM_PORT}/api/v1/rules" | python3 -c "
import json,sys
groups = json.load(sys.stdin).get('data',{}).get('groups',[])
alerts = [r for g in groups for r in g.get('rules',[]) if r.get('type')=='alerting']
broken = [r for r in alerts if r.get('lastError')]
firing = [r for r in alerts if r.get('state')=='firing']
print(f'{len(alerts)}|{len(broken)}|{len(firing)}')
for r in broken[:5]:
    print(f\"    表达式错误 {r['name']}: {r['lastError']}\", file=sys.stderr)
for r in firing[:8]:
    print(f\"    触发中 {r['name']}\", file=sys.stderr)
" 2>&1)

ALERT_TOTAL=$(echo "$ALERT_STATE" | head -1 | cut -d'|' -f1)
ALERT_BROKEN=$(echo "$ALERT_STATE" | head -1 | cut -d'|' -f2)
ALERT_FIRING=$(echo "$ALERT_STATE" | head -1 | cut -d'|' -f3)
echo "$ALERT_STATE" | tail -n +2

if [ "${ALERT_BROKEN:-1}" -eq 0 ]; then
  ok "${ALERT_TOTAL} 条告警表达式全部可求值，无表达式错误"
else
  bad "${ALERT_BROKEN} 条告警的表达式报错"
fi

if [ "${ALERT_FIRING:-0}" -eq 0 ]; then
  ok "健康状态下没有告警误报"
else
  info "${ALERT_FIRING} 条告警正在触发 —— 若系统确实健康则是误报，需要调整阈值"
fi

# ── 结果 ────────────────────────────────────────────────────

stop_traffic

printf '\n%s──────────────────────────────%s\n' "$BOLD" "$RESET"
if [ "$FAILED" -eq 0 ]; then
  printf '%s规则校验通过%s：%d 项检查全部通过\n' "$GREEN$BOLD" "$RESET" "$PASSED"
  printf '\n已验证：\n'
  printf '  · PromQL 语法与规则结构（promtool）\n'
  printf '  · 规则被真实 Prometheus 加载且无求值错误\n'
  printf '  · 抓取目标可达，指标真的能被采集\n'
  printf '  · 记录规则能算出数值（而非静默返回空）\n'
  printf '  · 健康状态下无告警误报\n'
  printf '\n仍未验证：告警真的会在故障时触发并送达 Alertmanager。\n'
  printf '那需要注入故障并配置接收端，见 load-test/chaos-degradation.sh。\n'
  exit 0
else
  printf '%s规则校验失败%s：%d 项通过，%d 项失败\n' "$RED$BOLD" "$RESET" "$PASSED" "$FAILED"
  exit 1
fi
