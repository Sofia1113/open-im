import { expect, test } from "@playwright/test";

import {
  DEMO_PASSWORD,
  loginWithCredentials,
  registerViaApi,
  uniqueSuffix,
} from "./support/fixtures";

/**
 * 认证流程 E2E
 *
 * 这一组用例覆盖的是服务端测试<b>无法</b>覆盖的部分：令牌到底有没有被前端
 * 正确存下来、刷新逻辑有没有在真实的 401 上触发、错误码有没有被翻译成
 * 用户看得懂的提示。
 *
 * 第 7 轮那个"prod 下无任何登录入口"的缺陷正属于这一类——服务端的每个
 * 组件单独看都是对的，连起来却没有一条可用的登录路径。那种问题只有
 * 端到端跑一遍才会暴露。
 */
test.describe("认证", () => {
  test("演示账号可以用凭证登录并进入工作台", async ({ page }) => {
    await loginWithCredentials(page, "alice", DEMO_PASSWORD);

    await expect(page.getByTestId("wallet-balance")).toBeVisible();
    await expect(page.getByTestId("conversation-item").first()).toBeVisible();
  });

  test("邮箱大小写混合也能登录", async ({ page }) => {
    // 服务端按 lower(email) 匹配。这条断言防止前端在提交前做了
    // 会破坏匹配的规范化处理
    await loginWithCredentials(page, "ALICE@Example.TEST", DEMO_PASSWORD);

    await expect(page.getByTestId("wallet-balance")).toBeVisible();
  });

  test("密码错误时显示服务端返回的提示，且不进入工作台", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("tab", { name: "登录" }).click();
    await page.getByLabel("用户名或邮箱").fill("alice");
    await page.getByLabel("密码").fill("definitely-not-the-password");
    await page.locator("button.auth-submit").click();

    await expect(page.getByText("用户名或密码不正确")).toBeVisible();
    await expect(page.getByTestId("wallet-balance")).toHaveCount(0);
  });

  test("不存在的账号与密码错误显示同样的提示", async ({ page }) => {
    // 账号枚举防护在服务端实现，但如果前端把两种情况渲染成不同文案，
    // 防护就在最后一步失效了
    await page.goto("/");
    await page.getByRole("tab", { name: "登录" }).click();
    await page.getByLabel("用户名或邮箱").fill(`nobody-${uniqueSuffix()}`);
    await page.getByLabel("密码").fill("whatever-password");
    await page.locator("button.auth-submit").click();

    await expect(page.getByText("用户名或密码不正确")).toBeVisible();
  });

  test("注册后直接进入工作台", async ({ page }) => {
    const username = `e2e_reg_${uniqueSuffix()}`;

    await page.goto("/");
    await page.getByRole("tab", { name: "注册" }).click();
    await page.getByLabel("用户名").fill(username);
    await page.getByLabel("昵称").fill("E2E 注册用户");
    await page.getByLabel("密码").fill("E2ePassw0rd!Strong");
    await page.locator("button.auth-submit").click();

    await expect(page.getByTestId("wallet-balance")).toBeVisible({
      timeout: 20_000,
    });
  });

  test("弱密码被拒绝并给出可操作的提示", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("tab", { name: "注册" }).click();
    await page.getByLabel("用户名").fill(`e2e_weak_${uniqueSuffix()}`);
    await page.getByLabel("昵称").fill("弱密码测试");
    await page.getByLabel("密码").fill("123");
    await page.locator("button.auth-submit").click();

    // 不断言具体文案——那属于服务端的策略描述，会随策略调整而变。
    // 只断言"没有进入工作台"且"显示了某个错误"
    await expect(page.getByTestId("wallet-balance")).toHaveCount(0);
    await expect(page.locator(".auth-error, [role=alert]").first()).toBeVisible();
  });

  test("会话在刷新页面后保持", async ({ page }) => {
    const username = `e2e_persist_${uniqueSuffix()}`;
    await registerViaApi(page, username, "E2ePassw0rd!Strong");
    await loginWithCredentials(page, username, "E2ePassw0rd!Strong");

    await page.reload();

    // 令牌必须被持久化到 localStorage 而不是只存在内存里，
    // 否则用户每次刷新都要重新登录
    await expect(page.getByTestId("wallet-balance")).toBeVisible({
      timeout: 20_000,
    });
  });

});

/*
 * 刻意不在这里测密码重置。
 *
 * 曾写过一个"未注册邮箱与已注册邮箱返回相同状态码"的用例，删掉了，两个理由：
 *
 *   1. 它测的是纯服务端行为，且已被 PasswordResetIntegrationTest 完整覆盖
 *      （账号枚举防护、令牌单次使用、过期、解除锁定等 16 个用例）。
 *      在 E2E 里重复一遍不增加信息量。
 *
 *   2. 它在 E2E 里本质上不稳定：PASSWORD_RESET_REQUEST 的配额是每分钟 3 次
 *      按 IP 计量，而 E2E 的所有请求来自同一个 IP。连续两次调用加上重试，
 *      第二个请求就会拿到 429——测试会因为限流<b>正常工作</b>而失败。
 *      放宽断言去容忍 429 只会让这个用例什么都不再验证。
 *
 * 等界面上有了"忘记密码"入口，值得加的 E2E 是那条<b>界面</b>路径：
 * 填邮箱 → 提交 → 看到"已发送"提示 → 用令牌打开重置页 → 设新密码 → 能登录。
 * 那条路径跨越了服务端测试覆盖不到的部分，而且每个用例只调一次接口。
 */
