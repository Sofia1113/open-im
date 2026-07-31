import { defineConfig, devices } from "@playwright/test";

/**
 * Playwright E2E 配置
 *
 * ── 为什么需要 E2E ────────────────────────────────────────────
 *
 * 服务端有 130 个测试，Web 端只有一个 messageSync 单元测试。登录界面、消息收发、
 * 红包交互、WebRTC 通话全部只经过手工验收。这意味着任何一次前端改动都可能
 * 静默破坏核心路径，而 CI 全绿。
 *
 * 更关键的是：**契约的两端从未被一起验证过**。服务端测试用 MockMvc 直接调用
 * 控制器，Web 端测试用假数据。两者中间那一层——真实的 HTTP、真实的 WebSocket
 * 握手、真实的令牌刷新——没有任何自动化覆盖。第 7 轮那个"prod 下无任何登录入口"
 * 的缺陷正是这一类：每个组件单独看都对，连起来不通。
 *
 * ── 依赖 ─────────────────────────────────────────────────────
 *
 * 需要 E2E 专用的数据库：`docker compose --profile e2e up -d`
 * （`pnpm e2e` 会自动执行）。
 *
 * 用独立端口的实例而不是开发库，两个理由：
 *   1. 宿主机自带的 PostgreSQL 监听 5432 时会抢在 Docker 之前被连上，
 *      服务端启动直接失败（`role "openim" does not exist`）
 *   2. E2E 会注册账号、发消息、动余额，跑在开发库上会不断污染本地数据
 *
 * webServer 会自动拉起服务端与 Web 客户端；已在运行时复用（reuseExistingServer）。
 *
 * 首次运行需要装浏览器：`pnpm e2e:install`
 */

/** E2E 依赖的端口，与 docker-compose.yml 的 e2e profile 对应 */
const E2E_DATABASE_URL = "jdbc:postgresql://127.0.0.1:55432/openim";
const E2E_REDIS_URL = "redis://127.0.0.1:56379";
export default defineConfig({
  testDir: "./e2e",

  // 单个用例的上限。服务端冷启动（Flyway 迁移 + 连接池预热）已由 webServer
  // 的等待逻辑覆盖，这里只需覆盖单次交互
  timeout: 30_000,
  expect: { timeout: 10_000 },

  // 本地全量并行；CI 上串行。
  // 这些用例共享同一个数据库，并行会让"演示账号余额"这类断言相互干扰——
  // 用例内各自创建独立账号可以并行，但红包与钱包的断言依赖演示数据的初始状态。
  fullyParallel: false,
  workers: process.env.CI ? 1 : undefined,

  // CI 上失败重试一次：WebSocket 建连与实时投递有固有的时序波动，
  // 一次重试能滤掉偶发的抖动，而真实的破坏会稳定复现两次。
  // 本地不重试——本地重试会掩盖正在调试的问题。
  retries: process.env.CI ? 1 : 0,

  // 禁止在 CI 上留下 test.only：那会让其余用例被静默跳过而 CI 仍然绿
  forbidOnly: !!process.env.CI,

  reporter: process.env.CI
    ? [["github"], ["html", { open: "never" }], ["list"]]
    : [["list"], ["html", { open: "never" }]],

  use: {
    baseURL: "http://127.0.0.1:5173",
    // 失败时才留痕：全量录制会让产物迅速膨胀
    trace: "on-first-retry",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    // 明确设置而不是依赖默认值——时区与语言会影响消息时间的断言
    locale: "zh-CN",
    timezoneId: "Asia/Shanghai",
  },

  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        // 通话用例需要摄像头与麦克风。用假设备而不是真实硬件：
        // CI 上没有摄像头，且真实设备会让用例依赖运行环境。
        launchOptions: {
          args: [
            "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream",
          ],
        },
      },
    },
  ],

  webServer: [
    {
      command: "mvn -q -f apps/server/pom.xml spring-boot:run",
      // 等就绪探针而不是端口——端口开了不代表 Flyway 迁移跑完了，
      // 那个窗口里的请求会因为表不存在而失败
      url: "http://127.0.0.1:3001/actuator/health/readiness",
      timeout: 180_000,
      reuseExistingServer: !process.env.CI,
      stdout: "pipe",
      stderr: "pipe",
      env: {
        // 指向 e2e profile 的隔离实例。允许外部覆盖，便于在 CI 上
        // 使用 service container 的地址。
        DATABASE_URL: process.env.E2E_DATABASE_URL ?? E2E_DATABASE_URL,
        REDIS_URL: process.env.E2E_REDIS_URL ?? E2E_REDIS_URL,
        DATABASE_USER: "openim",
        DATABASE_PASSWORD: "openim",
        // 演示账号是多数用例的前置条件，必须加载种子数据
        DEMO_LOGIN_ENABLED: "true",
        //
        // 关闭限流。这是一个有代价的决定，理由如下：
        //
        // E2E 的<b>全部</b>请求来自同一个 IP（localhost），而凭证类端点按 IP
        // 计量且配额很紧——LOGIN 是 10 次/分钟。一个双客户端用例就要登录 2 次，
        // 五个用例跑下来必然撞上 429，于是测试会因为限流**正常工作**而失败。
        // 那种失败没有任何诊断价值，只会训练人忽略 E2E 的红色。
        //
        // 代价是 E2E 不覆盖限流行为。这个代价可以接受，因为限流已有
        // RateLimiterTest（10 个用例，含 Redis 故障降级）与
        // SecurityHardeningIntegrationTest 覆盖，且那些测试能精确控制配额消耗。
        //
        // 如果将来要在 E2E 里验证"前端如何呈现 429"，正确做法是单独一个
        // spec 文件、单独的 webServer 实例、开启限流并把配额调到很小，
        // 而不是让所有用例共享一个开启限流的实例。
        RATE_LIMIT_ENABLED: "false",
      },
    },
    {
      command: "pnpm --filter @open-im/web dev",
      url: "http://127.0.0.1:5173",
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
