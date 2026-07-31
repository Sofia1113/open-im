import { expect, test } from "@playwright/test";

import {
  loginWithCredentials,
  openDirectConversationWith,
  registerViaApi,
  sendMessage,
  uniqueSuffix,
} from "./support/fixtures";

/**
 * 消息历史分页 E2E
 *
 * <b>覆盖的是两个此前不存在的取数方向。</b>
 *
 * 改造之前服务端只有 `GET /messages?afterSeq=` —— `WHERE seq > :afterSeq ORDER BY seq`。
 * 那是**断线补拉**的语义，不是历史回溯。于是：
 *
 * 1. 没有任何接口能取比某条消息更早的内容，"向上滚动加载更早消息"这个 IM 基础
 *    交互不存在；
 * 2. 打开会话走的是 `afterSeq=0` 然后一页页翻到底，也就是把**整个会话的全部历史**
 *    拉进内存——一个累积五万条消息的会话，点开它就是 500 次串行 HTTP 请求。
 *
 * 第 2 点在演示数据下永远看不出来（每个会话两条消息），因此这里刻意造出
 * 多于一页（100 条）的会话。
 *
 * 分页的边界正确性（不重不漏、hasMore 的取值、join_seq 裁剪）由
 * `MessageHistoryPaginationTest` 的 9 个用例覆盖；这个文件验证的是界面与网络层的
 * 接线——用哪个端点、按钮何时出现、点了之后消息真的出现在屏幕上。
 */

const PASSWORD = "e2e-history-2026";

/** 服务端单页上限，与 MessageService.PAGE_SIZE 一致 */
const SERVER_PAGE_SIZE = 100;

test.describe("消息历史分页", () => {
  /**
   * 打开会话只请求最新一页，不再遍历全部历史。
   *
   * 这条断言直接盯住网络请求，因为它验证的是**代价**而不是结果——两种实现
   * 最终显示的消息一样，区别只在打开一个长会话要发多少个请求。
   * 只看界面的话这个回归完全不可见。
   */
  test("打开会话只取最新一页，不遍历全部历史", async ({ browser }) => {
    test.setTimeout(180_000);

    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const username = `hist${uniqueSuffix()}`;
      const peerUsername = `peer${uniqueSuffix()}`;
      await registerViaApi(page, username, PASSWORD, `翻页发起者-${uniqueSuffix()}`);
      await registerViaApi(page, peerUsername, PASSWORD, `翻页对端-${uniqueSuffix()}`);
      await loginWithCredentials(page, username, PASSWORD);

      // 建一个单聊并塞满超过一页的消息。全新账号的联系人列表是空的
      // （GET /users 已收窄为"有会话往来的人"），因此走按用户名搜索
      await openDirectConversationWith(page, peerUsername);
      await expect(page.getByTestId("conversation-item")).toHaveCount(1);

      const total = SERVER_PAGE_SIZE + 15;
      for (let index = 1; index <= total; index++) {
        await sendMessage(page, `历史消息 ${index}`);
      }
      // 最后一条到达后才算造数完成
      await expect(
        page.getByTestId("message-bubble").filter({ hasText: `历史消息 ${total}` }),
      ).toBeVisible({ timeout: 30_000 });

      // 重新加载，从干净的本地状态打开会话，同时记录消息相关的请求
      const messageRequests: string[] = [];
      page.on("request", (request) => {
        const url = request.url();
        if (url.includes("/messages")) messageRequests.push(url);
      });

      await page.reload();
      await page.getByTestId("conversation-item").first().click();
      await expect(
        page.getByTestId("message-bubble").filter({ hasText: `历史消息 ${total}` }),
      ).toBeVisible({ timeout: 30_000 });

      // 打开会话用的是 /messages/latest
      expect(
        messageRequests.some((url) => url.includes("/messages/latest")),
        `打开会话应请求 /messages/latest，实际请求：${messageRequests.join(", ")}`,
      ).toBeTruthy();

      // 且没有从头遍历。afterSeq=0 是"从最早的消息开始翻"的标志——
      // 旧实现正是这样打开会话的。
      expect(
        messageRequests.filter((url) => url.includes("afterSeq=0")),
        `打开会话不应从 afterSeq=0 遍历历史，实际请求：${messageRequests.join(", ")}`,
      ).toHaveLength(0);
    } finally {
      await context.close();
    }
  });

  /**
   * 向上翻阅能取到服务端未发送的更早消息。
   *
   * 关键在于翻出来的必须是**第一页里没有的**内容：如果断言只检查"点了按钮之后
   * 还能看到某条消息"，那么第一页里本来就有的消息会让断言通过，
   * 而向前翻页压根没工作。
   */
  test("向上翻阅可以加载第一页之外的更早消息", async ({ browser }) => {
    test.setTimeout(180_000);

    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const username = `back${uniqueSuffix()}`;
      const peerUsername = `bpeer${uniqueSuffix()}`;
      await registerViaApi(page, username, PASSWORD, `回溯发起者-${uniqueSuffix()}`);
      await registerViaApi(page, peerUsername, PASSWORD, `回溯对端-${uniqueSuffix()}`);
      await loginWithCredentials(page, username, PASSWORD);

      await openDirectConversationWith(page, peerUsername);
      await expect(page.getByTestId("conversation-item")).toHaveCount(1);

      const marker = uniqueSuffix();
      const total = SERVER_PAGE_SIZE + 15;
      // 第一条带唯一标记：它的 seq 是 1，一定落在最新一页之外
      await sendMessage(page, `最早的消息 ${marker}`);
      for (let index = 2; index <= total; index++) {
        await sendMessage(page, `填充 ${index}`);
      }
      await expect(
        page.getByTestId("message-bubble").filter({ hasText: `填充 ${total}` }),
      ).toBeVisible({ timeout: 30_000 });

      await page.reload();
      await page.getByTestId("conversation-item").first().click();
      await expect(
        page.getByTestId("message-bubble").filter({ hasText: `填充 ${total}` }),
      ).toBeVisible({ timeout: 30_000 });

      // 第一页取的是最新 100 条，seq=1 的那条不在其中
      await expect(
        page.getByTestId("message-bubble").filter({ hasText: `最早的消息 ${marker}` }),
      ).toHaveCount(0);

      // 反复点"查看更早"直到最早那条出现。按钮先展开本地未渲染的部分，
      // 本地展开完了再向服务端要下一页——对用户是同一个动作。
      const loadEarlier = page.getByTestId("load-earlier-button");
      const earliest = page
        .getByTestId("message-bubble")
        .filter({ hasText: `最早的消息 ${marker}` });

      for (let attempt = 0; attempt < 6; attempt++) {
        if (await earliest.count()) break;
        if (!(await loadEarlier.count())) break;
        await loadEarlier.click();
        // 等这一轮结束。终态有两种，都要接受：
        //   * 按钮变回可用——还有更早的可以继续翻
        //   * 按钮消失——已经翻到会话开头（hasMore=false 且本地无未渲染消息）
        // 只等"变回可用"会在最后一轮失败，因为那时按钮已经从 DOM 里移除了，
        // 而一个不存在的元素既不是 enabled 也不是 disabled。
        await expect
          .poll(async () => {
            if ((await loadEarlier.count()) === 0) return "gone";
            return (await loadEarlier.isEnabled()) ? "ready" : "loading";
          }, { timeout: 15_000 })
          .not.toBe("loading");
      }

      await expect(earliest).toBeVisible({ timeout: 15_000 });

      // 翻到会话开头后按钮消失——服务端的 hasMore 为 false，
      // 本地也没有未渲染的消息了
      await expect(loadEarlier).toHaveCount(0);
    } finally {
      await context.close();
    }
  });

  /** 消息不足一页时不该出现"查看更早"——没有更早的东西可看 */
  test("短会话不显示查看更早入口", async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      const username = `short${uniqueSuffix()}`;
      const peerUsername = `speer${uniqueSuffix()}`;
      await registerViaApi(page, username, PASSWORD, `短会话-${uniqueSuffix()}`);
      await registerViaApi(page, peerUsername, PASSWORD, `短对端-${uniqueSuffix()}`);
      await loginWithCredentials(page, username, PASSWORD);

      await openDirectConversationWith(page, peerUsername);

      const text = `只有一条 ${uniqueSuffix()}`;
      await sendMessage(page, text);
      await expect(
        page.getByTestId("message-bubble").filter({ hasText: text }),
      ).toBeVisible();

      await expect(page.getByTestId("load-earlier-button")).toHaveCount(0);
    } finally {
      await context.close();
    }
  });
});
