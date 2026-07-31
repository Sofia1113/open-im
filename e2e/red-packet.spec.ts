import { expect, test } from "@playwright/test";

import {
  DEMO_PASSWORD,
  loginWithCredentials,
  openConversation,
  readWalletBalanceCents,
  uniqueSuffix,
} from "./support/fixtures";

/**
 * 红包 E2E
 *
 * 红包是这个系统里唯一涉及金额的功能，服务端已有并发正确性测试与账务不变量。
 * E2E 在此之上验证的是<b>界面呈现的金额与服务端账务一致</b>——
 * 一个只在前端做了错误单位换算（分/元）的 bug 不会破坏任何服务端不变量，
 * 但用户会看到错了一百倍的余额。
 */
test.describe("红包", () => {
  test("发放后余额减少，另一方领取后余额增加", async ({ browser }) => {
    const aliceContext = await browser.newContext();
    const bobContext = await browser.newContext();
    const alice = await aliceContext.newPage();
    const bob = await bobContext.newPage();

    try {
      await loginWithCredentials(alice, "alice", DEMO_PASSWORD);
      await loginWithCredentials(bob, "bob", DEMO_PASSWORD);
      await openConversation(alice, "Bob");
      await openConversation(bob, "Alice");

      const aliceBefore = await readWalletBalanceCents(alice);
      const bobBefore = await readWalletBalanceCents(bob);

      // 打开红包表单
      await alice.getByRole("button", { name: "发送红包" }).click();

      const greeting = `E2E 红包 ${uniqueSuffix()}`;
      await alice.getByLabel("总金额").fill("1.50");
      const greetingField = alice.getByLabel("祝福语");
      if (await greetingField.count()) {
        await greetingField.fill(greeting);
      }
      await alice.getByRole("button", { name: "塞钱进红包" }).click();

      // 发送方余额减少 150 分
      await expect
        .poll(() => readWalletBalanceCents(alice), { timeout: 15_000 })
        .toBe(aliceBefore - 150);

      // 接收方看到红包卡片并领取
      const card = bob.getByTestId("red-packet-card").last();
      await expect(card).toBeVisible({ timeout: 15_000 });
      await card.click();

      // exact: true 是必需的——红包卡片内也有"领取红包"字样，
      // 不加的话会同时匹配到消息列表里的卡片和弹窗里的领取按钮
      await bob.getByRole("button", { name: "领取红包", exact: true }).click();

      // 领取方余额增加 150 分。
      // 这条断言把前端显示、服务端账务、复式记账三者绑在一起：
      // 任何一处的单位换算或方向错误都会让它失败。
      await expect
        .poll(() => readWalletBalanceCents(bob), { timeout: 15_000 })
        .toBe(bobBefore + 150);
    } finally {
      await aliceContext.close();
      await bobContext.close();
    }
  });

  test("超出余额的红包被拒绝且余额不变", async ({ page }) => {
    await loginWithCredentials(page, "alice", DEMO_PASSWORD);
    await openConversation(page, "Bob");

    const before = await readWalletBalanceCents(page);

    await page.getByRole("button", { name: "发送红包" }).click();
    // 演示账号初始余额 500 元，这里填一个远超的数额
    await page.getByLabel("总金额").fill("999999");
    await page.getByRole("button", { name: "塞钱进红包" }).click();

    // 余额必须完全不变——扣款与红包创建在同一事务内，
    // 任何一步失败都不该留下"钱扣了但红包没发出"的中间态
    await page.waitForTimeout(2000);
    expect(await readWalletBalanceCents(page)).toBe(before);
  });
});
