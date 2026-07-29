import type {
  Ack,
  ClientToServerEvents,
  ServerToClientEvents,
} from "@open-im/contracts";

// ============================================================
// WebSocket 实时通信客户端
// 基于原生 WebSocket 实现，支持事件监听、命令发送和自动重连。
// ============================================================

/** WebSocket 生命周期事件 */
interface LifecycleEvents {
  connect: () => void;
  disconnect: () => void;
  connect_error: (error: Error) => void;
}

/** 实时事件类型：服务端推送事件和生命周期事件的联合 */
type RealtimeEvents = ServerToClientEvents & LifecycleEvents;
type Listener = (...args: never[]) => void;

/** 服务端推送的事件信封 */
interface EventEnvelope {
  kind: "event";
  event: keyof ServerToClientEvents;
  payload: unknown;
}

/** 服务端返回的命令确认信封 */
interface AckEnvelope {
  kind: "ack";
  commandId: string;
  ok: boolean;
  data?: unknown;
  error?: { code: string; message: string };
}

/** 待处理的命令（等待服务端 ACK） */
interface PendingCommand {
  acknowledge: (result: Ack<unknown>) => void;
  timeoutId: number;
}

/**
 * WebSocket 实时客户端
 *
 * 职责：
 * - 建立和维护 WebSocket 连接（含自动重连）
 * - 提供类型安全的事件监听和命令发送
 * - 处理命令超时和服务端 ACK
 */
export class RealtimeClient {
  private socket: WebSocket | null = null;
  private readonly listeners = new Map<string, Set<Listener>>();
  private readonly pending = new Map<string, PendingCommand>();
  private manuallyClosed = false;
  private reconnectAttempts = 0;
  private reconnectTimer: number | null = null;

  /**
   * @param tokenProvider 每次握手时取一次访问令牌。
   *   访问令牌只有 15 分钟有效期，构造时固定下来会导致长时间断线后
   *   带着早已过期的令牌反复重连失败；由调用方在这里返回（必要时刷新过的）最新令牌。
   */
  constructor(
    private readonly apiUrl: string,
    private readonly tokenProvider: () => Promise<string | null>,
    private readonly deviceId: string,
  ) {}

  /** 建立 WebSocket 连接 */
  connect(): void {
    this.manuallyClosed = false;
    void this.openSocket();
  }

  /** 手动关闭连接，取消重连调度 */
  disconnect(): void {
    this.manuallyClosed = true;
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.socket?.close(1000, "client disconnect");
    this.socket = null;
    this.rejectPending("CONNECTION_CLOSED", "实时连接已关闭");
  }

  /** 注册事件监听器 */
  on<Event extends keyof RealtimeEvents>(
    event: Event,
    listener: RealtimeEvents[Event],
  ): void {
    const listeners = this.listeners.get(event) ?? new Set<Listener>();
    listeners.add(listener as Listener);
    this.listeners.set(event, listeners);
  }

  /** 移除事件监听器 */
  off<Event extends keyof RealtimeEvents>(
    event: Event,
    listener: RealtimeEvents[Event],
  ): void {
    this.listeners.get(event)?.delete(listener as Listener);
  }

  /**
   * 发送命令到服务端
   * - 自动分配 commandId 并注册超时处理（15 秒）
   * - 若连接未就绪，立即回调错误
   */
  emit<Event extends keyof ClientToServerEvents>(
    event: Event,
    ...args: Parameters<ClientToServerEvents[Event]>
  ): void {
    const [payload, acknowledge] = args as [
      unknown,
      (result: Ack<unknown>) => void,
    ];
    if (this.socket?.readyState !== WebSocket.OPEN) {
      acknowledge({
        ok: false,
        error: { code: "CONNECTION_CLOSED", message: "实时连接尚未就绪" },
      });
      return;
    }

    const commandId = crypto.randomUUID();
    const timeoutId = window.setTimeout(() => {
      const command = this.pending.get(commandId);
      if (!command) return;
      this.pending.delete(commandId);
      command.acknowledge({
        ok: false,
        error: { code: "TIMEOUT", message: "实时请求超时，请重试" },
      });
    }, 15_000);
    this.pending.set(commandId, { acknowledge, timeoutId });
    this.socket.send(JSON.stringify({
      kind: "command",
      commandId,
      event,
      payload,
    }));
  }

  /**
   * 创建 WebSocket 连接并绑定事件处理。
   *
   * 永不向外抛出：调用方以 fire-and-forget 方式触发它，
   * 未捕获的 rejection 会中断重连链路。
   */
  private async openSocket(): Promise<void> {
    if (
      this.socket?.readyState === WebSocket.OPEN ||
      this.socket?.readyState === WebSocket.CONNECTING
    ) {
      return;
    }

    let token: string | null;
    try {
      token = await this.tokenProvider();
    } catch {
      this.dispatch("connect_error", new Error("实时连接失败"));
      if (!this.manuallyClosed) this.scheduleReconnect();
      return;
    }

    if (token === null) {
      // 会话已失效：继续重连只会不断收到 401，交由上层引导重新登录
      this.dispatch("connect_error", new Error("登录已失效，请重新登录"));
      return;
    }
    // 取令牌是异步的，期间可能已被 disconnect() 取消
    if (this.manuallyClosed) return;

    const url = new URL(this.apiUrl);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    url.pathname = "/ws";
    url.search = new URLSearchParams({
      token,
      deviceId: this.deviceId,
    }).toString();
    const socket = new WebSocket(url);
    this.socket = socket;

    socket.onopen = () => {
      this.reconnectAttempts = 0;
      this.dispatch("connect");
    };
    socket.onmessage = (message) => this.handleMessage(message.data);
    socket.onerror = () => {
      this.dispatch("connect_error", new Error("实时连接失败"));
    };
    socket.onclose = () => {
      if (this.socket === socket) this.socket = null;
      this.dispatch("disconnect");
      this.rejectPending("CONNECTION_CLOSED", "实时连接已断开");
      if (!this.manuallyClosed) this.scheduleReconnect();
    };
  }

  /** 解析收到的消息：区分事件推送和命令确认 */
  private handleMessage(raw: unknown): void {
    if (typeof raw !== "string") return;
    try {
      const envelope = JSON.parse(raw) as EventEnvelope | AckEnvelope;
      if (envelope.kind === "event") {
        this.dispatch(envelope.event, envelope.payload);
        return;
      }
      if (envelope.kind === "ack") {
        const command = this.pending.get(envelope.commandId);
        if (!command) return;
        window.clearTimeout(command.timeoutId);
        this.pending.delete(envelope.commandId);
        command.acknowledge(
          envelope.ok
            ? { ok: true, data: envelope.data }
            : {
                ok: false,
                error: envelope.error ?? {
                  code: "INTERNAL_ERROR",
                  message: "实时请求失败",
                },
              },
        );
      }
    } catch {
      this.dispatch("connect_error", new Error("收到无法解析的实时事件"));
    }
  }

  /** 派发事件到所有注册的监听器 */
  private dispatch(event: string, ...args: unknown[]): void {
    for (const listener of this.listeners.get(event) ?? []) {
      (listener as (...values: unknown[]) => void)(...args);
    }
  }

  /**
   * 指数退避重连
   * 首次重连延迟 1 秒，每次翻倍，最大 30 秒
   */
  private scheduleReconnect(): void {
    const delay = Math.min(30_000, 1_000 * 2 ** this.reconnectAttempts);
    this.reconnectAttempts += 1;
    this.reconnectTimer = window.setTimeout(() => void this.openSocket(), delay);
  }

  /** 拒绝所有待处理命令（连接断开时调用） */
  private rejectPending(code: string, message: string): void {
    for (const command of this.pending.values()) {
      window.clearTimeout(command.timeoutId);
      command.acknowledge({ ok: false, error: { code, message } });
    }
    this.pending.clear();
  }
}
