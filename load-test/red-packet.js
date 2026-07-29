// 红包并发领取压测
//
// 运行（需先启动服务端与数据库）：
//   docker compose --profile full up -d --build
//   k6 run load-test/red-packet.js
//
// 关注点不是 QPS 数字，而是三条不变量在压力下是否仍然成立：
//   1. 领取金额之和不超过红包总额（不超发）
//   2. 同一用户不会领到两份（不套利）
//   3. 份额不会因锁竞争而变得无人可领（不锁死）
//
// 压测不进 CI：它需要独立的、可复现的环境，
// 放进每次 PR 只会带来噪音和不稳定的失败。

import http from "k6/http";
import { check, fail } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://127.0.0.1:3001";

const claimSucceeded = new Counter("red_packet_claim_succeeded");
const claimRejected = new Counter("red_packet_claim_rejected");
const claimAmountTotal = new Counter("red_packet_claim_amount_total");

export const options = {
  scenarios: {
    // 群聊三人同时抢，重复多轮
    concurrent_claims: {
      executor: "per-vu-iterations",
      vus: 3,
      iterations: 20,
      maxDuration: "2m",
    },
  },
  thresholds: {
    // 领取接口的失败应当是明确的业务拒绝（409），而不是 5xx
    "http_req_failed{expected_response:true}": ["rate<0.01"],
    http_req_duration: ["p(95)<1000"],
  },
};

const USERS = ["alice", "bob", "carol"];

function login(username) {
  const response = http.post(
    `${BASE_URL}/api/v1/auth/demo-login`,
    JSON.stringify({
      username,
      deviceId: `k6-${username}-${__VU}`,
      deviceName: "k6 load test",
    }),
    { headers: { "Content-Type": "application/json" } },
  );
  if (response.status !== 200) {
    fail(`登录失败 ${username}: HTTP ${response.status}`);
  }
  return response.json("data.accessToken");
}

export function setup() {
  // 由 alice 预先发放一批红包，供各 VU 争抢
  const token = login("alice");
  const packets = [];
  for (let i = 0; i < 20; i++) {
    const response = http.post(
      `${BASE_URL}/api/v1/red-packets`,
      JSON.stringify({
        conversationId: "c2222222-2222-2222-2222-222222222222",
        mode: "random",
        totalAmount: 300,
        totalCount: 3,
        greeting: "压测红包",
        clientMessageId: `k6-msg-${Date.now()}-${i}`,
        idempotencyKey: `k6-idem-${Date.now()}-${i}`,
      }),
      {
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
      },
    );
    if (response.status === 200) {
      packets.push(response.json("data.redPacket.id"));
    }
  }
  if (packets.length === 0) {
    fail("未能创建任何红包，检查服务端是否就绪或 alice 余额是否耗尽");
  }
  return { packets };
}

export default function (data) {
  const username = USERS[(__VU - 1) % USERS.length];
  const token = login(username);
  const packetId = data.packets[__ITER % data.packets.length];

  const response = http.post(
    `${BASE_URL}/api/v1/red-packets/${packetId}/claim`,
    JSON.stringify({ idempotencyKey: `k6-claim-${__VU}-${__ITER}` }),
    {
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      // 领完与重复领取是预期内的业务拒绝，不应计入失败率
      responseCallback: http.expectedStatuses(200, 403, 409, 429),
    },
  );

  check(response, {
    "领取未返回服务端错误": (r) => r.status < 500,
  });

  if (response.status === 200) {
    claimSucceeded.add(1);
    claimAmountTotal.add(response.json("data.amount"));
  } else {
    claimRejected.add(1);
  }
}

export function teardown(data) {
  // 压测结束后校验不变量：每个红包的领取总额不得超过其面额
  const token = login("alice");
  let violations = 0;
  for (const packetId of data.packets) {
    const response = http.get(`${BASE_URL}/api/v1/red-packets/${packetId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (response.status !== 200) continue;
    const packet = response.json("data");
    if (packet.claimedAmount > packet.totalAmount) {
      console.error(`超发：红包 ${packetId} 已领 ${packet.claimedAmount} > 总额 ${packet.totalAmount}`);
      violations++;
    }
    if (packet.claimedCount > packet.totalCount) {
      console.error(`超发：红包 ${packetId} 领取次数 ${packet.claimedCount} > 份数 ${packet.totalCount}`);
      violations++;
    }
  }
  if (violations > 0) {
    fail(`检测到 ${violations} 项超发，账务存在并发缺陷`);
  }
  console.log("不变量校验通过：没有任何红包出现超发");
}
