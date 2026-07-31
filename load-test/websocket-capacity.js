// WebSocket 长连接容量压测
//
// 运行：
//   docker compose --profile full up -d --build
//   k6 run load-test/websocket-capacity.js
//   k6 run -e CONNECTIONS=2000 -e HOLD=5m load-test/websocket-capacity.js
//
// ── 这个脚本回答什么问题 ────────────────────────────────────
//
// `openim.realtime.max-connections` 的默认值 10000 是一个**猜测**——
// 它按"每条连接的常驻成本很小"估算，但从未被验证。这个脚本用来把猜测
// 变成一个测出来的数字。
//
// 具体要回答：
//   1. 单实例在 1Gi 内存上限下实际能承载多少条连接？
//   2. 每条连接的边际内存成本是多少？（连接数 → 堆占用的斜率）
//   3. 心跳机制在高连接数下的开销——每 30 秒遍历全部连接发 ping，
//      连接数上万时这一轮要多久？
//   4. 达到上限后拒绝新连接的行为是否如预期（1013 而非崩溃）？
//
// ── 观察什么 ────────────────────────────────────────────────
//
// 关键是**同时**看 k6 的连接数与服务端的内存曲线：
//   * openim_realtime_connections —— 应当与 k6 建立的连接数一致，
//     持续低于说明有连接被静默拒绝或断开
//   * jvm_memory_used_bytes{area="heap"} —— 与连接数的比值就是边际成本
//   * openim_realtime_connections_rejected_total —— 达到上限的证据
//   * 心跳日志的间隔 —— 明显超过 30 秒说明心跳循环本身成了瓶颈
//
// ── 已知限制 ────────────────────────────────────────────────
//
// k6 的每个 VU 持有一条连接，因此 CONNECTIONS 上限受 k6 自身的资源约束。
// 要压到几万条连接需要多台施压机（k6 分布式模式）或调高系统的 fd 上限：
//   ulimit -n 65536

import http from "k6/http";
import ws from "k6/ws";
import { check, fail, sleep } from "k6";
import { Counter, Gauge, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://127.0.0.1:3001";
const WS_URL = __ENV.WS_URL || BASE_URL.replace(/^http/, "ws") + "/ws";
const CONNECTIONS = Number(__ENV.CONNECTIONS || 200);
const HOLD = __ENV.HOLD || "2m";

const connectionsOpened = new Counter("ws_connections_opened");
const connectionsRejected = new Counter("ws_connections_rejected");
const connectionsFailed = new Counter("ws_connections_failed");
const handshakeLatency = new Trend("ws_handshake_latency", true);
const messagesReceived = new Counter("ws_messages_received");
const pingsReceived = new Counter("ws_pings_received");
const activeConnections = new Gauge("ws_active_connections");

export const options = {
  scenarios: {
    // 逐步建立连接并保持。
    // 不用 constant-vus：那会让全部连接在同一瞬间建立，
    // 测出来的是"瞬时握手能力"而不是"稳态承载能力"，
    // 而后者才是容量规划需要的数字。
    hold_connections: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        // 缓慢爬升，让服务端有机会体现出真实的资源曲线
        { duration: "1m", target: Math.ceil(CONNECTIONS * 0.5) },
        { duration: "1m", target: CONNECTIONS },
        // 保持——内存泄漏与心跳开销只在长时间保持下才显现
        { duration: HOLD, target: CONNECTIONS },
      ],
      gracefulRampDown: "30s",
    },
  },
  thresholds: {
    // 握手延迟：连接建立要走鉴权（查一次会话有效性缓存）。
    // 明显上升说明鉴权路径在高并发下退化
    ws_handshake_latency: ["p(95)<2000"],
    // 失败（非拒绝）应当极少。区分"被拒绝"和"失败"很重要：
    // 前者是容量闸门正常工作，后者是 bug
    ws_connections_failed: ["count<10"],
  },
};

const USERS = ["alice", "bob", "carol"];

export function setup() {
  const sessions = {};
  for (const username of USERS) {
    const response = http.post(
      `${BASE_URL}/api/v1/auth/demo-login`,
      JSON.stringify({
        username,
        deviceId: `k6-ws-${username}`,
        deviceName: "k6 ws capacity",
      }),
      { headers: { "Content-Type": "application/json" } },
    );
    if (response.status !== 200) {
      fail(`登录失败 ${username}: ${response.status} ${response.body}`);
    }
    sessions[username] = response.json().data.accessToken;
  }
  return { sessions };
}

export default function (data) {
  const username = USERS[__VU % USERS.length];
  const token = data.sessions[username];

  // 每个 VU 用不同的 deviceId：同一 userId + deviceId 会落进同一个
  // deviceSessions 桶，测不出真实的连接注册表规模
  const deviceId = `k6-ws-vu${__VU}`;
  const url = `${WS_URL}?token=${encodeURIComponent(token)}&deviceId=${deviceId}`;

  const start = Date.now();
  const response = ws.connect(url, {}, function (socket) {
    handshakeLatency.add(Date.now() - start);
    connectionsOpened.add(1);
    activeConnections.add(1);

    socket.on("message", function (raw) {
      messagesReceived.add(1);
      // 服务端的应用层心跳是 WebSocket ping 帧，k6 单独有 on("ping")；
      // 这里统计的是业务消息
      try {
        const parsed = JSON.parse(raw);
        check(parsed, {
          "消息带 kind 字段": (m) => typeof m.kind === "string",
        });
      } catch {
        // 非 JSON 帧不该出现，但不因此中断连接
      }
    });

    // 响应服务端心跳。不回 pong 的话会在 pong-timeout-millis（默认 90 秒）
    // 后被清理，那样就测不到长时间保持
    socket.on("ping", function () {
      pingsReceived.add(1);
      socket.pong();
    });

    socket.on("close", function () {
      activeConnections.add(-1);
    });

    socket.on("error", function (error) {
      // 1013 SERVICE_OVERLOAD 是容量闸门，不是错误
      if (String(error).includes("1013")) {
        connectionsRejected.add(1);
      } else {
        connectionsFailed.add(1);
      }
    });

    // 保持连接直到本轮结束。
    // setTimeout 而不是 sleep：sleep 会阻塞 VU 的事件循环，
    // 收不到 ping 也就无法回 pong
    socket.setTimeout(function () {
      socket.close();
    }, parseDuration(HOLD));
  });

  // 1013 会让 ws.connect 直接返回非 101 状态
  if (response && response.status !== 101) {
    if (response.status === 503 || response.status === 1013) {
      connectionsRejected.add(1);
    } else {
      connectionsFailed.add(1);
    }
  }

  // VU 之间稍作错开，避免全部同时重连造成的锯齿
  sleep(1);
}

/** 把 "2m" / "90s" 这类时长转成毫秒 */
function parseDuration(value) {
  const match = /^(\d+)([smh])$/.exec(value);
  if (!match) return 120_000;
  const amount = Number(match[1]);
  return amount * { s: 1_000, m: 60_000, h: 3_600_000 }[match[2]];
}

export function teardown() {
  console.log(
    "容量压测结束。把以下数字记入 docs/03-delivery/CAPACITY.md：\n" +
      "  * 稳定承载的连接数（openim_realtime_connections 的平台期）\n" +
      "  * 该连接数下的堆占用（jvm_memory_used_bytes{area=\"heap\"}）\n" +
      "  * 每条连接的边际内存成本 = 堆增量 / 连接增量\n" +
      "  * 心跳一轮的耗时（服务端日志中相邻心跳的间隔）",
  );
}
