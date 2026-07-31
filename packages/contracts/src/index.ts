import { z } from "zod";

// ============================================================
// OpenIM 共享类型契约
// 定义 Web 客户端与服务端之间共享的所有数据类型，使用 Zod
// 进行运行时校验，确保两端数据一致性。
// ============================================================

/** 用户信息 */
export const userSchema = z.object({
  id: z.string(),
  username: z.string(),
  displayName: z.string(),
  avatar: z.string(),
  status: z.enum(["online", "offline"]),
  lastSeenAt: z.string().nullable(),
});

export type User = z.infer<typeof userSchema>;

/**
 * 用户目录的三条读取路径
 *
 * `GET /users` 曾经返回**全站所有** ACTIVE 用户——任何登录用户可以枚举全部注册者，
 * 且响应体随注册量线性增长。收窄之后拆成三条语义不同的路径：
 *
 * | 用途 | 端点 | 可见范围 |
 * | --- | --- | --- |
 * | 我的联系人 | `GET /users` | 与我共处过至少一个会话的人 |
 * | 主动找人 | `GET /users/search?username=` | 需提供**完整**用户名 |
 * | 渲染已知的人 | `GET /users/lookup?ids=a,b,c` | 由 ID 指定，无边界 |
 *
 * 前两条是"发现新的人"，需要边界;第三条是"把已经拿到的 ID 换成昵称"——
 * 那些 ID 本来就通过鉴权过的接口交给了客户端，再校验一次没有意义。
 *
 * `search` 刻意**不做模糊匹配**：前缀搜索允许用几百次请求枚举出大部分用户名，
 * 等于把全站列表还回去。它同时受一个独立的限流策略保护（20 次/分钟），
 * 那个配额是可见性边界的一部分，不只是防滥用。
 */
export const USER_LOOKUP_MAX_IDS = 200;

/** 会话信息（单聊/群聊） */
export const conversationSchema = z.object({
  id: z.string(),
  type: z.enum(["direct", "group"]),
  title: z.string(),
  avatar: z.string(),
  memberIds: z.array(z.string()),
  lastMessage: z.string(),
  lastActiveAt: z.string(),
  unreadCount: z.number().int().nonnegative(),
});

export type Conversation = z.infer<typeof conversationSchema>;

/**
 * 群成员角色
 *
 * 服务端 conversation_members.role 自建表起就有这三个取值，但直到补上会话
 * 成员管理才真正生效。单聊不使用角色——两个成员都是 MEMBER，且不允许
 * 加人、踢人、转让。
 */
export const memberRoleSchema = z.enum(["MEMBER", "ADMIN", "OWNER"]);

export type MemberRole = z.infer<typeof memberRoleSchema>;

/**
 * 会话成员
 *
 * 带上 username/displayName/avatar，使"查看群成员"只需一次请求——
 * 只返回 userId 会迫使客户端再拉一遍用户列表。
 */
export const conversationMemberSchema = z.object({
  userId: z.string(),
  username: z.string(),
  displayName: z.string(),
  avatar: z.string(),
  role: memberRoleSchema,
  joinedAt: z.string(),
});

export type ConversationMember = z.infer<typeof conversationMemberSchema>;

/** 群成员上限，与服务端 ConversationService.MAX_GROUP_MEMBERS 保持一致 */
export const MAX_GROUP_MEMBERS = 500;

/**
 * 创建单聊
 *
 * 幂等：与同一个人重复调用返回已存在的会话而不是报错。这个接口的语义是
 * "打开与某人的对话"，不是"新建一条记录"。
 */
export const createDirectConversationSchema = z.object({
  peerId: z.string().min(1),
});

export type CreateDirectConversationInput = z.infer<
  typeof createDirectConversationSchema
>;

/**
 * 创建群聊
 *
 * memberIds 不含创建者——他自动成为 OWNER。上限 499 使加上创建者刚好是
 * MAX_GROUP_MEMBERS。
 */
export const createGroupConversationSchema = z.object({
  title: z.string().trim().min(1).max(64),
  avatar: z.string().max(16).optional(),
  memberIds: z.array(z.string().min(1)).min(1).max(MAX_GROUP_MEMBERS - 1),
});

export type CreateGroupConversationInput = z.infer<
  typeof createGroupConversationSchema
>;

/** 批量加人。单次上限 50——服务端在一个持有会话行锁的事务里处理这批人 */
export const addConversationMembersSchema = z.object({
  userIds: z.array(z.string().min(1)).min(1).max(50),
});

export type AddConversationMembersInput = z.infer<
  typeof addConversationMembersSchema
>;

/**
 * 指派或撤销管理员
 *
 * 不接受 OWNER：转让群主是独立端点（POST /conversations/{id}/transfer-owner），
 * 它还要降级原群主并改写会话的 owner_id。
 */
export const updateMemberRoleSchema = z.object({
  role: z.enum(["MEMBER", "ADMIN"]),
});

export type UpdateMemberRoleInput = z.infer<typeof updateMemberRoleSchema>;

export const transferOwnershipSchema = z.object({
  userId: z.string().min(1),
});

export type TransferOwnershipInput = z.infer<typeof transferOwnershipSchema>;

/** 修改群资料。目前只有标题 */
export const updateConversationSchema = z.object({
  title: z.string().trim().min(1).max(64),
});

export type UpdateConversationInput = z.infer<typeof updateConversationSchema>;

/**
 * 上报已读位置
 *
 * 未读数由 `next_seq - 1 - read_seq` 推导，服务端不单独维护计数字段，
 * 所以推进这个游标是唯一能让未读数下降的途径。游标只能前进，
 * 因此乱序到达的上报是安全的。
 */
export const markReadSchema = z.object({
  seq: z.number().int().nonnegative(),
});

export type MarkReadInput = z.infer<typeof markReadSchema>;

/** 消息类型：文本、系统通知、通话、红包 */
export const messageTypeSchema = z.enum([
  "text",
  "system",
  "call",
  "red_packet",
]);

/** 消息内容 */
export const messageSchema = z.object({
  id: z.string(),
  conversationId: z.string(),
  seq: z.number().int().positive(),
  senderId: z.string(),
  clientMessageId: z.string(),
  type: messageTypeSchema,
  content: z.record(z.unknown()),
  sentAt: z.string(),
});

export type Message = z.infer<typeof messageSchema>;

/**
 * 一页消息历史
 *
 * `hasMore` 由服务端给出，而不是让客户端按"返回条数 < 页大小"推断——
 * 那个推断在最后一页恰好装满时是错的：客户端会以为还有更多，再请求一次拿到
 * 空数组才停下，用户看到加载指示器闪一下却没有新内容出现。
 *
 * `messages` 按 `seq` 升序，可直接渲染。
 */
export const messagePageSchema = z.object({
  messages: z.array(messageSchema),
  hasMore: z.boolean(),
});

export type MessagePage = z.infer<typeof messagePageSchema>;

/**
 * 演示登录请求
 *
 * 用 username 而非 userId：服务端用户主键是 uuid，
 * 让客户端硬编码 uuid 既不可读，也会在重建演示数据后失效。
 */
export const demoLoginSchema = z.object({
  username: z.string().min(1).max(64),
  deviceId: z.string().min(3).max(128),
  deviceName: z.string().min(1).max(128),
});

/**
 * 凭证登录请求
 *
 * identifier 同时接受用户名和邮箱，由服务端判断是哪一种——让客户端先猜
 * 只会在用户输入含 @ 的用户名时出错。
 *
 * 这里不校验密码强度：登录时的强度提示会泄漏策略细节，
 * 且真正的判定必须在服务端。强度约束只出现在注册契约里。
 */
export const credentialLoginSchema = z.object({
  identifier: z.string().min(1).max(254),
  password: z.string().min(1).max(256),
  deviceId: z.string().min(3).max(128),
  deviceName: z.string().min(1).max(128),
});

export type CredentialLoginInput = z.infer<typeof credentialLoginSchema>;

/** 密码策略下限，与服务端 PasswordPolicy.MIN_LENGTH 保持一致 */
export const PASSWORD_MIN_LENGTH = 10;

/**
 * 注册请求
 *
 * 用户名限定小写字母、数字和下划线：混合大小写会让"同一个用户名"产生歧义，
 * 而服务端的唯一约束是区分大小写的。
 *
 * 密码上限 72 字节是 BCrypt 的硬限制（超出部分被静默截断），
 * 这里按字符数放宽到 256 只作为 payload 保护，字节级校验在服务端。
 */
export const registerSchema = z.object({
  username: z
    .string()
    .min(3)
    .max(32)
    .regex(/^[a-z0-9_]+$/, "用户名只能包含小写字母、数字和下划线"),
  email: z.string().email().max(254).optional(),
  displayName: z.string().trim().min(1).max(64),
  password: z.string().min(PASSWORD_MIN_LENGTH).max(256),
  deviceId: z.string().min(3).max(128),
  deviceName: z.string().min(1).max(128),
});

export type RegisterInput = z.infer<typeof registerSchema>;

/** 改密请求。成功后服务端会撤销全部设备会话，客户端需要重新登录 */
export const changePasswordSchema = z.object({
  currentPassword: z.string().min(1).max(256),
  newPassword: z.string().min(PASSWORD_MIN_LENGTH).max(256),
});

export type ChangePasswordInput = z.infer<typeof changePasswordSchema>;

/** 发送消息请求 */
export const sendMessageSchema = z.object({
  conversationId: z.string().min(1),
  clientMessageId: z.string().min(8).max(128),
  type: z.literal("text"),
  content: z.object({
    text: z.string().trim().min(1).max(4000),
  }),
});

export type SendMessageInput = z.infer<typeof sendMessageSchema>;

/** 红包模式：固定金额、等额、拼手气 */
export const redPacketModeSchema = z.enum(["fixed", "equal", "random"]);

/** 创建红包请求 */
export const createRedPacketSchema = z
  .object({
    conversationId: z.string().min(1),
    mode: redPacketModeSchema,
    totalAmount: z.number().int().min(1).max(20_000),
    totalCount: z.number().int().min(1).max(100),
    greeting: z.string().trim().min(1).max(60),
    clientMessageId: z.string().min(8).max(128),
    idempotencyKey: z.string().min(8).max(128),
  })
  .refine(
    (value) => value.totalAmount >= value.totalCount,
    "每个红包至少需要 1 分",
  );

export type CreateRedPacketInput = z.infer<typeof createRedPacketSchema>;

export const claimRedPacketSchema = z.object({
  idempotencyKey: z.string().min(8).max(128),
});

/** 红包状态：已发布、部分领取、已领完、已过期、已退款 */
export type RedPacketStatus =
  | "published"
  | "partially_claimed"
  | "exhausted"
  | "expired"
  | "refunded";

/** 红包领取记录 */
export interface RedPacketClaim {
  id: string;
  userId: string;
  amount: number;
  claimedAt: string;
}

/** 红包详情 */
export interface RedPacket {
  id: string;
  conversationId: string;
  senderId: string;
  mode: z.infer<typeof redPacketModeSchema>;
  totalAmount: number;
  totalCount: number;
  claimedAmount: number;
  claimedCount: number;
  greeting: string;
  status: RedPacketStatus;
  expiresAt: string;
  claims: RedPacketClaim[];
}

/** 钱包流水条目 */
export interface WalletEntry {
  id: string;
  type: "red_packet_send" | "red_packet_claim" | "red_packet_refund";
  amount: number;
  balanceAfter: number;
  businessId: string;
  createdAt: string;
}

/** 用户钱包 */
export interface Wallet {
  userId: string;
  balance: number;
  entries: WalletEntry[];
}

/** 创建通话请求 */
export const createCallSchema = z.object({
  conversationId: z.string().min(1),
  mediaType: z.enum(["audio", "video"]),
  inviteeIds: z.array(z.string()).min(1).max(5),
  idempotencyKey: z.string().min(8).max(128),
});

export type CreateCallInput = z.infer<typeof createCallSchema>;

/** 通话参与者状态：已邀请、已加入、已拒绝、已离开、未接听 */
export type CallParticipantState =
  | "invited"
  | "joined"
  | "rejected"
  | "left"
  | "missed";

/** 通话参与者 */
export interface CallParticipant {
  userId: string;
  state: CallParticipantState;
  joinedDeviceId?: string;
}

/** 通话房间 */
export interface CallRoom {
  id: string;
  conversationId: string;
  creatorId: string;
  mediaType: "audio" | "video";
  status: "ringing" | "active" | "ended";
  participants: CallParticipant[];
  createdAt: string;
  expiresAt: string;
  endedAt?: string;
  endReason?: string;
}

/** 会话（登录令牌） */
export interface Session {
  accessToken: string;
  refreshToken: string;
  expiresAt: string;
  user: User;
}

/** API 成功响应 */
export interface ApiSuccess<T> {
  data: T;
  requestId: string;
}

/** API 失败响应 */
export interface ApiFailure {
  error: {
    code: string;
    message: string;
    details?: unknown;
  };
  requestId: string;
}

/** WebSocket 消息确认类型 */
export type Ack<T> =
  | { ok: true; data: T }
  | { ok: false; error: { code: string; message: string } };

/** WebRTC 信令 */
export interface RtcSignal {
  roomId: string;
  targetUserId: string;
  sdp?: RTCSessionDescriptionInit;
  candidate?: RTCIceCandidateInit;
}

/** 服务端推送给客户端的事件映射 */
export interface ServerToClientEvents {
  "message:created": (payload: { eventId: string; message: Message }) => void;
  "call:invite": (payload: { room: CallRoom }) => void;
  "call:participant:joined": (payload: {
    roomId: string;
    participant: CallParticipant;
  }) => void;
  "call:participant:left": (payload: {
    roomId: string;
    userId: string;
    reason: string;
  }) => void;
  "call:answered-elsewhere": (payload: {
    roomId: string;
    acceptedDeviceId: string;
  }) => void;
  "call:ended": (payload: { roomId: string; reason: string }) => void;
  "rtc:offer": (payload: RtcSignal & { fromUserId: string }) => void;
  "rtc:answer": (payload: RtcSignal & { fromUserId: string }) => void;
  "rtc:ice-candidate": (
    payload: RtcSignal & { fromUserId: string },
  ) => void;
  "red-packet:updated": (payload: {
    eventId: string;
    redPacket: RedPacket;
  }) => void;
  "wallet:updated": (payload: { wallet: Wallet }) => void;

  /**
   * 有新会话与你相关（别人开了单聊、把你拉进群，或你自己在另一台设备上建的）。
   *
   * 载荷刻意只带 conversationId，客户端据此拉 GET /conversations/{id}。
   * 原因是会话视图<b>因人而异</b>：单聊的标题是对端的昵称，未读数是各自的游标。
   * 一个能同时发给所有收件人的完整会话对象并不存在——真发的话，群里每个人
   * 都会收到以某一个人视角渲染的标题。
   */
  "conversation:created": (payload: { conversationId: string }) => void;

  /**
   * 会话成员发生变化。收件人是<b>变更之后</b>的成员，因此新加入的人也会收到。
   *
   * 被移出的人收不到这个事件（他们已不在成员列表里），服务端会另外给他们发
   * conversation:removed。
   */
  "conversation:members-changed": (payload: {
    conversationId: string;
    joined: string[];
    removed: string[];
  }) => void;

  /** 你被移出会话，或你自己退群了（含你的其他设备）。客户端应从列表中移除它 */
  "conversation:removed": (payload: { conversationId: string }) => void;

  /** 会话资料或成员角色变化。客户端重新拉这一个会话与它的成员列表 */
  "conversation:updated": (payload: { conversationId: string }) => void;
}

/** 客户端发送给服务端的事件映射 */
export interface ClientToServerEvents {
  "message:send": (
    input: SendMessageInput,
    acknowledge: (result: Ack<Message>) => void,
  ) => void;
  "rtc:offer": (input: RtcSignal, acknowledge: (result: Ack<null>) => void) => void;
  "rtc:answer": (input: RtcSignal, acknowledge: (result: Ack<null>) => void) => void;
  "rtc:ice-candidate": (
    input: RtcSignal,
    acknowledge: (result: Ack<null>) => void,
  ) => void;
}
