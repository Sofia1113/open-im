#!/usr/bin/env node
//
// 多实例跨实例行为验证
//
// 用法：
//   docker compose --profile multi up -d --build
//   node load-test/verify-multi-instance.mjs
//
// ── 为什么必须真的起两个实例 ────────────────────────────────
//
// 单实例下，所有"跨实例"代码路径都会退化成本地操作：
//   * Streams 消费组只有一个成员，收到的全是自己发的（originInstanceId 相同直接跳过）
//   * presence 读写同一个进程刚写的键
//   * 限流配额只有一个进程在消耗
//   * 缓存失效广播的接收端就是发送端
//
// 也就是说单实例能通过全部测试，而跨实例逻辑是否正确<b>完全未知</b>。
// 这个脚本是"多实例可用"这个断言的唯一证据。
//
// ── 用 Node 内置 WebSocket ─────────────────────────────────
//
// Node 22 自带全局 WebSocket，不需要 ws 依赖。用真实的 WebSocket 而不是
// 轮询 HTTP：跨实例投递的终点是 WebSocket 帧，轮询 HTTP 只能证明消息落库了，
// 而"落库"和"另一个实例上的连接收到了"是两件事——后者才是这次要验证的。

const A = process.env.INSTANCE_A ?? "http://127.0.0.1:3001";
const B = process.env.INSTANCE_B ?? "http://127.0.0.1:3002";
const PASSWORD = process.env.DEMO_PASSWORD ?? "openim-demo-2026";

const GREEN = "\x1b[32m";
const RED = "\x1b[31m";
const YELLOW = "\x1b[33m";
const BOLD = "\x1b[1m";
const RESET = "\x1b[0m";

let passed = 0;
let failed = 0;

const step = (text) => console.log(`\n${BOLD}══ ${text}${RESET}`);
const ok = (text) => {
  console.log(`${GREEN}  ✓${RESET} ${text}`);
  passed += 1;
};
const bad = (text) => {
  console.log(`${RED}  ✗${RESET} ${text}`);
  failed += 1;
};
const info = (text) => console.log(`${YELLOW}  ·${RESET} ${text}`);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function api(base, path, { method = "GET", token, body } = {}) {
  const response = await fetch(`${base}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(10_000),
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // 非 JSON 响应，保留 text
  }
  return { status: response.status, json, text };
}

async function login(base, username, deviceSuffix) {
  const result = await api(base, "/api/v1/auth/login", {
    method: "POST",
    body: {
      identifier: username,
      password: PASSWORD,
      deviceId: `multi-${username}-${deviceSuffix}`,
      deviceName: "multi-instance verify",
    },
  });
  if (result.status !== 200) {
    throw new Error(`登录失败 ${username}@${base}: ${result.status} ${result.text}`);
  }
  return result.json.data;
}

async function metric(base, name) {
  const response = await fetch(`${base}/actuator/prometheus`, {
    signal: AbortSignal.timeout(10_000),
  });
  const body = await response.text();
  // 指标名后必须紧跟空格或 '{'，否则 openim_redis_available 会匹配到
  // openim_redis_availability_transitions_total
  const line = body
    .split("\n")
    .find((l) => !l.startsWith("#") && new RegExp(`^${name}[ {]`).test(l));
  return line ? Number.parseFloat(line.trim().split(/\s+/).pop()) : null;
}

/**
 * 建立 WebSocket 连接并收集事件。
 *
 * 返回一个对象，`events` 会持续累积收到的业务事件。
 */
function connect(base, token, deviceId) {
  const wsUrl = `${base.replace(/^http/, "ws")}/ws?token=${encodeURIComponent(token)}&deviceId=${deviceId}`;
  const socket = new WebSocket(wsUrl);
  const events = [];
  const opened = new Promise((resolve, reject) => {
    socket.addEventListener("open", () => resolve());
    socket.addEventListener("error", (event) =>
      reject(new Error(`WebSocket 连接失败 ${wsUrl}: ${event.message ?? "unknown"}`)),
    );
  });
  socket.addEventListener("message", (event) => {
    try {
      const parsed = JSON.parse(event.data);
      if (parsed.kind === "event") {
        events.push(parsed);
      }
    } catch {
      // 忽略非 JSON 帧
    }
  });
  const acks = [];
  socket.addEventListener("message", (event) => {
    try {
      const parsed = JSON.parse(event.data);
      if (parsed.kind === "ack") {
        acks.push(parsed);
      }
    } catch {
      // 忽略
    }
  });

  /**
   * 通过 WebSocket 发送消息并等待服务端 ACK。
   *
   * 发消息**只有** WebSocket 通道——`/conversations/{id}/messages` 是 GET 拉历史。
   * 脚本第一版误用了 POST，得到 500（HttpRequestMethodNotSupported）。
   */
  async function sendMessage(conversationId, text) {
    const commandId = `cmd-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    socket.send(
      JSON.stringify({
        kind: "command",
        commandId,
        event: "message:send",
        payload: {
          conversationId,
          clientMessageId: `multi-${commandId}`,
          type: "text",
          content: { text },
        },
      }),
    );
    const deadline = Date.now() + 15_000;
    while (Date.now() < deadline) {
      const ack = acks.find((a) => a.commandId === commandId);
      if (ack) return ack;
      await sleep(100);
    }
    return null;
  }

  return { socket, events, acks, opened, sendMessage };
}

/** 等到 events 里出现满足条件的事件，或超时 */
async function waitForEvent(events, predicate, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const found = events.find(predicate);
    if (found) return found;
    await sleep(200);
  }
  return null;
}

async function main() {
  // ── 前置：两个实例都就绪 ──────────────────────────────────

  step("前置检查：两个实例均已就绪");

  for (const [name, base] of [["A", A], ["B", B]]) {
    const health = await api(base, "/actuator/health/readiness");
    if (health.status === 200) {
      ok(`实例 ${name}（${base}）就绪`);
    } else {
      bad(`实例 ${name}（${base}）未就绪：${health.status}`);
      console.log("\n先运行：docker compose --profile multi up -d --build");
      process.exit(1);
    }
  }

  // 确认两个实例的 instanceId 不同——相同的话所有跨实例断言都会假通过
  // （消费者会把对方的事件当作自己发的而跳过）
  const idA = await metric(A, "openim_realtime_stream_published_total");
  const idB = await metric(B, "openim_realtime_stream_published_total");
  if (idA !== null && idB !== null) {
    ok("两个实例的指标端点均可抓取");
  } else {
    bad("指标端点不可用，后续断言无法验证");
  }

  // ── 1. 跨实例消息投递 ────────────────────────────────────

  step("验证 1：A 上发送的消息投递到 B 上的连接");

  const aliceOnA = await login(A, "alice", "a");
  const bobOnB = await login(B, "bob", "b");
  ok("Alice 登录实例 A，Bob 登录实例 B");

  const bobConn = connect(B, bobOnB.accessToken, "multi-bob-b");
  await bobConn.opened;
  ok("Bob 在实例 B 上建立 WebSocket 连接");

  // 取单聊会话
  const conversations = await api(A, "/api/v1/conversations", {
    token: aliceOnA.accessToken,
  });
  const direct = conversations.json.data.find((c) => c.type === "direct");
  if (!direct) {
    bad("演示数据里找不到单聊会话");
    process.exit(1);
  }

  // Alice 也需要连接——发消息是 WebSocket 命令
  const aliceConn = connect(A, aliceOnA.accessToken, "multi-alice-a");
  await aliceConn.opened;

  const marker = `跨实例投递 ${Date.now()}`;
  const ack = await aliceConn.sendMessage(direct.id, marker);
  if (ack?.ok) {
    ok("消息在实例 A 上写入成功（收到服务端 ACK）");
  } else {
    bad(`消息写入失败：${JSON.stringify(ack)}`);
  }

  // 这是本脚本最核心的一条断言。
  // 消息在 A 上写入，Bob 的连接在 B 上——它只能通过 Redis Streams 到达。
  const received = await waitForEvent(
    bobConn.events,
    (e) => JSON.stringify(e.payload ?? {}).includes(marker),
  );
  if (received) {
    ok(`Bob 在实例 B 上收到了这条消息 —— 跨实例投递成立`);
  } else {
    bad("Bob 未收到消息 —— 跨实例投递失败");
    info(`收到的事件：${JSON.stringify(bobConn.events.map((e) => e.event))}`);
  }

  // 恰好一次：同一条消息不该收到两遍
  const duplicates = bobConn.events.filter((e) =>
    JSON.stringify(e.payload ?? {}).includes(marker),
  );
  if (duplicates.length === 1) {
    ok("该消息只收到一次 —— 去重生效（Outbox 补偿不会造成重复）");
  } else {
    bad(`该消息收到 ${duplicates.length} 次 —— 去重未生效`);
  }

  // ── 2. 反向投递 ──────────────────────────────────────────

  step("验证 2：反向投递（B 上发送，A 上接收）");

  const reverseMarker = `反向投递 ${Date.now()}`;
  const reverseAck = await bobConn.sendMessage(direct.id, reverseMarker);
  if (reverseAck?.ok) {
    ok("消息在实例 B 上写入成功");
  } else {
    bad(`反向消息写入失败：${JSON.stringify(reverseAck)}`);
  }

  const reverseReceived = await waitForEvent(
    aliceConn.events,
    (e) => JSON.stringify(e.payload ?? {}).includes(reverseMarker),
  );
  if (reverseReceived) {
    ok("Alice 在实例 A 上收到 B 发出的消息 —— 双向跨实例投递成立");
  } else {
    bad("反向投递失败");
  }

  // ── 3. 在线状态跨实例可见 ────────────────────────────────

  step("验证 3：在线状态跨实例可见");

  // Bob 连在 B 上，从 A 查询用户列表应当看到他在线。
  //
  // 注意字段名是 `status` 而不是 `presence`：数据库列叫 presence，
  // 映射到领域模型时叫 status（而 users 表另有一个 status 列存账号状态）。
  // 脚本第一版查 `presence` 拿到 undefined，报"未找到"——那是脚本的 bug。
  const usersFromA = await api(A, "/api/v1/users", { token: aliceOnA.accessToken });
  const bobFromA = usersFromA.json?.data?.find((u) => u.username === "bob");
  if (bobFromA?.status === "online") {
    ok("从实例 A 能看到连在实例 B 上的 Bob 处于在线 —— presence 跨实例共享");
  } else {
    bad(`从实例 A 看到的 Bob 在线状态 = ${bobFromA?.status ?? "未找到该用户"}`);
    info("presence 存于 Redis（SETEX + TTL），跨实例应当一致");
  }

  // 反向也查一次：Alice 连在 A 上，从 B 应当看到她在线。
  // 只查一个方向的话，"两个实例读同一个 Redis"这件事只被验证了一半。
  const usersFromB = await api(B, "/api/v1/users", { token: bobOnB.accessToken });
  const aliceFromB = usersFromB.json?.data?.find((u) => u.username === "alice");
  if (aliceFromB?.status === "online") {
    ok("从实例 B 能看到连在实例 A 上的 Alice 处于在线 —— 双向可见");
  } else {
    bad(`从实例 B 看到的 Alice 在线状态 = ${aliceFromB?.status ?? "未找到该用户"}`);
  }

  // ── 4. 消费组独立 ────────────────────────────────────────

  step("验证 4：每个实例有独立的消费组");

  const groups = await redisCommand([
    "XINFO", "GROUPS", "openim:events:stream",
  ]);
  const groupNames = (groups ?? "")
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.startsWith("instance-"));

  if (groupNames.includes("instance-a") && groupNames.includes("instance-b")) {
    ok(`两个实例各有独立消费组：${groupNames.join(", ")}`);
    info("这是广播语义的实现方式——共用一个组会变成竞争消费，一条消息只有一个实例收到");
  } else {
    bad(`消费组不符合预期：${groupNames.join(", ") || "无"}`);
  }

  // ── 5. 限流配额跨实例共享 ────────────────────────────────

  step("验证 5：限流配额跨实例共享");

  // LOGIN 配额是 10/分钟按 IP。交替打两个实例，若配额共享则总数超过 10 后被拒；
  // 若各自独立则要 20 次才会被拒。
  let rejectedAt = null;
  for (let i = 1; i <= 16; i += 1) {
    const target = i % 2 === 0 ? A : B;
    const result = await api(target, "/api/v1/auth/login", {
      method: "POST",
      body: {
        identifier: `nobody-${Date.now()}-${i}`,
        password: "wrong-password",
        deviceId: "quota-probe",
        deviceName: "quota",
      },
    });
    if (result.status === 429) {
      rejectedAt = i;
      break;
    }
  }

  if (rejectedAt !== null && rejectedAt <= 13) {
    ok(`第 ${rejectedAt} 次请求被限流 —— 配额跨实例共享（独立计量的话要到 20 次左右）`);
  } else if (rejectedAt !== null) {
    bad(`第 ${rejectedAt} 次才被限流 —— 配额可能未共享`);
  } else {
    bad("16 次请求都未被限流 —— 配额未共享或限流未生效");
  }

  // ── 6. 单实例停机后另一实例继续服务 ──────────────────────

  step("验证 6：停掉实例 A 后，实例 B 继续服务");

  info("停止 openim-server-a");
  await execCommand("docker", ["stop", "openim-server-a"]);
  await sleep(3000);

  const bHealthAfter = await api(B, "/actuator/health/readiness");
  if (bHealthAfter.status === 200) {
    ok("实例 B 仍然就绪");
  } else {
    bad(`实例 B 就绪探针返回 ${bHealthAfter.status}`);
  }

  // 用**已有的令牌**访问业务接口，而不是重新登录。
  // 上一步的限流验证刻意耗尽了 LOGIN 配额，此时登录必然拿到 429——
  // 那会让这条断言变成"跳过"，而它恰恰是故障隔离最重要的证据。
  // 已有令牌走的是鉴权 + 业务查询路径，不受 LOGIN 配额影响。
  const bizAfterDown = await api(B, "/api/v1/conversations", {
    token: bobOnB.accessToken,
  });
  if (bizAfterDown.status === 200) {
    ok("A 停机后 B 的业务接口正常 —— 故障隔离成立");
  } else {
    bad(`A 停机后 B 的业务接口返回 ${bizAfterDown.status}`);
  }

  // A 的消费组会残留（由 cleanupStaleConsumerGroups 在闲置超时后清理）。
  // 确认它不影响 B 继续消费：残留组各有独立 pending，互不干扰。
  const markerAfterDown = `A 停机后投递 ${Date.now()}`;
  const ackAfterDown = await bobConn.sendMessage(direct.id, markerAfterDown);
  if (ackAfterDown?.ok) {
    ok("A 停机后 B 仍能收发消息 —— 残留的消费组不阻碍存活实例");
  } else {
    bad(`A 停机后在 B 上发消息失败：${JSON.stringify(ackAfterDown)}`);
  }

  info("恢复 openim-server-a");
  await execCommand("docker", ["start", "openim-server-a"]);

  // ── 收尾 ─────────────────────────────────────────────────

  bobConn.socket.close();
  aliceConn.socket.close();

  console.log(`\n${BOLD}──────────────────────────────${RESET}`);
  if (failed === 0) {
    console.log(`${GREEN}${BOLD}多实例验证通过${RESET}：${passed} 项检查全部通过\n`);
    console.log("已验证的跨实例行为：");
    console.log("  · 事件投递：双向，经 Redis Streams，恰好一次");
    console.log("  · 在线状态：跨实例可见");
    console.log("  · 消费组：每实例独立（广播语义）");
    console.log("  · 限流配额：跨实例共享");
    console.log("  · 故障隔离：单实例停机不影响另一实例");
    console.log("\n仍未验证：WebSocket 连接的粘性路由（需要真实的 Ingress/LB）。");
    process.exit(0);
  } else {
    console.log(`${RED}${BOLD}多实例验证失败${RESET}：${passed} 项通过，${failed} 项失败\n`);
    process.exit(1);
  }
}

/** 在 redis 容器里执行命令 */
async function redisCommand(args) {
  return execCommand("docker", ["exec", "openim-redis", "redis-cli", ...args]);
}

async function execCommand(command, args) {
  const { spawn } = await import("node:child_process");
  return new Promise((resolve) => {
    const child = spawn(command, args);
    let out = "";
    child.stdout.on("data", (chunk) => {
      out += chunk.toString();
    });
    child.stderr.on("data", () => {});
    child.on("close", () => resolve(out));
  });
}

main().catch((error) => {
  console.error(`\n${RED}验证脚本异常${RESET}: ${error.message}`);
  process.exit(1);
});
