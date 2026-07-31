import { expect, test } from "@playwright/test";

import {
  DEMO_PASSWORD,
  loginWithCredentials,
  openConversation,
  sendMessage,
  uniqueSuffix,
} from "./support/fixtures";

/**
 * 实时消息 E2E
 *
 * 这是整个测试体系里唯一验证<b>跨客户端实时投递</b>的地方。
 * 服务端测试能验证消息写库、能验证 Stream 里有记录，但"另一个浏览器里
 * 真的出现了这条消息"跨越了服务端投递、WebSocket 传输、客户端事件处理、
 * React 状态更新四层，只有 E2E 能覆盖。
 *
 * 用两个 browser context 而不是两个 page：context 之间 localStorage 隔离，
 * 这才等价于两台设备。同一个 context 里的两个 page 共享令牌，
 * 测出来的是"多标签页"而不是"两个用户"。
 */
test.describe("实时消息", () => {
  test("一方发送的消息实时出现在另一方界面上", async ({ browser }) => {
    const aliceContext = await browser.newContext();
    const bobContext = await browser.newContext();
    const alice = await aliceContext.newPage();
    const bob = await bobContext.newPage();

    try {
      await loginWithCredentials(alice, "alice", DEMO_PASSWORD);
      await loginWithCredentials(bob, "bob", DEMO_PASSWORD);

      // 双方打开同一个单聊
      await openConversation(alice, "Bob");
      await openConversation(bob, "Alice");

      const text = `实时投递验证 ${uniqueSuffix()}`;
      await sendMessage(alice, text);

      // 发送方看到自己的消息（本地回显 + 服务端 ACK）
      await expect(
        alice.getByTestId("message-bubble").filter({ hasText: text }),
      ).toBeVisible();

      // 接收方通过 WebSocket 收到——这是本用例的核心断言
      await expect(
        bob.getByTestId("message-bubble").filter({ hasText: text }),
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await aliceContext.close();
      await bobContext.close();
    }
  });

  test("消息在刷新页面后仍然存在", async ({ page }) => {
    await loginWithCredentials(page, "alice", DEMO_PASSWORD);
    await openConversation(page, "Bob");

    const text = `持久化验证 ${uniqueSuffix()}`;
    await sendMessage(page, text);
    await expect(
      page.getByTestId("message-bubble").filter({ hasText: text }),
    ).toBeVisible();

    await page.reload();
    await openConversation(page, "Bob");

    // 刷新后从数据库补拉。看不到说明消息只存在于内存里——
    // 那意味着服务端根本没写库，或者补拉接口有问题
    await expect(
      page.getByTestId("message-bubble").filter({ hasText: text }),
    ).toBeVisible({ timeout: 15_000 });
  });

  test("断线后重连并补拉期间遗漏的消息", async ({ browser }) => {
    const aliceContext = await browser.newContext();
    const bobContext = await browser.newContext();
    const alice = await aliceContext.newPage();
    const bob = await bobContext.newPage();

    try {
      await loginWithCredentials(alice, "alice", DEMO_PASSWORD);
      await loginWithCredentials(bob, "bob", DEMO_PASSWORD);
      await openConversation(alice, "Bob");
      await openConversation(bob, "Alice");

      // Bob 离线
      await bobContext.setOffline(true);

      const missed = `断线期间的消息 ${uniqueSuffix()}`;
      await sendMessage(alice, missed);
      await expect(
        alice.getByTestId("message-bubble").filter({ hasText: missed }),
      ).toBeVisible();

      // Bob 恢复。客户端应当重连并按会话最大 seq 分页补拉——
      // 这条路径是"消息不丢"的最后一道保障，也是 Redis Stream
      // 投递失败时的兜底。它坏了的话，症状是偶发丢消息，极难复现。
      await bobContext.setOffline(false);

      await expect(
        bob.getByTestId("message-bubble").filter({ hasText: missed }),
      ).toBeVisible({ timeout: 30_000 });
    } finally {
      await aliceContext.close();
      await bobContext.close();
    }
  });

  test("群聊消息投递给其他成员", async ({ browser }) => {
    const aliceContext = await browser.newContext();
    const carolContext = await browser.newContext();
    const alice = await aliceContext.newPage();
    const carol = await carolContext.newPage();

    try {
      await loginWithCredentials(alice, "alice", DEMO_PASSWORD);
      await loginWithCredentials(carol, "carol", DEMO_PASSWORD);

      // 演示数据里有一个三人群
      await openConversation(alice, "周末出游计划");
      await openConversation(carol, "周末出游计划");

      const text = `群聊投递 ${uniqueSuffix()}`;
      await sendMessage(alice, text);

      await expect(
        carol.getByTestId("message-bubble").filter({ hasText: text }),
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await aliceContext.close();
      await carolContext.close();
    }
  });

  test("超长消息被前端拦住而不是发出去再被拒", async ({ page }) => {
    await loginWithCredentials(page, "alice", DEMO_PASSWORD);
    await openConversation(page, "Bob");

    const input = page.getByLabel("消息内容");
    await expect(input).toBeEnabled({ timeout: 20_000 });

    // maxLength=4000 与服务端的 MAX_TEXT_LENGTH 一致。
    // 两处不一致的话用户会打完一大段才被拒绝。
    await expect(input).toHaveAttribute("maxlength", "4000");
  });
});
