# 安全策略

## 支持的版本

| 版本 | 是否支持 |
| --- | --- |
| 0.1.x | ✅ 当前开发版本 |

> ⚠️ **当前版本为演示 MVP，不适合生产部署。** 数据保存在单进程内存中，缺少限流、令牌撤销和可观测性。生产化改造进度见 [IMPLEMENTATION_STATUS.md](../docs/03-delivery/IMPLEMENTATION_STATUS.md)。

## 报告漏洞

**请勿通过公开 issue 报告安全漏洞。**

请通过 GitHub [Private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guiding-contributors-to-report-security-vulnerabilities/privately-reporting-a-security-vulnerability) 提交，或发送邮件至维护者。

<!-- TODO: 补充安全联系邮箱，例如 security@example.com -->

报告时请尽量包含：

- 漏洞类型与受影响的组件（服务端 / Web 客户端 / 契约）
- 复现步骤或 PoC
- 你评估的影响范围
- 可能的修复建议（可选）

## 响应流程

| 阶段 | 目标时限 |
| --- | --- |
| 确认收到 | 3 个工作日内 |
| 初步评估与定级 | 7 个工作日内 |
| 修复并发布 | 视严重程度，高危优先 |

我们会在修复发布后于 [CHANGELOG.md](../CHANGELOG.md) 中致谢报告者（除非你要求匿名）。

## 关注范围

以下属于有效的安全报告范围：

- 认证绕过、令牌伪造、会话固定
- 越权访问会话、消息、通话或红包（IDOR）
- WebSocket 事件伪造与信令滥用
- 红包并发套利、账务不一致
- XSS、CSRF、CORS 配置缺陷
- 敏感信息泄漏（令牌、消息正文、完整 IP）

以下**暂不属于**有效范围（已知的演示架构限制，已在文档中公开记录）：

- 内存仓储导致的数据丢失
- 演示登录账号（仅在非 `prod` profile 下启用）
- 缺少限流导致的资源耗尽
- 公共 STUN / 未部署 TURN 导致的 NAT 穿透失败

这些限制正在按路线图修复，重复报告不会加速处理。

## 安全设计基线

完整的威胁模型、身份与会话、授权、输入校验、钱包账务和合规要求见 [docs/02-features/SECURITY.md](../docs/02-features/SECURITY.md)。
