import type {
  CallParticipant,
  CallRoom,
  Conversation,
  CreateRedPacketInput,
  Message,
  RedPacket,
  Session,
  User,
  Wallet,
} from "@open-im/contracts";
import {
  type FormEvent,
  type ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  API_URL,
  apiRequest,
  clearSession,
  getDeviceId,
  readSession,
  saveSession,
} from "./api";
import { RealtimeClient } from "./realtime";
import {
  collectMessagePages,
  latestMessageSeq,
  mergeMessages,
} from "./messageSync";
import { useRtcCall } from "./useRtcCall";

// ============================================================
// OpenIM Web 客户端 — 主应用组件
// 包含登录、会话列表、消息列表、红包、音视频通话等全部 UI。
// ============================================================

type ImSocket = RealtimeClient;

/** 人民币格式化器（分转元显示） */
const currency = new Intl.NumberFormat("zh-CN", {
  style: "currency",
  currency: "CNY",
});

/**
 * 应用根组件
 * - 检查本地缓存的会话，有则进入工作台，无则展示登录页
 */
export function App() {
  const [session, setSession] = useState<Session | null>(() => readSession());

  if (!session) {
    return (
      <LoginScreen
        onLogin={(nextSession) => {
          saveSession(nextSession);
          setSession(nextSession);
        }}
      />
    );
  }

  return (
    <Workspace
      session={session}
      onLogout={() => {
        clearSession();
        setSession(null);
      }}
    />
  );
}

/** 登录选择页 */
function LoginScreen({ onLogin }: { onLogin: (session: Session) => void }) {
  const [loadingId, setLoadingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  /** 演示账号列表。id 是登录用的 username，服务端据此解析真实的 uuid 主键 */
  const demoUsers = [
    { id: "alice", name: "Alice 林夏", avatar: "林", color: "mint" },
    { id: "bob", name: "Bob 陈默", avatar: "陈", color: "blue" },
    { id: "carol", name: "Carol 周晴", avatar: "周", color: "amber" },
  ];

  /** 执行演示登录 */
  const login = async (username: string) => {
    setLoadingId(username);
    setError(null);
    try {
      const session = await apiRequest<Session>("/api/v1/auth/demo-login", null, {
        method: "POST",
        body: JSON.stringify({
          username,
          deviceId: getDeviceId(),
          deviceName: navigator.userAgent.includes("Mac") ? "Mac 浏览器" : "Web 浏览器",
        }),
      });
      onLogin(session);
    } catch (reason) {
      setError(
        reason instanceof Error
          ? reason.message
          : "无法连接服务端，请确认 pnpm dev 已启动",
      );
    } finally {
      setLoadingId(null);
    }
  };

  return (
    <main className="login-page">
      <div className="login-glow login-glow-a" />
      <div className="login-glow login-glow-b" />
      <section className="login-card">
        <div className="brand-mark brand-mark-large">O</div>
        <p className="eyebrow">OPENIM · LOCAL MVP</p>
        <h1>让每一次连接，都自然发生</h1>
        <p className="login-subtitle">
          跨端消息、多人音视频与虚拟红包，都在一个轻盈的工作台里。
        </p>
        <div className="demo-label">
          <span /> 选择演示身份 <span />
        </div>
        <div className="demo-users">
          {demoUsers.map((user) => (
            <button
              className="demo-user"
              disabled={loadingId !== null}
              key={user.id}
              onClick={() => void login(user.id)}
            >
              <Avatar label={user.avatar} tone={user.color} size="large" />
              <span>
                <strong>{user.name}</strong>
                <small>{loadingId === user.id ? "正在登录…" : "进入工作台"}</small>
              </span>
              <i>→</i>
            </button>
          ))}
        </div>
        {error && <div className="inline-error">{error}</div>}
        <p className="demo-notice">本地演示使用虚拟余额，不连接真实支付。</p>
      </section>
      <p className="login-footer">OpenIM · Product & Engineering Preview</p>
    </main>
  );
}

/**
 * 主工作台组件
 * - 管理用户、会话、消息、钱包、红包和通话状态
 * - 建立 WebSocket 实时连接
 * - 渲染侧边栏导航、会话列表、聊天面板和各类弹窗
 */
function Workspace({
  session,
  onLogout,
}: {
  session: Session;
  onLogout: () => void;
}) {
  const [users, setUsers] = useState<User[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Record<string, Message[]>>({});
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [redPackets, setRedPackets] = useState<Record<string, RedPacket>>({});
  const [selectedPacketId, setSelectedPacketId] = useState<string | null>(null);
  const [showRedPacketForm, setShowRedPacketForm] = useState(false);
  const [socket, setSocket] = useState<ImSocket | null>(null);
  const [connected, setConnected] = useState(false);
  const [incomingCall, setIncomingCall] = useState<CallRoom | null>(null);
  const [currentCall, setCurrentCall] = useState<CallRoom | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [activeNav, setActiveNav] = useState<"chat" | "contacts">("chat");
  const conversationsRef = useRef(conversations);
  const messagesRef = useRef(messages);
  const currentCallRef = useRef(currentCall);
  const hydratedConversationsRef = useRef(new Set<string>());

  conversationsRef.current = conversations;
  messagesRef.current = messages;
  currentCallRef.current = currentCall;

  const showToast = useCallback((message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(null), 2800);
  }, []);

  /** 向会话列表追加消息并更新会话摘要 */
  const addMessage = useCallback((message: Message) => {
    setMessages((current) => {
      const items = current[message.conversationId] ?? [];
      return {
        ...current,
        [message.conversationId]: mergeMessages(items, [message]),
      };
    });
    setConversations((current) =>
      current
        .map((conversation) =>
          conversation.id === message.conversationId
            ? {
                ...conversation,
                lastMessage: messagePreview(message),
                lastActiveAt: message.sentAt,
              }
            : conversation,
        )
        .sort((left, right) => right.lastActiveAt.localeCompare(left.lastActiveAt)),
    );
  }, []);

  const syncConversation = useCallback(async (
    conversationId: string,
    afterSeq: number,
  ) => {
    const incoming = await collectMessagePages(afterSeq, (cursor) =>
      apiRequest<Message[]>(
        `/api/v1/conversations/${conversationId}/messages?afterSeq=${cursor}`,
        session,
      ),
    );
    if (incoming.length === 0) return;
    setMessages((current) => ({
      ...current,
      [conversationId]: mergeMessages(current[conversationId] ?? [], incoming),
    }));
    const latest = incoming.at(-1);
    if (latest) {
      setConversations((current) => current
        .map((conversation) => conversation.id === conversationId
          ? {
              ...conversation,
              lastMessage: messagePreview(latest),
              lastActiveAt: latest.sentAt,
            }
          : conversation)
        .sort((left, right) => right.lastActiveAt.localeCompare(left.lastActiveAt)));
    }
  }, [session]);

  const syncKnownConversations = useCallback(async () => {
    await Promise.all(conversationsRef.current.map((conversation) => {
      const localMessages = messagesRef.current[conversation.id] ?? [];
      return syncConversation(conversation.id, latestMessageSeq(localMessages));
    }));
  }, [syncConversation]);

  /** 初始化：加载用户列表、会话列表和钱包余额 */
  useEffect(() => {
    let active = true;
    void Promise.all([
      apiRequest<User[]>("/api/v1/users", session),
      apiRequest<Conversation[]>("/api/v1/conversations", session),
      apiRequest<Wallet>("/api/v1/wallet", session),
    ])
      .then(([nextUsers, nextConversations, nextWallet]) => {
        if (!active) return;
        setUsers(nextUsers);
        setConversations(nextConversations);
        setWallet(nextWallet);
        setSelectedId((current) => current ?? nextConversations[0]?.id ?? null);
      })
      .catch((error: unknown) => {
        showToast(error instanceof Error ? error.message : "初始化失败");
      });
    return () => {
      active = false;
    };
  }, [session, showToast]);

  /** 建立 WebSocket 实时连接并注册事件监听 */
  useEffect(() => {
    const realtime = new RealtimeClient(
      API_URL,
      session.accessToken,
      getDeviceId(),
    );
    setSocket(realtime);

    realtime.on("connect", () => {
      setConnected(true);
      void syncKnownConversations().catch((error: unknown) => {
        showToast(error instanceof Error ? error.message : "消息同步失败");
      });
    });
    realtime.on("disconnect", () => setConnected(false));
    realtime.on("message:created", ({ message }) => addMessage(message));
    realtime.on("wallet:updated", ({ wallet: nextWallet }) => setWallet(nextWallet));
    realtime.on("red-packet:updated", ({ redPacket }) => {
      setRedPackets((current) => ({ ...current, [redPacket.id]: redPacket }));
    });
    realtime.on("call:invite", ({ room }) => {
      if (currentCallRef.current) return;
      setIncomingCall(room);
    });
    realtime.on("call:answered-elsewhere", ({ roomId, acceptedDeviceId }) => {
      if (acceptedDeviceId !== getDeviceId()) {
        setIncomingCall((current) => (current?.id === roomId ? null : current));
      }
    });
    realtime.on("call:participant:joined", ({ roomId, participant }) => {
      setCurrentCall((room) => updateParticipant(room, roomId, participant));
    });
    realtime.on("call:participant:left", ({ roomId, userId }) => {
      setCurrentCall((room) =>
        updateParticipant(room, roomId, { userId, state: "left" }),
      );
    });
    realtime.on("call:ended", ({ roomId }) => {
      setCurrentCall((room) => (room?.id === roomId ? null : room));
      setIncomingCall((room) => (room?.id === roomId ? null : room));
      showToast("通话已结束");
    });
    realtime.on("connect_error", (error) => {
      if (error.message === "AUTH_REQUIRED") showToast("登录已失效，请重新登录");
    });
    realtime.connect();

    return () => {
      realtime.disconnect();
      setSocket(null);
    };
  }, [addMessage, session, showToast, syncKnownConversations]);

  /** 选中会话时加载历史消息 */
  useEffect(() => {
    if (!selectedId || hydratedConversationsRef.current.has(selectedId)) return;
    let active = true;
    void syncConversation(selectedId, 0)
      .then(() => {
        if (active) hydratedConversationsRef.current.add(selectedId);
      })
      .catch((error: unknown) => {
        showToast(error instanceof Error ? error.message : "消息加载失败");
      });
    return () => {
      active = false;
    };
  }, [selectedId, showToast, syncConversation]);

  const selectedConversation = conversations.find(
    (conversation) => conversation.id === selectedId,
  );
  const selectedMessages = selectedId ? messages[selectedId] ?? [] : [];
  const userMap = useMemo(
    () => new Map(users.map((user) => [user.id, user])),
    [users],
  );
  const selectedPacket = selectedPacketId
    ? redPackets[selectedPacketId] ?? null
    : null;

  /** 打开红包详情（从服务端加载或使用缓存） */
  const openRedPacket = async (packetId: string) => {
    setSelectedPacketId(packetId);
    if (redPackets[packetId]) return;
    try {
      const packet = await apiRequest<RedPacket>(`/api/v1/red-packets/${packetId}`, session);
      setRedPackets((current) => ({ ...current, [packet.id]: packet }));
    } catch (error) {
      showToast(error instanceof Error ? error.message : "红包加载失败");
      setSelectedPacketId(null);
    }
  };

  /** 通过 WebSocket 发送文本消息 */
  const sendMessage = (text: string) => {
    if (!socket || !selectedConversation) return;
    socket.emit(
      "message:send",
      {
        conversationId: selectedConversation.id,
        clientMessageId: crypto.randomUUID(),
        type: "text",
        content: { text },
      },
      (result) => {
        if (result.ok) addMessage(result.data);
        else showToast(result.error.message);
      },
    );
  };

  /** 创建红包（HTTP 请求） */
  const createPacket = async (input: CreateRedPacketInput) => {
    const result = await apiRequest<{
      redPacket: RedPacket;
      message: Message;
      wallet: Wallet;
    }>("/api/v1/red-packets", session, {
      method: "POST",
      body: JSON.stringify(input),
    });
    setWallet(result.wallet);
    setRedPackets((current) => ({
      ...current,
      [result.redPacket.id]: result.redPacket,
    }));
    addMessage(result.message);
    setShowRedPacketForm(false);
    showToast("红包已发送");
  };

  /** 领取红包（HTTP 请求） */
  const claimPacket = async () => {
    if (!selectedPacketId) return;
    try {
      const result = await apiRequest<{
        redPacket: RedPacket;
        amount: number;
        wallet: Wallet;
        alreadyClaimed: boolean;
      }>(`/api/v1/red-packets/${selectedPacketId}/claim`, session, {
        method: "POST",
        body: JSON.stringify({ idempotencyKey: crypto.randomUUID() }),
      });
      setRedPackets((current) => ({
        ...current,
        [result.redPacket.id]: result.redPacket,
      }));
      setWallet(result.wallet);
      showToast(
        result.alreadyClaimed
          ? `你已经领取过 ${currency.format(result.amount / 100)}`
          : `领取成功：${currency.format(result.amount / 100)}`,
      );
    } catch (error) {
      showToast(error instanceof Error ? error.message : "领取失败");
    }
  };

  /** 发起音视频通话 */
  const startCall = async (mediaType: "audio" | "video") => {
    if (!selectedConversation) return;
    const inviteeIds = selectedConversation.memberIds.filter(
      (id) => id !== session.user.id,
    );
    try {
      const room = await apiRequest<CallRoom>("/api/v1/calls", session, {
        method: "POST",
        body: JSON.stringify({
          conversationId: selectedConversation.id,
          mediaType,
          inviteeIds: inviteeIds.slice(0, 5),
          idempotencyKey: crypto.randomUUID(),
        }),
      });
      setCurrentCall(room);
    } catch (error) {
      showToast(error instanceof Error ? error.message : "无法发起通话");
    }
  };

  /** 接听来电 */
  const acceptCall = async () => {
    if (!incomingCall) return;
    const roomId = incomingCall.id;
    try {
      const room = await apiRequest<CallRoom>(`/api/v1/calls/${roomId}/accept`, session, {
        method: "POST",
      });
      setIncomingCall(null);
      setCurrentCall(room);
    } catch (error) {
      showToast(error instanceof Error ? error.message : "接听失败");
    }
  };

  /** 拒绝来电 */
  const rejectCall = async () => {
    if (!incomingCall) return;
    const roomId = incomingCall.id;
    setIncomingCall(null);
    try {
      await apiRequest(`/api/v1/calls/${roomId}/reject`, session, { method: "POST" });
    } catch (error) {
      showToast(error instanceof Error ? error.message : "操作失败");
    }
  };

  /** 离开当前通话 */
  const leaveCall = async () => {
    if (!currentCall) return;
    const roomId = currentCall.id;
    setCurrentCall(null);
    try {
      await apiRequest(`/api/v1/calls/${roomId}/leave`, session, { method: "POST" });
    } catch (error) {
      showToast(error instanceof Error ? error.message : "离开通话失败");
    }
  };

  const filteredConversations = conversations.filter((conversation) =>
    `${conversation.title} ${conversation.lastMessage}`
      .toLowerCase()
      .includes(search.toLowerCase()),
  );

  return (
    <main className="app-shell">
      <aside className="global-nav">
        <div className="brand-mark">O</div>
        <nav aria-label="主导航">
          <NavButton
            active={activeNav === "chat"}
            icon="chat"
            label="消息"
            onClick={() => setActiveNav("chat")}
          />
          <NavButton
            active={activeNav === "contacts"}
            icon="people"
            label="联系人"
            onClick={() => setActiveNav("contacts")}
          />
        </nav>
        <div className="nav-bottom">
          <button className="nav-icon-button" title="退出登录" onClick={onLogout}>
            <Icon name="logout" />
          </button>
          <Avatar label={session.user.avatar} tone="mint" />
        </div>
      </aside>

      <aside className="conversation-panel">
        <header className="panel-header">
          <div>
            <p className="eyebrow">OPENIM</p>
            <h2>{activeNav === "chat" ? "消息" : "联系人"}</h2>
          </div>
          <span className={`connection-pill ${connected ? "online" : ""}`}>
            <i /> {connected ? "在线" : "重连中"}
          </span>
        </header>
        <label className="search-box">
          <Icon name="search" />
          <input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="搜索会话"
          />
          <kbd>⌘ K</kbd>
        </label>

        {activeNav === "chat" ? (
          <div className="conversation-list">
            {filteredConversations.map((conversation) => (
              <button
                className={`conversation-item ${selectedId === conversation.id ? "selected" : ""}`}
                key={conversation.id}
                onClick={() => setSelectedId(conversation.id)}
              >
                <Avatar label={conversation.avatar} tone={conversation.type === "group" ? "blue" : "mint"} />
                <span className="conversation-copy">
                  <span className="conversation-title-row">
                    <strong>{conversation.title}</strong>
                    <time>{formatListTime(conversation.lastActiveAt)}</time>
                  </span>
                  <span className="conversation-preview-row">
                    <small>{conversation.lastMessage || "开始聊天"}</small>
                    {conversation.unreadCount > 0 && <b>{conversation.unreadCount}</b>}
                  </span>
                </span>
              </button>
            ))}
          </div>
        ) : (
          <div className="contact-list">
            {users
              .filter((user) => user.id !== session.user.id)
              .map((user) => (
                <div className="contact-item" key={user.id}>
                  <div className="avatar-with-status">
                    <Avatar label={user.avatar} tone="blue" />
                    <i className={user.status} />
                  </div>
                  <span>
                    <strong>{user.displayName}</strong>
                    <small>{user.status === "online" ? "在线" : "最近在线"}</small>
                  </span>
                </div>
              ))}
          </div>
        )}

        <div className="wallet-card">
          <span>
            <small>演示钱包余额</small>
            <strong>{currency.format((wallet?.balance ?? 0) / 100)}</strong>
          </span>
          <Icon name="wallet" />
        </div>
      </aside>

      <section className="chat-panel">
        {selectedConversation ? (
          <>
            <header className="chat-header">
              <div className="chat-identity">
                <Avatar label={selectedConversation.avatar} tone="blue" />
                <span>
                  <h3>{selectedConversation.title}</h3>
                  <small>
                    {selectedConversation.type === "group"
                      ? `${selectedConversation.memberIds.length} 位成员`
                      : "端到端实时连接"}
                  </small>
                </span>
              </div>
              <div className="chat-actions">
                <button title="语音通话" onClick={() => void startCall("audio")}>
                  <Icon name="phone" />
                </button>
                <button title="视频通话" onClick={() => void startCall("video")}>
                  <Icon name="video" />
                </button>
                <button title="会话信息">
                  <Icon name="more" />
                </button>
              </div>
            </header>
            <MessageList
              messages={selectedMessages}
              currentUser={session.user}
              userMap={userMap}
              onOpenRedPacket={(id) => void openRedPacket(id)}
            />
            <Composer
              disabled={!connected}
              onSend={sendMessage}
              onRedPacket={() => setShowRedPacketForm(true)}
            />
          </>
        ) : (
          <div className="empty-chat">
            <div className="empty-orbit"><span>O</span></div>
            <h3>选择一个会话</h3>
            <p>开始发送消息、发起通话或分享红包。</p>
          </div>
        )}
      </section>

      {showRedPacketForm && selectedConversation && (
        <RedPacketForm
          conversation={selectedConversation}
          onClose={() => setShowRedPacketForm(false)}
          onSubmit={createPacket}
        />
      )}
      {selectedPacketId && (
        <RedPacketDetail
          packet={selectedPacket}
          userMap={userMap}
          currentUserId={session.user.id}
          onClaim={() => void claimPacket()}
          onClose={() => setSelectedPacketId(null)}
        />
      )}
      {incomingCall && (
        <IncomingCall
          room={incomingCall}
          caller={userMap.get(incomingCall.creatorId)}
          onAccept={() => void acceptCall()}
          onReject={() => void rejectCall()}
        />
      )}
      {currentCall && (
        <CallOverlay
          call={currentCall}
          socket={socket}
          user={session.user}
          userMap={userMap}
          onLeave={() => void leaveCall()}
        />
      )}
      {toast && <div className="toast">{toast}</div>}
    </main>
  );
}

/** 消息列表组件 */
function MessageList({
  messages,
  currentUser,
  userMap,
  onOpenRedPacket,
}: {
  messages: Message[];
  currentUser: User;
  userMap: Map<string, User>;
  onOpenRedPacket: (id: string) => void;
}) {
  const endRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  return (
    <div className="message-list">
      <div className="date-divider"><span>今天</span></div>
      {messages.map((message) => {
        const mine = message.senderId === currentUser.id;
        const sender = userMap.get(message.senderId) ?? currentUser;
        return (
          <article className={`message-row ${mine ? "mine" : ""}`} key={message.id}>
            {!mine && <Avatar label={sender.avatar} tone="blue" />}
            <div className="message-stack">
              {!mine && <small className="sender-name">{sender.displayName}</small>}
              {message.type === "red_packet" ? (
                <button
                  className="red-packet-card"
                  onClick={() => onOpenRedPacket(contentString(message, "redPacketId"))}
                >
                  <span className="red-packet-icon">¥</span>
                  <span>
                    <strong>{contentString(message, "greeting") || "恭喜发财"}</strong>
                    <small>领取红包</small>
                  </span>
                  <i>›</i>
                  <footer>OpenIM 虚拟红包</footer>
                </button>
              ) : (
                <div className="message-bubble">{contentString(message, "text")}</div>
              )}
              <time>{formatMessageTime(message.sentAt)}{mine ? " · 已发送" : ""}</time>
            </div>
            {mine && <Avatar label={currentUser.avatar} tone="mint" />}
          </article>
        );
      })}
      <div ref={endRef} />
    </div>
  );
}

/** 消息输入框组件 */
function Composer({
  disabled,
  onSend,
  onRedPacket,
}: {
  disabled: boolean;
  onSend: (text: string) => void;
  onRedPacket: () => void;
}) {
  const [text, setText] = useState("");
  const submit = () => {
    const value = text.trim();
    if (!value || disabled) return;
    onSend(value);
    setText("");
  };

  return (
    <footer className="composer">
      <div className="composer-tools">
        <button title="表情"><Icon name="smile" /></button>
        <button title="文件"><Icon name="paperclip" /></button>
        <button title="发送红包" className="red-tool" onClick={onRedPacket}>¥</button>
      </div>
      <textarea
        aria-label="消息内容"
        disabled={disabled}
        maxLength={4000}
        placeholder={disabled ? "实时连接恢复后即可发送" : "输入消息，Enter 发送"}
        value={text}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            submit();
          }
        }}
      />
      <div className="composer-footer">
        <small>Shift + Enter 换行</small>
        <button disabled={!text.trim() || disabled} onClick={submit}>
          发送 <span>⌘↵</span>
        </button>
      </div>
    </footer>
  );
}

/** 红包发送表单弹窗 */
function RedPacketForm({
  conversation,
  onClose,
  onSubmit,
}: {
  conversation: Conversation;
  onClose: () => void;
  onSubmit: (input: CreateRedPacketInput) => Promise<void>;
}) {
  const direct = conversation.type === "direct";
  const [mode, setMode] = useState<CreateRedPacketInput["mode"]>(direct ? "fixed" : "random");
  const [amount, setAmount] = useState("10.00");
  const [count, setCount] = useState(direct ? "1" : "3");
  const [greeting, setGreeting] = useState("恭喜发财，大吉大利");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const totalAmount = Math.round(Number(amount) * 100);
    const totalCount = Number(count);
    if (!Number.isSafeInteger(totalAmount) || totalAmount < 1) {
      setError("请输入有效金额");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      await onSubmit({
        conversationId: conversation.id,
        mode,
        totalAmount,
        totalCount,
        greeting: greeting.trim() || "恭喜发财",
        clientMessageId: crypto.randomUUID(),
        idempotencyKey: crypto.randomUUID(),
      });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "发送失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal onClose={onClose} className="packet-form-modal">
      <div className="modal-heading">
        <span className="modal-symbol packet">¥</span>
        <div><p className="eyebrow">VIRTUAL WALLET</p><h3>发送红包</h3></div>
      </div>
      <form onSubmit={(event) => void submit(event)}>
        {!direct && (
          <label>
            <span>红包类型</span>
            <div className="segmented-control">
              <button type="button" className={mode === "random" ? "active" : ""} onClick={() => setMode("random")}>拼手气</button>
              <button type="button" className={mode === "equal" ? "active" : ""} onClick={() => setMode("equal")}>等额</button>
            </div>
          </label>
        )}
        <label className="input-row">
          <span>总金额</span>
          <div><input value={amount} inputMode="decimal" onChange={(event) => setAmount(event.target.value)} /><b>元</b></div>
        </label>
        <label className="input-row">
          <span>红包个数</span>
          <div><input disabled={direct} value={count} inputMode="numeric" onChange={(event) => setCount(event.target.value)} /><b>个</b></div>
        </label>
        <label className="text-field">
          <span>祝福语</span>
          <input maxLength={60} value={greeting} onChange={(event) => setGreeting(event.target.value)} />
        </label>
        <div className="packet-amount">{currency.format(Number(amount) || 0)}</div>
        {error && <div className="inline-error">{error}</div>}
        <button className="primary-button packet-button" disabled={loading} type="submit">
          {loading ? "正在发送…" : "塞钱进红包"}
        </button>
        <small className="safe-note"><Icon name="lock" /> 演示虚拟余额 · 服务端幂等保护</small>
      </form>
    </Modal>
  );
}

/** 红包详情弹窗 */
function RedPacketDetail({
  packet,
  userMap,
  currentUserId,
  onClaim,
  onClose,
}: {
  packet: RedPacket | null;
  userMap: Map<string, User>;
  currentUserId: string;
  onClaim: () => void;
  onClose: () => void;
}) {
  if (!packet) {
    return <Modal onClose={onClose}><div className="modal-loading">正在打开红包…</div></Modal>;
  }
  const ownClaim = packet.claims.find((claim) => claim.userId === currentUserId);
  const available = ["published", "partially_claimed"].includes(packet.status);
  return (
    <Modal onClose={onClose} className="packet-detail-modal">
      <div className="packet-detail-hero">
        <span className="packet-seal">¥</span>
        <Avatar label={userMap.get(packet.senderId)?.avatar ?? "O"} tone="amber" size="large" />
        <h3>{userMap.get(packet.senderId)?.displayName ?? "好友"} 的红包</h3>
        <p>{packet.greeting}</p>
        {ownClaim && <strong>{currency.format(ownClaim.amount / 100)}</strong>}
        {!ownClaim && available && (
          <button className="open-packet-button" onClick={onClaim}>開</button>
        )}
        {!available && !ownClaim && <span className="packet-status">{packetStatus(packet.status)}</span>}
      </div>
      <div className="claim-summary">
        已领取 {packet.claimedCount}/{packet.totalCount} 个，共 {currency.format(packet.claimedAmount / 100)}/{currency.format(packet.totalAmount / 100)}
      </div>
      <div className="claim-list">
        {packet.claims.map((claim) => (
          <div key={claim.id}>
            <Avatar label={userMap.get(claim.userId)?.avatar ?? "O"} tone="blue" />
            <span><strong>{userMap.get(claim.userId)?.displayName ?? claim.userId}</strong><small>{formatMessageTime(claim.claimedAt)}</small></span>
            <b>{currency.format(claim.amount / 100)}</b>
          </div>
        ))}
        {packet.claims.length === 0 && <p className="empty-claims">还没有人领取，快来抢头彩。</p>}
      </div>
    </Modal>
  );
}

/** 来电提醒浮层 */
function IncomingCall({
  room,
  caller,
  onAccept,
  onReject,
}: {
  room: CallRoom;
  caller: User | undefined;
  onAccept: () => void;
  onReject: () => void;
}) {
  return (
    <div className="incoming-call">
      <div className="incoming-pulse"><Avatar label={caller?.avatar ?? "O"} tone="blue" size="large" /></div>
      <div>
        <small>{room.mediaType === "video" ? "视频通话邀请" : "语音通话邀请"}</small>
        <strong>{caller?.displayName ?? "好友"}</strong>
      </div>
      <button className="call-reject" onClick={onReject}><Icon name="phone" /></button>
      <button className="call-accept" onClick={onAccept}><Icon name={room.mediaType === "video" ? "video" : "phone"} /></button>
    </div>
  );
}

/** 通话界面浮层（含 WebRTC 视频网格） */
function CallOverlay({
  call,
  socket,
  user,
  userMap,
  onLeave,
}: {
  call: CallRoom;
  socket: ImSocket | null;
  user: User;
  userMap: Map<string, User>;
  onLeave: () => void;
}) {
  const rtc = useRtcCall({ socket, call, userId: user.id });
  const joined = call.participants.filter((participant) => participant.state === "joined");
  return (
    <div className="call-overlay">
      <header>
        <div><span className="live-dot" /><strong>{call.mediaType === "video" ? "视频通话" : "语音通话"}</strong><small>{call.status === "ringing" ? "正在等待对方接听" : `${joined.length} 人在线`}</small></div>
        <span className="encrypted-label"><Icon name="lock" /> WebRTC 加密媒体</span>
      </header>
      <div className={`video-grid tiles-${Math.min(rtc.remoteMedia.length + 1, 6)}`}>
        <VideoTile stream={rtc.localStream} label={`${user.displayName}（我）`} muted avatar={user.avatar} video={call.mediaType === "video" && rtc.cameraEnabled} />
        {rtc.remoteMedia.map((remote) => (
          <VideoTile key={remote.userId} stream={remote.stream} label={userMap.get(remote.userId)?.displayName ?? remote.userId} avatar={userMap.get(remote.userId)?.avatar ?? "O"} video={call.mediaType === "video"} />
        ))}
        {call.participants
          .filter((participant) => participant.userId !== user.id && participant.state !== "joined")
          .map((participant) => (
            <div className="video-tile waiting" key={participant.userId}><Avatar label={userMap.get(participant.userId)?.avatar ?? "O"} tone="blue" size="large" /><strong>{userMap.get(participant.userId)?.displayName ?? participant.userId}</strong><small>{participant.state === "invited" ? "等待接听…" : "未加入"}</small></div>
          ))}
      </div>
      {rtc.mediaError && <div className="call-error">{rtc.mediaError}</div>}
      <footer className="call-controls">
        <CallControl active={rtc.micEnabled} icon={rtc.micEnabled ? "mic" : "micOff"} label={rtc.micEnabled ? "麦克风" : "已静音"} onClick={rtc.toggleMic} />
        {call.mediaType === "video" && <CallControl active={rtc.cameraEnabled} icon="video" label={rtc.cameraEnabled ? "摄像头" : "已关闭"} onClick={rtc.toggleCamera} />}
        <button className="hangup-button" onClick={onLeave}><Icon name="phone" /><span>挂断</span></button>
      </footer>
    </div>
  );
}

/** 视频/音频图块 */
function VideoTile({
  stream,
  label,
  avatar,
  muted = false,
  video,
}: {
  stream: MediaStream | null;
  label: string;
  avatar: string;
  muted?: boolean;
  video: boolean;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  useEffect(() => {
    if (videoRef.current) videoRef.current.srcObject = stream;
  }, [stream]);
  return (
    <div className="video-tile">
      {video && stream ? (
        <video ref={videoRef} autoPlay playsInline muted={muted} />
      ) : (
        <div className="audio-avatar"><Avatar label={avatar} tone="blue" size="large" /></div>
      )}
      <span>{label}</span>
    </div>
  );
}

/** 通话控制按钮（静音、摄像头开关） */
function CallControl({ active, icon, label, onClick }: { active: boolean; icon: IconName; label: string; onClick: () => void }) {
  return <button className={`call-control ${active ? "active" : ""}`} onClick={onClick}><Icon name={icon} /><span>{label}</span></button>;
}

/** 通用弹窗容器（点击遮罩层关闭） */
function Modal({ children, onClose, className = "" }: { children: ReactNode; onClose: () => void; className?: string }) {
  return (
    <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && onClose()}>
      <section className={`modal ${className}`} role="dialog" aria-modal="true">
        <button className="modal-close" aria-label="关闭" onClick={onClose}>×</button>
        {children}
      </section>
    </div>
  );
}

/** 用户头像（取姓名首字） */
function Avatar({ label, tone = "mint", size = "normal" }: { label: string; tone?: string; size?: "normal" | "large" }) {
  return <span className={`avatar avatar-${tone} avatar-${size}`}>{label.slice(0, 1)}</span>;
}

/** 侧边栏导航按钮 */
function NavButton({ active, icon, label, onClick }: { active: boolean; icon: IconName; label: string; onClick: () => void }) {
  return <button className={active ? "active" : ""} onClick={onClick}><Icon name={icon} /><span>{label}</span></button>;
}

type IconName = "chat" | "people" | "search" | "phone" | "video" | "more" | "wallet" | "logout" | "smile" | "paperclip" | "lock" | "mic" | "micOff";

/** SVG 图标集合 */
function Icon({ name }: { name: IconName }) {
  const paths: Record<IconName, ReactNode> = {
    chat: <><path d="M21 12a8 8 0 0 1-8 8H6l-4 2 1.4-4.2A9 9 0 1 1 21 12Z" /><path d="M8 12h.01M12 12h.01M16 12h.01" /></>,
    people: <><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></>,
    search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-4-4" /></>,
    phone: <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.8 19.8 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.12 4.18 2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.12.9.33 1.78.62 2.63a2 2 0 0 1-.45 2.11L8 9.73a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.85.29 1.73.5 2.63.62A2 2 0 0 1 22 16.92Z" />,
    video: <><rect x="2" y="6" width="14" height="12" rx="2" /><path d="m16 10 6-3v10l-6-3Z" /></>,
    more: <><circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></>,
    wallet: <><path d="M20 7V5a2 2 0 0 0-2-2H5a3 3 0 0 0 0 6h15v10a2 2 0 0 1-2 2H5a3 3 0 0 1-3-3V6" /><path d="M16 13h2" /></>,
    logout: <><path d="M10 17l5-5-5-5M15 12H3" /><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4" /></>,
    smile: <><circle cx="12" cy="12" r="9" /><path d="M8 14s1.5 2 4 2 4-2 4-2M9 9h.01M15 9h.01" /></>,
    paperclip: <path d="m21.4 11.6-8.9 8.9a6 6 0 0 1-8.5-8.5l9.6-9.6a4 4 0 0 1 5.7 5.7l-9.6 9.6a2 2 0 0 1-2.8-2.8l8.9-8.9" />,
    lock: <><rect x="4" y="10" width="16" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></>,
    mic: <><rect x="9" y="2" width="6" height="12" rx="3" /><path d="M5 10a7 7 0 0 0 14 0M12 17v5M8 22h8" /></>,
    micOff: <><path d="m2 2 20 20M9 9v2a3 3 0 0 0 5.12 2.12M15 9V5a3 3 0 0 0-5.94-.6M17 16.95A7 7 0 0 0 19 12v-2M5 10v2a7 7 0 0 0 11.95 4.95M12 19v3M8 22h8" /></>,
  };
  return <svg viewBox="0 0 24 24" aria-hidden="true">{paths[name]}</svg>;
}

/** 更新通话参与者状态（不可变更新） */
function updateParticipant(room: CallRoom | null, roomId: string, participant: CallParticipant): CallRoom | null {
  if (!room || room.id !== roomId) return room;
  const exists = room.participants.some((item) => item.userId === participant.userId);
  return {
    ...room,
    status: participant.state === "joined" ? "active" : room.status,
    participants: exists
      ? room.participants.map((item) => item.userId === participant.userId ? { ...item, ...participant } : item)
      : [...room.participants, participant],
  };
}

/** 从消息内容中提取指定字段的字符串值 */
function contentString(message: Message, key: string): string {
  const value = message.content[key];
  return typeof value === "string" ? value : "";
}

/** 消息预览文本 */
function messagePreview(message: Message): string {
  if (message.type === "red_packet") return "[红包]";
  if (message.type === "call") return "[通话]";
  return contentString(message, "text") || "[消息]";
}

/** 会话列表时间格式化 */
function formatListTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

/** 消息时间格式化 */
function formatMessageTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

/** 红包状态中文显示 */
function packetStatus(status: RedPacket["status"]): string {
  return ({ published: "待领取", partially_claimed: "还有剩余", exhausted: "已领完", expired: "已过期", refunded: "已退款" })[status];
}
