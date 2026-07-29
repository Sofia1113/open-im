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
