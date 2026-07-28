import type { ApiFailure, ApiSuccess, Session } from "@open-im/contracts";

// ============================================================
// API 客户端层
// 封装 HTTP 请求、会话管理和设备标识，提供类型安全的 API 调用。
// ============================================================

/** 服务端 API 基础 URL */
export const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:3001";
const SESSION_KEY = "open-im-session";
const DEVICE_KEY = "open-im-device-id";

/** 自定义 API 请求错误，携带错误码以供上层判断 */
export class ClientError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly details?: unknown,
  ) {
    super(message);
  }
}

/** 获取或生成本地持久化的设备 ID */
export function getDeviceId(): string {
  const existing = localStorage.getItem(DEVICE_KEY);
  if (existing) return existing;
  const deviceId = `web-${crypto.randomUUID()}`;
  localStorage.setItem(DEVICE_KEY, deviceId);
  return deviceId;
}

/** 从 localStorage 读取缓存会话 */
export function readSession(): Session | null {
  const value = localStorage.getItem(SESSION_KEY);
  if (!value) return null;
  try {
    return JSON.parse(value) as Session;
  } catch {
    localStorage.removeItem(SESSION_KEY);
    return null;
  }
}

/** 将会话持久化到 localStorage */
export function saveSession(session: Session): void {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

/** 清除本地缓存的会话 */
export function clearSession(): void {
  localStorage.removeItem(SESSION_KEY);
}

/**
 * 通用 API 请求封装
 * - 自动注入 Authorization 和 Content-Type 请求头
 * - 统一反序列化 JSON 响应，区分成功/失败
 */
export async function apiRequest<T>(
  path: string,
  session: Session | null,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (session) headers.set("Authorization", `Bearer ${session.accessToken}`);
  const response = await fetch(`${API_URL}${path}`, { ...init, headers });
  const payload = (await response.json()) as ApiSuccess<T> | ApiFailure;
  if (!response.ok || "error" in payload) {
    const failure = payload as ApiFailure;
    throw new ClientError(
      failure.error?.code ?? "NETWORK_ERROR",
      failure.error?.message ?? "请求失败，请稍后重试",
      failure.error?.details,
    );
  }
  return payload.data;
}
