// 消息吞吐压测
//
// 运行（需先启动服务端与依赖）：
//   docker compose --profile full up -d --build
//   k6 run load-test/message-throughput.js
//   k6 run -e VUS=200 -e DURATION=5m load-test/message-throughput.js
//
// ── 这个脚本回答什么问题 ────────────────────────────────────
//
// 此前压测只覆盖红包领取。红包是低频高冲突的操作，它验证的是并发正确性，
// 而不是容量。真正决定这个系统能承载多少用户的是**消息路径**：
//
//   1. 单实例能承载多少并发发送？瓶颈在哪一层？
//   2. 群聊扇出的放大效应有多大？（一条消息 → N 个成员 × M 个实例）
//   3. 每请求一次的鉴权查询会不会先撞上连接池上限？
//
// 第 3 点是这轮改造前的真实瓶颈：鉴权对每个请求查一次数据库，
// Hikari 池只有 20 个连接。加了会话缓存后这条路径应该不再是瓶颈——
// 这个脚本用来验证那个判断，而不是相信它。
//
// ── 观察什么 ────────────────────────────────────────────────
//
// 跑的时候同时看这些指标（Grafana 的 OpenIM dashboard）：
//   * hikaricp_connections_pending —— 出现非零说明连接池成为瓶颈
//   * cache_gets_total{cache="session-validity"} 的命中率 —— 应当接近 1
//   * openim_realtime_stream_published_total 与 _consumed_total 的比值
//     —— 消费速率应约为发布速率 × 实例数
//   * jvm_gc_pause_seconds —— 排除 GC 抖动造成的延迟尖峰

import http from "k6/http";
import { check, fail } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://127.0.0.1:3001";
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || "2m";

// 自定义指标：k6 内置的 http_req_* 不区分业务成功与否
const messagesSent = new Counter("messages_sent");
const messagesRejected = new Counter("messages_rejected");
const rateLimited = new Counter("messages_rate_limited");
const sendLatency = new Trend("message_send_latency", true);
const historyLatency = new Trend("message_history_latency", true);
const businessSuccess = new Rate("message_business_success");

export const options = {
  scenarios: {
    // 阶梯加压：找到吞吐拐点，而不是只测一个固定并发下的数字。
    // 固定并发测出来的"QPS 3000"没有意义——它不告诉你 3001 会发生什么。
    ramping_send: {
      executor: "ramping-vus",
      startVUs: 5,
      stages: [
        { duration: "30s", target: Math.ceil(VUS * 0.2) },
        { duration: "30s", target: Math.ceil(VUS * 0.5) },
        { duration: DURATION, target: VUS },
        // 主动降压：观察系统能否恢复，而不只是能否顶住。
        // 压力撤掉后延迟不回落说明有队列积压或资源没释放。
        { duration: "30s", target: Math.ceil(VUS * 0.2) },
      ],
      gracefulRampDown: "20s",
    },
  },
  thresholds: {
    // 业务成功率而非 HTTP 成功率：429 在 HTTP 层是"失败"，
    // 但它是限流<b>正常工作</b>的表现，不该计入错误
    message_business_success: ["rate>0.95"],
    // p95 与 p99 都设阈值：只看 p95 会漏掉长尾，而长尾正是用户抱怨的部分
    "message_send_latency": ["p(95)<500", "p(99)<1500"],
    "message_history_latency": ["p(95)<800"],
    // 5xx 是真正的失败，容忍度必须远低于业务拒绝
    "http_req_failed{status:500}": ["rate<0.001"],
  },
};

const USERS = ["alice", "bob", "carol"];

/** 每个 VU 登录一次，复用令牌——重复登录会撞上 LOGIN 的配额 */
export function setup() {
  const sessions = {};
  for (const username of USERS) {
    const response = http.post(
      `${BASE_URL}/api/v1/auth/demo-login`,
      JSON.stringify({
        username,
        deviceId: `k6-throughput-${username}`,
        deviceName: "k6 throughput",
      }),
      { headers: { "Content-Type": "application/json" } },
    );
    if (response.status !== 200) {
      fail(`登录失败 ${username}: ${response.status} ${response.body}`);
    }
    const body = response.json();
    sessions[username] = body.data.accessToken;
  }

  // 取会话列表，供发消息使用
  const token = sessions[USERS[0]];
  const conversations = http.get(`${BASE_URL}/api/v1/conversations`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (conversations.status !== 200) {
    fail(`获取会话列表失败: ${conversations.status}`);
  }
  const list = conversations.json().data;

  const direct = list.find((c) => c.type === "direct");
  const group = list.find((c) => c.type === "group");
  if (!direct || !group) {
    fail("演示数据缺少单聊或群聊会话，无法压测扇出对比");
  }

  return { sessions, directId: direct.id, groupId: group.id };
}

export default function (data) {
  const username = USERS[__VU % USERS.length];
  const token = data.sessions[username];
  const headers = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${token}`,
  };

  // 交替打单聊与群聊：群聊的扇出成本更高（成员数 × 实例数），
  // 混合负载比只压一种更接近真实流量
  const conversationId = __ITER % 3 === 0 ? data.groupId : data.directId;

  const sent = http.post(
    `${BASE_URL}/api/v1/conversations/${conversationId}/messages`,
    JSON.stringify({
      conversationId,
      clientMessageId: `k6-${__VU}-${__ITER}-${Date.now()}`,
      type: "text",
      content: { text: `k6 吞吐测试 VU${__VU} 第 ${__ITER} 条` },
    }),
    { headers, tags: { name: "send_message" } },
  );

  sendLatency.add(sent.timings.duration);

  if (sent.status === 200 || sent.status === 201) {
    messagesSent.add(1);
    businessSuccess.add(true);
  } else if (sent.status === 429) {
    // 限流是预期行为，不算失败——但要计数，
    // 如果绝大多数请求都被限流，那么这次压测测的是限流器而不是消息路径
    rateLimited.add(1);
    businessSuccess.add(true);
  } else {
    messagesRejected.add(1);
    businessSuccess.add(false);
    check(sent, {
      "发送消息未返回 5xx": (r) => r.status < 500,
    });
  }

  // 每 5 轮拉一次历史，模拟真实客户端的补拉行为。
  // 只压写入会漏掉一个重要的瓶颈：历史查询走 idx_messages_conversation_seq，
  // 而消息表在持续增长
  if (__ITER % 5 === 0) {
    const history = http.get(
      `${BASE_URL}/api/v1/conversations/${conversationId}/messages?afterSeq=0`,
      { headers, tags: { name: "list_messages" } },
    );
    historyLatency.add(history.timings.duration);
    check(history, { "拉取历史成功": (r) => r.status === 200 });
  }
}

export function teardown(data) {
  // 压测产生的消息不清理——它们是后续容量分析的样本，
  // 而且"表里有几十万条消息"正是需要被测试的状态。
  // 需要重置时用 docker compose down -v。
  console.log(
    `压测结束。会话：单聊 ${data.directId}，群聊 ${data.groupId}\n` +
      "关注 hikaricp_connections_pending 与 session-validity 缓存命中率。",
  );
}
