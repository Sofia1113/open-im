import { type Browser, expect, test } from "@playwright/test";

import {
  loginWithCredentials,
  openDirectConversationWith,
  registerViaApi,
  sendMessage,
  uniqueSuffix,
} from "./support/fixtures";

/**
 * 会话创建与成员管理 E2E
 *
 * <b>这个文件覆盖的是此前走不通的那条路。</b>
 *
 * 改造之前，服务端没有任何创建会话的接口——会话全部来自演示种子，而种子在
 * {@code prod} profile 下不加载。生产环境注册出来的账号打开的是空列表，
 * 无法与任何人建立对话，一条消息都发不出去。而当时 14 个 E2E 全部通过：
 * 它们登录的是种子账号 Alice/Bob/Carol，打开的是种子里已经存在的会话，
 * 没有任何一条断言覆盖"一个全新账号能做什么"。
 *
 * 因此这里的每个用例都<b>从注册开始</b>，不使用任何演示账号，也不打开任何
 * 种子会话。这是防止同类断层复发的机制：只要"新账号建会话"这条路再次断掉，
 * 这些用例就会失败。
 *
 * 注册走 API（前置条件而非被测行为），此后全部通过界面操作。
 */

const PASSWORD = "e2e-conversation-2026";

interface Account {
  context: Awaited<ReturnType<Browser["newContext"]>>;
  page: Awaited<ReturnType<Awaited<ReturnType<Browser["newContext"]>>["newPage"]>>;
  username: string;
  displayName: string;
}

/**
 * 创建若干全新账号，每个在独立的 browser context 里登录。
 *
 * 用独立 context 而不是独立 page：context 之间 localStorage 隔离，这才等价于
 * 两台设备。同一个 context 里的两个 page 共享令牌，测出来的是"多标签页"。
 *
 * 全新账号的联系人列表是<b>空的</b>——`GET /users` 已从"全站所有用户"收窄为
 * "与我有会话往来的人"。因此建立第一次对话要走按用户名搜索
 * （{@link openDirectConversationWith}），这也是收窄之后真实用户的路径。
 */
async function setupAccounts(
  browser: Browser,
  ...displayNames: string[]
): Promise<Account[]> {
  const accounts: Account[] = [];
  for (const displayName of displayNames) {
    const context = await browser.newContext();
    const page = await context.newPage();
    accounts.push({
      context,
      page,
      username: `conv${uniqueSuffix()}`,
      displayName,
    });
  }

  // 阶段一：全部注册。此时谁都还没登录，所以没人持有过期的联系人列表
  for (const account of accounts) {
    await registerViaApi(account.page, account.username, PASSWORD, account.displayName);
  }
  // 阶段二：逐个登录，各自加载到完整的联系人列表
  for (const account of accounts) {
    await loginWithCredentials(account.page, account.username, PASSWORD);
  }
  return accounts;
}

async function closeAll(accounts: Account[]): Promise<void> {
  for (const account of accounts) {
    await account.context.close();
  }
}

/**
 * 让 actor 与 peer 建立联系关系（开一个单聊）。
 *
 * 建群的候选人只有<b>联系人</b>——把从未打过交道的陌生人直接拉进群，
 * 对他来说等同于被陌生人加入一个未知的群聊。因此群相关的用例必须先走这一步，
 * 这也是真实用户建群前的必经路径。
 */
async function becomeContacts(actor: Account, peer: Account): Promise<void> {
  await openDirectConversationWith(actor.page, peer.username);
  await expect(actor.page.getByTestId("conversation-item")).toHaveCount(1);
}

test.describe("会话创建", () => {
  /**
   * 两个全新账号从零开始建立对话并互发消息。
   *
   * 这是整个改动要解决的那个问题的最直接验证：注册 → 在联系人里点对方 →
   * 单聊出现 → 发消息 → 对方实时收到。中间任何一环断掉这个用例都会失败。
   */
  test("两个全新注册的账号可以建立单聊并互发消息", async ({ browser }) => {
    const [alice, bob] = await setupAccounts(
      browser,
      `新用户A-${uniqueSuffix()}`,
      `新用户B-${uniqueSuffix()}`,
    );

    try {
      // 全新账号的会话列表是空的。这条断言是这个用例的前提——
      // 如果它失败了说明测试意外用到了种子数据，后面的断言就失去意义
      await expect(alice.page.getByTestId("conversation-item")).toHaveCount(0);

      // 按用户名找到对方并打开单聊。两个入口此前都不存在：
      // 服务端既没有创建会话的接口，也没有按用户名查找的接口
      await openDirectConversationWith(alice.page, bob.username);

      // 会话出现并被自动选中
      await expect(alice.page.getByTestId("conversation-item")).toHaveCount(1);

      const text = `全新账号的第一条消息 ${uniqueSuffix()}`;
      await sendMessage(alice.page, text);
      await expect(
        alice.page.getByTestId("message-bubble").filter({ hasText: text }),
      ).toBeVisible();

      // 对端通过 conversation:created 事件看到这个新会话出现，
      // 然后收到消息。两者都靠实时事件，没有刷新页面。
      await expect(bob.page.getByTestId("conversation-item")).toHaveCount(1, {
        timeout: 15_000,
      });
      await bob.page.getByTestId("conversation-item").first().click();
      await expect(
        bob.page.getByTestId("message-bubble").filter({ hasText: text }),
      ).toBeVisible({ timeout: 15_000 });

      // 反向也能发
      const reply = `回复 ${uniqueSuffix()}`;
      await sendMessage(bob.page, reply);
      await expect(
        alice.page.getByTestId("message-bubble").filter({ hasText: reply }),
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await closeAll([alice, bob]);
    }
  });

  /** 重复点同一个联系人不会产生第二个单聊——服务端幂等 */
  test("重复打开同一个人的单聊不会产生重复会话", async ({ browser }) => {
    const [alice, bob] = await setupAccounts(
      browser,
      `幂等A-${uniqueSuffix()}`,
      `幂等B-${uniqueSuffix()}`,
    );

    try {
      await openDirectConversationWith(alice.page, bob.username);
      await expect(alice.page.getByTestId("conversation-item")).toHaveCount(1);

      // 第二次：此时对方已经是联系人，直接在列表里点
      await alice.page.getByRole("button", { name: "联系人" }).click();
      await alice.page
        .getByTestId("contact-item")
        .filter({ hasText: bob.displayName })
        .click();

      await expect(alice.page.getByTestId("conversation-item")).toHaveCount(1);
    } finally {
      await closeAll([alice, bob]);
    }
  });
});

test.describe("群成员管理", () => {
  /**
   * 建群 → 角色正确 → 加人 → 被移出者的会话实时消失。
   *
   * 最后一步验证的是 {@code conversation:removed}：被移出的人已经不在会话成员
   * 列表里，扇出到不了他，服务端必须<b>按用户</b>单独推送。少了那条推送，
   * 客户端会继续显示一个已经无权访问的会话，直到下次操作全部返回 403。
   */
  test("建群、加人、移出成员在两端都实时生效", async ({ browser }) => {
    const [owner, member] = await setupAccounts(
      browser,
      `群主-${uniqueSuffix()}`,
      `成员-${uniqueSuffix()}`,
    );

    try {
      // 先建立联系关系——建群只能从联系人里选人
      await becomeContacts(owner, member);

      // 建群
      await owner.page.getByTestId("new-group-button").click();
      const groupTitle = `E2E 群 ${uniqueSuffix()}`;
      await owner.page.getByTestId("group-title-input").fill(groupTitle);
      await owner.page
        .getByTestId("group-member-option")
        .filter({ hasText: member.displayName })
        .click();
      await owner.page.getByTestId("group-submit-button").click();

      await expect(
        owner.page.getByTestId("conversation-item").filter({ hasText: groupTitle }),
      ).toBeVisible();

      // 成员面板：创建者是群主，被邀请者是成员。
      // role 字段自建表起就存在，但在补上成员管理之前没有一行代码读它。
      //
      // 按行定位再取 member-role，而不是在整个面板上按文本找"群主"——
      // 成员行里有一个"转让群主"按钮，按文本匹配会把它也算进去。
      await owner.page.getByTestId("conversation-info-button").click();
      await expect(owner.page.getByTestId("member-row")).toHaveCount(2);
      await expect(
        owner.page
          .getByTestId("member-row")
          .filter({ hasText: owner.displayName })
          .getByTestId("member-role"),
      ).toHaveText("群主");
      await expect(
        owner.page
          .getByTestId("member-row")
          .filter({ hasText: member.displayName })
          .getByTestId("member-role"),
      ).toHaveText("成员");

      // 被邀请者那一侧也看到了这个群（conversation:created）
      await expect(
        member.page.getByTestId("conversation-item").filter({ hasText: groupTitle }),
      ).toBeVisible({ timeout: 15_000 });

      // 群主把成员移出
      await owner.page
        .getByTestId("member-row")
        .filter({ hasText: member.displayName })
        .getByTestId("remove-member-button")
        .click();
      await expect(owner.page.getByTestId("member-row")).toHaveCount(1);

      // 被移出者的会话从列表里消失——这依赖服务端单独给他推的
      // conversation:removed，扇出覆盖不到已经不是成员的人
      await expect(
        member.page.getByTestId("conversation-item").filter({ hasText: groupTitle }),
      ).toHaveCount(0, { timeout: 15_000 });
    } finally {
      await closeAll([owner, member]);
    }
  });

  /**
   * 普通成员看不到管理操作。
   *
   * 这只是界面层的收敛，<b>不是权限边界</b>——真正的判定在服务端（越权请求会被
   * 403 拒绝，有集成测试逐格覆盖）。这里验证的是"不让人点一个必然失败的按钮"。
   */
  test("普通成员看不到成员管理入口", async ({ browser }) => {
    const [owner, member] = await setupAccounts(
      browser,
      `拥有者-${uniqueSuffix()}`,
      `普通-${uniqueSuffix()}`,
    );

    try {
      await becomeContacts(owner, member);

      await owner.page.getByTestId("new-group-button").click();
      const groupTitle = `权限群 ${uniqueSuffix()}`;
      await owner.page.getByTestId("group-title-input").fill(groupTitle);
      await owner.page
        .getByTestId("group-member-option")
        .filter({ hasText: member.displayName })
        .click();
      await owner.page.getByTestId("group-submit-button").click();

      await member.page
        .getByTestId("conversation-item")
        .filter({ hasText: groupTitle })
        .click({ timeout: 15_000 });
      await member.page.getByTestId("conversation-info-button").click();

      // 成员列表可见（任何成员都能看），但管理入口不可见
      await expect(member.page.getByTestId("member-row")).toHaveCount(2);
      await expect(member.page.getByTestId("add-members-button")).toHaveCount(0);
      await expect(member.page.getByTestId("remove-member-button")).toHaveCount(0);

      // 但退群是可以的——群主才是那个必须先转让的角色
      await expect(
        member.page.getByTestId("leave-conversation-button"),
      ).toBeVisible();
    } finally {
      await closeAll([owner, member]);
    }
  });

  /** 群主退群前必须转让，界面上不给退群入口 */
  test("群主没有直接退群的入口", async ({ browser }) => {
    const [owner, member] = await setupAccounts(
      browser,
      `唯一群主-${uniqueSuffix()}`,
      `旁观-${uniqueSuffix()}`,
    );

    try {
      await becomeContacts(owner, member);

      await owner.page.getByTestId("new-group-button").click();
      await owner.page.getByTestId("group-title-input").fill(`转让群 ${uniqueSuffix()}`);
      await owner.page
        .getByTestId("group-member-option")
        .filter({ hasText: member.displayName })
        .click();
      await owner.page.getByTestId("group-submit-button").click();

      await owner.page.getByTestId("conversation-info-button").click();

      // 退群按钮不存在，取而代之的是说明为什么
      await expect(owner.page.getByTestId("leave-conversation-button")).toHaveCount(0);
      await expect(
        owner.page.getByText("群主需先转让群主身份才能退群"),
      ).toBeVisible();
    } finally {
      await closeAll([owner, member]);
    }
  });
});
