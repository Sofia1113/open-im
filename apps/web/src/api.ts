import type { ApiFailure, ApiSuccess, Session } from "@open-im/contracts";

// ============================================================
// API 客户端层
// 封装 HTTP 请求、会话管理和设备标识，提供类型安全的 API 调用。
//
// 会话在本模块内维护为单一事实来源：访问令牌短期有效（默认 15 分钟），
// 由这里在过期前主动刷新、或在收到 401 时被动刷新，调用方无需感知。
// ============================================================

/** 服务端 API 基础 URL */
export const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:3001";
const SESSION_KEY = "open-im-session";
const DEVICE_KEY = "open-im-device-id";

/** 提前于过期时间刷新的余量，避免请求恰好卡在过期瞬间 */
const REFRESH_LEEWAY_MS = 60_000;

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

// ------------------------------------------------------------
// 会话状态
// ------------------------------------------------------------

let currentSession: Session | null = readSession();
const sessionListeners = new Set<(session: Session | null) => void>();

/**
 * 同一时刻只允许一个刷新请求在飞行。
 *
 * 服务端的刷新令牌是一次性的：并发提交同一个令牌会命中重放检测。
 * 宽限期能容忍跨标签页的重复提交，但同一标签页内没有理由发出多次，
 * 让后续调用共享同一个 Promise 即可。
 */
let refreshInFlight: Promise<Session | null> | null = null;

/** 读取当前会话 */
export function getSession(): Session | null {
  return currentSession;
}

/** 更新会话并通知订阅者；传入 null 表示登出 */
export function setSession(session: Session | null): void {
  currentSession = session;
  if (session) {
    saveSession(session);
  } else {
    clearSession();
  }
  sessionListeners.forEach((listener) => listener(session));
}

/** 订阅会话变化，返回取消订阅函数 */
export function subscribeSession(
  listener: (session: Session | null) => void,
): () => void {
  sessionListeners.add(listener);
  return () => sessionListeners.delete(listener);
}

/**
 * 返回可用于建立连接的访问令牌，必要时先刷新。
 *
 * WebSocket 在重连时调用它，因此长时间断线后恢复也能拿到有效令牌，
 * 而不是拿着早已过期的那个反复握手失败。
 */
export async function getFreshAccessToken(): Promise<string | null> {
  const session = await ensureFreshSession();
  return session?.accessToken ?? null;
}

/** 令牌是否已过期或即将过期 */
function isExpiring(session: Session): boolean {
  const expiresAt = Date.parse(session.expiresAt);
  if (Number.isNaN(expiresAt)) return true;
  return expiresAt - Date.now() <= REFRESH_LEEWAY_MS;
}

/** 确保当前会话的访问令牌可用，必要时刷新 */
async function ensureFreshSession(): Promise<Session | null> {
  if (!currentSession) return null;
  if (!isExpiring(currentSession)) return currentSession;
  return refreshSession();
}

/**
 * 刷新会话。
 *
 * 刷新失败一律视为会话终结并登出，不做重试：服务端每次刷新都会轮换令牌，
 * 用一个已失败的令牌重试只会越过宽限期并触发设备撤销。
 */
async function refreshSession(): Promise<Session | null> {
  if (refreshInFlight) return refreshInFlight;

  const session = currentSession;
  if (!session) return null;

  refreshInFlight = (async () => {
    try {
      const response = await fetch(`${API_URL}/api/v1/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: session.refreshToken }),
      });
      const payload = (await response.json()) as ApiSuccess<Session> | ApiFailure;
      if (!response.ok || "error" in payload) {
        setSession(null);
        return null;
      }
      setSession(payload.data);
      return payload.data;
    } catch {
      // 网络故障不应把用户踢出登录：保留会话，让调用方按普通网络错误处理
      return null;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

/**
 * 通用 API 请求封装
 * - 自动注入 Authorization 和 Content-Type 请求头
 * - 令牌临近过期时先刷新；收到 401 时刷新一次并重试
 * - 统一反序列化 JSON 响应，区分成功/失败
 *
 * `authenticated` 参数只决定是否附带凭证；实际使用的令牌始终取自本模块维护的
 * 最新会话，因此刷新后立即生效，调用方不必把新会话传回来。
 */
export async function apiRequest<T>(
  path: string,
  authenticated: Session | null,
  init: RequestInit = {},
): Promise<T> {
  if (!authenticated) {
    return sendRequest<T>(path, init, null);
  }

  const session = await ensureFreshSession();
  if (!session) {
    throw new ClientError("AUTH_REQUIRED", "登录已失效，请重新登录");
  }

  try {
    return await sendRequest<T>(path, init, session.accessToken);
  } catch (error) {
    // 令牌可能被服务端提前作废（远程下线、账号封禁），此时刷新一次再试
    if (!(error instanceof ClientError) || error.code !== "AUTH_REQUIRED") {
      throw error;
    }
    const refreshed = await refreshSession();
    if (!refreshed) {
      throw error;
    }
    return sendRequest<T>(path, init, refreshed.accessToken);
  }
}

async function sendRequest<T>(
  path: string,
  init: RequestInit,
  accessToken: string | null,
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

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

/** 退出当前设备：服务端撤销该设备会话，本地清除缓存 */
export async function logout(session: Session): Promise<void> {
  try {
    await apiRequest("/api/v1/sessions/current", session, { method: "DELETE" });
  } catch {
    // 服务端不可达时仍然清除本地会话，避免用户卡在已登录状态
  } finally {
    setSession(null);
  }
}
