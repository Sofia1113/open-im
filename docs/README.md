# OpenIM 文档索引

## 产品

1. [PRD](./00-product/PRD.md)：目标、范围、角色、核心功能、指标和发布分期。
2. [用户故事与业务规则](./00-product/USER_STORIES.md)：Given/When/Then、状态机、异常与权限。
3. [路线图](./00-product/ROADMAP.md)：M0–M3、优先级与 Definition of Done。

## 架构

1. [总体架构](./01-architecture/ARCHITECTURE.md)：技术选型、模块边界、一致性、部署与 ADR。
2. [数据模型](./01-architecture/DATA_MODEL.md)：用户、会话、消息、通话、红包、钱包与 Outbox。
3. [API 与实时事件](./01-architecture/API_AND_EVENTS.md)：REST、原生 WebSocket、RTC 信令和错误码。
4. [认证与缓存时序图](./01-architecture/AUTH_SEQUENCE.md)：登录、Token 刷新轮换、请求鉴权与会话有效性缓存。

## 专项

1. [多端音视频](./02-features/RTC.md)：Mesh/SFU、通话状态机、多设备接听和弱网恢复。
2. [红包与虚拟账务](./02-features/RED_PACKET.md)：分配算法、领取事务、退款、对账和风控。
3. [安全与隐私](./02-features/SECURITY.md)：威胁模型、认证授权、内容安全和上线清单。

## 交付

1. [MVP 验收](./03-delivery/ACCEPTANCE.md)：环境、P0 场景、自动化分层和阻断条件。
2. [实现状态](./03-delivery/IMPLEMENTATION_STATUS.md)：已完成、部分完成、待生产化与验证证据。

阅读建议：产品评审从 PRD 开始；客户端/服务端开发从总体架构和契约开始；音视频与钱包开发必须同时阅读对应专项和安全基线。
