import { type Page, expect } from "@playwright/test";

/**
 * E2E 共用工具
 *
 * 这些函数刻意通过**界面**操作而不是直接调 API：E2E 的价值正在于验证
 * 界面与服务端之间那一层——真实的 HTTP、真实的 WebSocket 握手、
 * 真实的令牌存储与刷新。绕过界面直接调 API 就退化成了另一种集成测试，
 * 而集成测试服务端已经有 180 多个了。
 *
 * 例外是 `registerViaApi`：创建测试账号是**前置条件**而不是被测行为，
 * 每个用例都走一遍注册界面只是在重复测试同一件事，还让用例变慢。
 */

export const DEMO_PASSWORD = "openim-demo-2026";

/** 生成互不冲突的账号名。并行用例共享数据库，固定名字会撞唯一约束 */
export function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * 通过用户名查找并打开与某人的单聊。
 *
 * <b>为什么不能直接在联系人列表里点</b>：`GET /users` 已经从"全站所有用户"收窄为
 * "与我有会话往来的人"，因此一个全新账号的联系人列表是<b>空的</b>——
 * 陌生人不再出现在那里。这不是测试的怪癖，而是收窄之后建立第一次对话的真实路径：
 * 按完整用户名搜索，然后点搜索结果。
 */
export async function openDirectConversationWith(
  page: Page,
  username: string,
): Promise<void> {
  await page.getByRole("button", { name: "联系人" }).click();
  await page.getByTestId("user-search-input").fill(username);
  await page.getByTestId("user-search-button").click();
  await page.getByTestId("contact-item").first().click();
}

/**
 * 用凭证登录，等到工作台真正可用。
 *
 * "可用"的判定是钱包余额出现——它需要登录后的第一批 API 请求全部成功。
 * 只等 URL 变化或某个标题出现是不够的：那时页面已经渲染但数据还没到，
 * 后续操作会作用在一个空列表上，失败信息会指向错误的地方。
 */
export async function loginWithCredentials(
  page: Page,
  identifier: string,
  password: string,
): Promise<void> {
  await page.goto("/");
  await page.getByRole("tab", { name: "登录" }).click();
  await page.getByLabel("用户名或邮箱").fill(identifier);
  await page.getByLabel("密码").fill(password);
  await page.locator("button.auth-submit").click();

  await expect(page.getByTestId("wallet-balance")).toBeVisible({ timeout: 20_000 });
}

/** 通过演示入口一键登录（仅非 prod 可用） */
export async function loginAsDemoUser(page: Page, displayName: string): Promise<void> {
  await page.goto("/");
  await page.getByRole("button", { name: new RegExp(displayName) }).click();
  await expect(page.getByTestId("wallet-balance")).toBeVisible({ timeout: 20_000 });
}

/** 直接调 API 创建账号。这是前置条件，不是被测行为 */
export async function registerViaApi(
  page: Page,
  username: string,
  password: string,
  displayName = "E2E 用户",
): Promise<void> {
  const response = await page.request.post(
    "http://127.0.0.1:3001/api/v1/auth/register",
    {
      data: {
        username,
        email: `${username}@e2e.test`,
        displayName,
        password,
        deviceId: `e2e-${username}`,
        deviceName: "E2E",
      },
    },
  );
  expect(
    response.ok(),
    `注册前置条件失败：${response.status()} ${await response.text()}`,
  ).toBeTruthy();
}

/** 打开指定标题的会话 */
export async function openConversation(page: Page, title: string): Promise<void> {
  await page
    .getByTestId("conversation-item")
    .filter({ hasText: title })
    .first()
    .click();
  await expect(page.getByLabel("消息内容")).toBeVisible();
}

/**
 * 发送一条消息。
 *
 * 输入框在 WebSocket 未连接时是 disabled 的，因此先等它可用——
 * 不等的话 fill 会在连接建立前执行，消息发不出去而报错指向输入框。
 */
export async function sendMessage(page: Page, text: string): Promise<void> {
  const input = page.getByLabel("消息内容");
  await expect(input).toBeEnabled({ timeout: 20_000 });
  await input.fill(text);
  await input.press("Enter");
}

/** 读取钱包余额（分）。界面显示的是格式化后的元，这里还原成分便于断言 */
export async function readWalletBalanceCents(page: Page): Promise<number> {
  const text = await page.getByTestId("wallet-balance").innerText();
  const numeric = text.replace(/[^\d.]/g, "");
  return Math.round(Number.parseFloat(numeric) * 100);
}
