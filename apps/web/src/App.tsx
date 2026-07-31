import type {
  CallParticipant,
  CallRoom,
  Conversation,
  ConversationMember,
  CreateGroupConversationInput,
  CreateRedPacketInput,
  MemberRole,
  Message,
  MessagePage,
  RedPacket,
  Session,
  User,
  Wallet,
} from "@open-im/contracts";
import {
  Fragment,
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
  ClientError,
  apiRequest,
  getDeviceId,
  getFreshAccessToken,
  getSession,
  logout,
  setSession,
  subscribeSession,
} from "./api";
import { ErrorBoundary } from "./ErrorBoundary";
import { dateDividerFor } from "./messageDate";
import { RealtimeClient } from "./realtime";
import {
  collectMessagePages,
  earliestMessageSeq,
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
  const [session, setSessionState] = useState<Session | null>(() => getSession());

  // 令牌刷新和会话失效都发生在 api 模块内部，订阅它才能让界面跟随变化，
  // 例如刷新令牌被服务端撤销后自动退回登录页
  useEffect(() => subscribeSession(setSessionState), []);

  if (!session) {
    return <LoginScreen onLogin={(nextSession) => setSession(nextSession)} />;
  }

  return (
    <Workspace
      session={session}
      onLogout={() => {
        void logout(session);
      }}
    />
  );
}

/** 当前设备的展示名，用于设备列表和远程下线 */
function deviceName(): string {
  return navigator.userAgent.includes("Mac") ? "Mac 浏览器" : "Web 浏览器";
}

/**
 * 登录页
 *
 * 凭证登录是唯一在生产环境可用的方式。演示身份按钮依赖服务端的
 * /auth/demo-login，它在 prod profile 下返回 404，因此这里在收到该响应后
 * 隐藏演示区域——而不是靠构建期开关，那样会让本地与生产的界面出现分叉。
 */
function LoginScreen({ onLogin }: { onLogin: (session: Session) => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [identifier, setIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [pending, setPending] = useState(false);
  const [loadingId, setLoadingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [demoAvailable, setDemoAvailable] = useState(true);

  /** 演示账号列表。id 是登录用的 username，服务端据此解析真实的 uuid 主键 */
  const demoUsers = [
    { id: "alice", name: "Alice 林夏", avatar: "林", color: "mint" },
    { id: "bob", name: "Bob 陈默", avatar: "陈", color: "blue" },
    { id: "carol", name: "Carol 周晴", avatar: "周", color: "amber" },
  ];

  const describeFailure = (reason: unknown, fallback: string): string => {
    if (reason instanceof ClientError) {
      // 服务端已经给出面向用户的中文消息，直接展示；
      // 不在前端重写文案，否则两处会各自漂移
      return reason.message;
    }
    return reason instanceof Error ? reason.message : fallback;
  };

  /** 凭证登录 */
  const submitCredentials = async () => {
    if (!identifier.trim() || !password) {
      setError("请输入账号和密码");
      return;
    }
    setPending(true);
    setError(null);
    try {
      const session = await apiRequest<Session>("/api/v1/auth/login", null, {
        method: "POST",
        body: JSON.stringify({
          identifier: identifier.trim(),
          password,
          deviceId: getDeviceId(),
          deviceName: deviceName(),
        }),
      });
      onLogin(session);
    } catch (reason) {
      setError(describeFailure(reason, "无法连接服务端，请确认服务已启动"));
    } finally {
      setPending(false);
    }
  };

  /** 注册并直接登录 */
  const submitRegistration = async () => {
    if (!username.trim() || !displayName.trim() || !password) {
      setError("请填写用户名、昵称和密码");
      return;
    }
    setPending(true);
    setError(null);
    try {
      const session = await apiRequest<Session>("/api/v1/auth/register", null, {
        method: "POST",
        body: JSON.stringify({
          username: username.trim().toLowerCase(),
          // 邮箱可选：留空时不提交该字段，避免空字符串触发格式校验
          email: email.trim() || undefined,
          displayName: displayName.trim(),
          password,
          deviceId: getDeviceId(),
          deviceName: deviceName(),
        }),
      });
      onLogin(session);
    } catch (reason) {
      setError(describeFailure(reason, "注册失败，请稍后重试"));
    } finally {
      setPending(false);
    }
  };

  /** 演示登录：不校验凭证，仅在非生产环境可用 */
  const demoLogin = async (demoUsername: string) => {
    setLoadingId(demoUsername);
    setError(null);
    try {
      const session = await apiRequest<Session>("/api/v1/auth/demo-login", null, {
        method: "POST",
        body: JSON.stringify({
          username: demoUsername,
          deviceId: getDeviceId(),
          deviceName: deviceName(),
        }),
      });
      onLogin(session);
    } catch (reason) {
      if (reason instanceof ClientError && reason.code === "NOT_FOUND") {
        // 生产环境禁用了演示登录，隐藏该区域并引导使用凭证登录
        setDemoAvailable(false);
        setError("当前环境未启用演示登录，请使用账号密码登录");
      } else {
        setError(describeFailure(reason, "无法连接服务端，请确认服务已启动"));
      }
    } finally {
      setLoadingId(null);
    }
  };

  const busy = pending || loadingId !== null;

  return (
    <main className="login-page">
      <div className="login-glow login-glow-a" />
      <div className="login-glow login-glow-b" />
      <section className="login-card">
        <div className="brand-mark brand-mark-large">O</div>
        <p className="eyebrow">OPENIM</p>
        <h1>让每一次连接，都自然发生</h1>
        <p className="login-subtitle">
          跨端消息、多人音视频与虚拟红包，都在一个轻盈的工作台里。
        </p>

        <div className="auth-tabs" role="tablist">
          <button
            aria-selected={mode === "login"}
            className={mode === "login" ? "auth-tab is-active" : "auth-tab"}
            disabled={busy}
            onClick={() => {
              setMode("login");
              setError(null);
            }}
            role="tab"
            type="button"
          >
            登录
          </button>
          <button
            aria-selected={mode === "register"}
            className={mode === "register" ? "auth-tab is-active" : "auth-tab"}
            disabled={busy}
            onClick={() => {
              setMode("register");
              setError(null);
            }}
            role="tab"
            type="button"
          >
            注册
          </button>
        </div>

        <form
          className="auth-form"
          onSubmit={(event) => {
            event.preventDefault();
            void (mode === "login" ? submitCredentials() : submitRegistration());
          }}
        >
          {mode === "login" ? (
            <>
              <label className="auth-field">
                <span>用户名或邮箱</span>
                <input
                  autoComplete="username"
                  disabled={busy}
                  onChange={(event) => setIdentifier(event.target.value)}
                  placeholder="alice"
                  type="text"
                  value={identifier}
                />
              </label>
              <label className="auth-field">
                <span>密码</span>
                <input
                  autoComplete="current-password"
                  disabled={busy}
                  onChange={(event) => setPassword(event.target.value)}
                  placeholder="请输入密码"
                  type="password"
                  value={password}
                />
              </label>
            </>
          ) : (
            <>
              <label className="auth-field">
                <span>用户名</span>
                <input
                  autoComplete="username"
                  disabled={busy}
                  onChange={(event) => setUsername(event.target.value)}
                  placeholder="小写字母、数字和下划线"
                  type="text"
                  value={username}
                />
              </label>
              <label className="auth-field">
                <span>昵称</span>
                <input
                  autoComplete="nickname"
                  disabled={busy}
                  onChange={(event) => setDisplayName(event.target.value)}
                  placeholder="展示给他人的名字"
                  type="text"
                  value={displayName}
                />
              </label>
              <label className="auth-field">
                <span>
                  邮箱 <em>选填</em>
                </span>
                <input
                  autoComplete="email"
                  disabled={busy}
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder="用于账号找回"
                  type="email"
                  value={email}
                />
              </label>
              <label className="auth-field">
                <span>密码</span>
                <input
                  autoComplete="new-password"
                  disabled={busy}
                  onChange={(event) => setPassword(event.target.value)}
                  placeholder="至少 10 个字符"
                  type="password"
                  value={password}
                />
              </label>
            </>
          )}
          <button className="auth-submit" disabled={busy} type="submit">
            {pending ? "处理中…" : mode === "login" ? "登录" : "注册并进入"}
          </button>
        </form>

        {error && (
          <div className="inline-error" role="alert">
            {error}
          </div>
        )}

        {demoAvailable && (
          <>
            <div className="demo-label">
              <span /> 或选择演示身份 <span />
            </div>
            <div className="demo-users">
              {demoUsers.map((user) => (
                <button
                  className="demo-user"
                  disabled={busy}
                  key={user.id}
                  onClick={() => void demoLogin(user.id)}
                  type="button"
                >
                  <Avatar label={user.avatar} tone={user.color} size="large" />
                  <span>
                    <strong>{user.name}</strong>
                    <small>
                      {loadingId === user.id ? "正在登录…" : "进入工作台"}
                    </small>
                  </span>
                  <i>→</i>
                </button>
              ))}
            </div>
          </>
        )}
        <p className="demo-notice">演示环境使用虚拟余额，不连接真实支付。</p>
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
  /**
   * 按 ID 补齐的用户资料。
   *
   * 联系人列表收窄之后，出现在历史消息、红包领取记录或通话里的人不一定在
   * 联系人里（比如已退群的成员）。没有这张表，他们的昵称会退化成一串 UUID。
   */
  const [profiles, setProfiles] = useState<Record<string, User>>({});
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
  const [showGroupForm, setShowGroupForm] = useState(false);
  const [showMemberPanel, setShowMemberPanel] = useState(false);
  const [members, setMembers] = useState<ConversationMember[]>([]);
  /** 每个会话服务端侧是否还有更早的消息。缺省视为可能有，首次加载后即被填上 */
  const [hasMoreHistory, setHasMoreHistory] = useState<Record<string, boolean>>({});
  const [loadingHistory, setLoadingHistory] = useState<Record<string, boolean>>({});
  const conversationsRef = useRef(conversations);
  const messagesRef = useRef(messages);
  const currentCallRef = useRef(currentCall);
  const hydratedConversationsRef = useRef(new Set<string>());
  // 通过 ref 读取而不是进 useEffect 的依赖数组：把它们列为依赖会让 WebSocket
  // 在每次打开成员面板或切换会话时断开重连。与 currentCallRef 同理。
  const showMemberPanelRef = useRef(showMemberPanel);
  const selectedIdRef = useRef(selectedId);
  /** 每个会话最近一次上报过的已读 seq，用于避免重复上报同一个位置 */
  const lastReadReportedRef = useRef<Record<string, number>>({});
  /**
   * 正在加载历史的会话。
   *
   * 用 ref 而不是读 loadingHistory state：state 的更新是异步的，连点两次
   * "查看更早"时第二次读到的仍是 false，同一页会被请求两次。
   */
  const loadingHistoryRef = useRef(new Set<string>());
  /** profiles 的同步快照，供 ensureProfiles 判断"已经有了"而不必进依赖数组 */
  const profilesRef = useRef(profiles);
  /** 正在补齐中的用户 ID，避免同一批人被并发请求两次 */
  const inFlightProfilesRef = useRef(new Set<string>());

  profilesRef.current = profiles;

  conversationsRef.current = conversations;
  messagesRef.current = messages;
  currentCallRef.current = currentCall;
  showMemberPanelRef.current = showMemberPanel;
  selectedIdRef.current = selectedId;

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

  /**
   * 断线补拉：把 afterSeq 之后漏掉的消息全部补齐。
   *
   * 这里必须翻页到底——漏掉的消息不补全会在本地 seq 上留下空洞，而客户端正是
   * 靠 seq 连续性判断有没有漏收。<b>只用于重连</b>；打开会话走
   * {@link loadLatestMessages}，它只取最新一页。
   */
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

  // ============================================================
  // 历史消息分页
  //
  // 此前打开会话是从 afterSeq=0 一页页翻到底，也就是把整个会话的全部历史拉进
  // 内存——五万条消息的会话点开就是 500 次串行请求。而且服务端只有向后拉的接口，
  // 没有任何办法向上翻阅更早的消息。
  // ============================================================

  /** 打开会话：只取最新一页，更早的内容按需加载 */
  const loadLatestMessages = useCallback(async (conversationId: string) => {
    const page = await apiRequest<MessagePage>(
      `/api/v1/conversations/${conversationId}/messages/latest`,
      session,
    );
    setMessages((current) => ({
      ...current,
      [conversationId]: mergeMessages(current[conversationId] ?? [], page.messages),
    }));
    setHasMoreHistory((current) => ({ ...current, [conversationId]: page.hasMore }));
  }, [session]);

  /**
   * 向上翻阅：取比已加载的最早一条更早的一页。
   *
   * {@code hasMore} 取自服务端而不是按"返回条数不足一页"推断——那个推断在最后
   * 一页恰好装满时会多发一次请求，用户看到加载指示器闪一下却没有新内容。
   */
  const loadEarlierMessages = useCallback(async (conversationId: string) => {
    const loaded = messagesRef.current[conversationId] ?? [];
    const cursor = earliestMessageSeq(loaded);
    if (cursor <= 0) {
      // 还没加载过任何消息，"更早"没有意义
      return;
    }
    if (loadingHistoryRef.current.has(conversationId)) {
      // 用户连点或滚动事件重复触发时，避免同一页被请求多次
      return;
    }
    loadingHistoryRef.current.add(conversationId);
    setLoadingHistory((current) => ({ ...current, [conversationId]: true }));
    try {
      const page = await apiRequest<MessagePage>(
        `/api/v1/conversations/${conversationId}/messages/history?beforeSeq=${cursor}`,
        session,
      );
      setMessages((current) => ({
        ...current,
        [conversationId]: mergeMessages(current[conversationId] ?? [], page.messages),
      }));
      setHasMoreHistory((current) => ({ ...current, [conversationId]: page.hasMore }));
    } catch (error) {
      showToast(error instanceof Error ? error.message : "加载历史失败");
    } finally {
      loadingHistoryRef.current.delete(conversationId);
      setLoadingHistory((current) => ({ ...current, [conversationId]: false }));
    }
  }, [session, showToast]);

  // ============================================================
  // 用户资料补齐
  //
  // GET /users 此前返回全站所有用户，客户端因此天然拥有每个人的资料。
  // 收窄成"我的联系人"之后，出现在历史消息里但已退群的人在本地查不到，
  // 昵称会退化成一串 UUID——这一段负责按需补齐他们。
  // ============================================================

  /**
   * 确保这批 ID 的资料在本地可用。
   *
   * 已知的不再请求：这个函数被消息列表的渲染路径间接触发，重复请求同一批 ID
   * 会随消息数量放大。用 ref 记录"正在请求中"的 ID，避免同一批人被并发请求两次
   * （state 更新是异步的，读 profiles 判断会漏）。
   */
  /**
   * 重新拉取联系人列表。
   *
   * 联系人是"与我有会话往来的人"，因此每一个新建立的会话都可能带来新联系人。
   * 失败不提示：列表陈旧只是少显示一个人，而下次进入联系人页会再拉一次。
   */
  const refreshContacts = useCallback(async () => {
    try {
      setUsers(await apiRequest<User[]>("/api/v1/users", session));
    } catch {
      // 见上：静默失败
    }
  }, [session]);

  const ensureProfiles = useCallback(async (ids: string[]) => {
    const missing = ids.filter((id) =>
      id
      && id !== session.user.id
      && !profilesRef.current[id]
      && !inFlightProfilesRef.current.has(id));
    if (missing.length === 0) return;

    for (const id of missing) inFlightProfilesRef.current.add(id);
    try {
      const fetched = await apiRequest<User[]>(
        `/api/v1/users/lookup?ids=${missing.map(encodeURIComponent).join(",")}`,
        session,
      );
      setProfiles((current) => {
        const next = { ...current };
        for (const user of fetched) next[user.id] = user;
        return next;
      });
    } catch {
      // 补齐失败不提示：昵称退化成 ID 是可接受的降级，弹一个"加载用户资料失败"
      // 对用户没有任何可操作性。下次渲染会重试。
    } finally {
      for (const id of missing) inFlightProfilesRef.current.delete(id);
    }
  }, [session]);

  /**
   * 按完整用户名查找一个人。
   *
   * 联系人列表收窄后必须有这条路径，否则两个从未有过往来的人无法建立第一次对话。
   * 服务端要求完整用户名且对这个接口单独限流——那个配额是可见性边界的一部分。
   */
  const searchUserByUsername = useCallback(async (username: string) => {
    const trimmed = username.trim();
    if (!trimmed) return [];
    return apiRequest<User[]>(
      `/api/v1/users/search?username=${encodeURIComponent(trimmed)}`,
      session,
    );
  }, [session]);

  // ============================================================
  // 会话与成员管理
  //
  // 服务端此前没有任何创建会话的接口——会话全部来自演示种子。因此这一段
  // 在客户端同样是新的：在它之前，"新建会话"这个动作在界面上不存在。
  // ============================================================

  /**
   * 拉取单个会话并合并进列表。
   *
   * 成员变更事件只带 conversationId，客户端据此拉<b>这一个</b>会话而不是重新拉
   * 全量列表——一个 500 人群加一个人，否则就是 500 个客户端各拉一次全量。
   */
  const refreshConversation = useCallback(async (conversationId: string) => {
    const conversation = await apiRequest<Conversation>(
      `/api/v1/conversations/${conversationId}`,
      session,
    );
    setConversations((current) => {
      const others = current.filter((item) => item.id !== conversation.id);
      return [...others, conversation].sort((left, right) =>
        right.lastActiveAt.localeCompare(left.lastActiveAt),
      );
    });
    return conversation;
  }, [session]);

  /** 从本地移除一个会话（被移出或退群） */
  const dropConversation = useCallback((conversationId: string) => {
    setConversations((current) => current.filter((item) => item.id !== conversationId));
    setMessages((current) => {
      const next = { ...current };
      delete next[conversationId];
      return next;
    });
    hydratedConversationsRef.current.delete(conversationId);
    setSelectedId((current) => (current === conversationId ? null : current));
  }, []);

  /**
   * 打开与某人的单聊。
   *
   * 服务端是幂等的（同一对用户只有一个单聊），所以这里不需要先查本地列表——
   * 直接调用，已存在时返回的就是那个会话。在客户端先查一遍反而会漏：
   * 本地列表可能还没同步到对方刚建的那个会话。
   */
  const openDirectConversation = useCallback(async (peerId: string) => {
    try {
      const conversation = await apiRequest<Conversation>(
        "/api/v1/conversations/direct",
        session,
        { method: "POST", body: JSON.stringify({ peerId }) },
      );
      await refreshConversation(conversation.id);
      setSelectedId(conversation.id);
      setActiveNav("chat");
      // 刚建立往来的人现在是联系人了。不刷新的话他只存在于搜索结果里——
      // 用户关掉搜索就再也找不到刚聊过的人，而联系人列表明明应该有他。
      void refreshContacts();
    } catch (error) {
      showToast(error instanceof Error ? error.message : "无法打开会话");
    }
  }, [session, refreshConversation, refreshContacts, showToast]);

  const createGroupConversation = useCallback(async (
    input: CreateGroupConversationInput,
  ) => {
    const conversation = await apiRequest<Conversation>(
      "/api/v1/conversations/group",
      session,
      { method: "POST", body: JSON.stringify(input) },
    );
    await refreshConversation(conversation.id);
    setSelectedId(conversation.id);
    setActiveNav("chat");
    setShowGroupForm(false);
  }, [session, refreshConversation]);

  const loadMembers = useCallback(async (conversationId: string) => {
    const roster = await apiRequest<ConversationMember[]>(
      `/api/v1/conversations/${conversationId}/members`,
      session,
    );
    setMembers(roster);
    return roster;
  }, [session]);

  /**
   * 成员管理动作的统一入口。
   *
   * 每个动作完成后都重新拉一次成员列表，而不是在本地推算结果：角色变更和
   * 转让群主会同时改动<b>多个</b>成员的角色（转让会降级原群主），本地推算很容易
   * 只更新其中一个，界面上就出现两个群主。
   */
  const runMemberAction = useCallback(async (
    conversationId: string,
    action: () => Promise<unknown>,
  ) => {
    try {
      await action();
      await loadMembers(conversationId);
      await refreshConversation(conversationId);
    } catch (error) {
      showToast(error instanceof Error ? error.message : "操作失败");
    }
  }, [loadMembers, refreshConversation, showToast]);

  const addMembers = useCallback((conversationId: string, userIds: string[]) =>
    runMemberAction(conversationId, () =>
      apiRequest(`/api/v1/conversations/${conversationId}/members`, session, {
        method: "POST",
        body: JSON.stringify({ userIds }),
      })), [runMemberAction, session]);

  const removeMember = useCallback((conversationId: string, userId: string) =>
    runMemberAction(conversationId, () =>
      apiRequest(
        `/api/v1/conversations/${conversationId}/members/${userId}`,
        session,
        { method: "DELETE" },
      )), [runMemberAction, session]);

  const changeMemberRole = useCallback((
    conversationId: string,
    userId: string,
    role: Exclude<MemberRole, "OWNER">,
  ) =>
    runMemberAction(conversationId, () =>
      apiRequest(
        `/api/v1/conversations/${conversationId}/members/${userId}`,
        session,
        { method: "PATCH", body: JSON.stringify({ role }) },
      )), [runMemberAction, session]);

  const transferOwnership = useCallback((conversationId: string, userId: string) =>
    runMemberAction(conversationId, () =>
      apiRequest(`/api/v1/conversations/${conversationId}/transfer-owner`, session, {
        method: "POST",
        body: JSON.stringify({ userId }),
      })), [runMemberAction, session]);

  /** 退群。成功后本地立即移除——服务端也会推 conversation:removed 给其他设备 */
  const leaveConversation = useCallback(async (conversationId: string) => {
    try {
      await apiRequest(`/api/v1/conversations/${conversationId}/membership`, session, {
        method: "DELETE",
      });
      setShowMemberPanel(false);
      dropConversation(conversationId);
    } catch (error) {
      showToast(error instanceof Error ? error.message : "退出失败");
    }
  }, [session, dropConversation, showToast]);

  /**
   * 上报已读位置。
   *
   * 未读数由服务端按 {@code next_seq - 1 - read_seq} 推导，所以这是唯一能让
   * 未读角标下降的途径。服务端的 advanceReadSeq 早就实现了，但在补上这个调用
   * 之前没有任何调用方——角标只增不减。
   *
   * 失败不提示：已读上报是尽力而为的后台动作，弹一个"已读同步失败"对用户没有
   * 任何可操作性，而下一条消息到达时会重新上报。
   */
  const markRead = useCallback(async (conversationId: string, seq: number) => {
    if (seq <= 0) return;
    try {
      const updated = await apiRequest<Conversation>(
        `/api/v1/conversations/${conversationId}/read`,
        session,
        { method: "POST", body: JSON.stringify({ seq }) },
      );
      setConversations((current) => current.map((item) =>
        item.id === updated.id ? { ...item, unreadCount: updated.unreadCount } : item));
    } catch {
      // 见上：静默失败
    }
  }, [session]);

  const openMemberPanel = useCallback(async (conversationId: string) => {
    setShowMemberPanel(true);
    try {
      await loadMembers(conversationId);
    } catch (error) {
      showToast(error instanceof Error ? error.message : "无法加载成员");
    }
  }, [loadMembers, showToast]);

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
      getFreshAccessToken,
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

    // ── 会话生命周期 ────────────────────────────────────────
    // 三个事件都只带 conversationId，客户端据此拉这一个会话。
    // 载荷里放完整会话对象是不行的：单聊的标题是<b>对端</b>的昵称、未读数是各人
    // 各自的游标，一个能同时发给所有收件人的会话对象并不存在。
    realtime.on("conversation:created", ({ conversationId }) => {
      void refreshConversation(conversationId).catch(() => {
        // 拉取失败不提示：可能是这台设备正好在重连。列表会在下次初始化时补齐
      });
      // 别人开了与我的单聊或把我拉进群，那些人现在是我的联系人。
      // 收窄联系人列表之后这一步是必要的——他们此前对我完全不可见。
      void refreshContacts();
    });
    realtime.on("conversation:updated", ({ conversationId }) => {
      void refreshConversation(conversationId).catch(() => {});
    });
    realtime.on("conversation:members-changed", ({ conversationId }) => {
      void refreshConversation(conversationId).catch(() => {});
      // 成员进出会改变我的联系人集合
      void refreshContacts();
      // 成员面板开着时同步刷新，否则会显示已经过期的角色
      if (showMemberPanelRef.current && selectedIdRef.current === conversationId) {
        void loadMembers(conversationId).catch(() => {});
      }
    });
    // 被移出会话或在另一台设备上退群。少了这条，客户端会继续显示一个
    // 已经无权访问的会话，直到下次操作全部返回 403 才发现。
    realtime.on("conversation:removed", ({ conversationId }) => {
      dropConversation(conversationId);
      showToast("你已不在该会话中");
    });
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
      // 只对终止性错误弹提示：普通连接失败会自动重连，由连接状态指示器体现即可，
      // 每次重试都弹一次会在弱网下刷屏
      if (error.code === "AUTH_REQUIRED") showToast(error.message);
    });
    realtime.connect();

    return () => {
      realtime.disconnect();
      setSocket(null);
    };
  }, [
    addMessage,
    dropConversation,
    loadMembers,
    refreshContacts,
    refreshConversation,
    session,
    showToast,
    syncKnownConversations,
  ]);

  /**
   * 选中会话时加载最新一页消息。
   *
   * 此前这里调的是 {@code syncConversation(selectedId, 0)}——从最早的消息开始
   * 一页页翻到底。那对演示数据（每个会话两条消息）无所谓，对真实会话意味着
   * 打开它的代价与它的历史长度成正比。
   */
  useEffect(() => {
    if (!selectedId || hydratedConversationsRef.current.has(selectedId)) return;
    let active = true;
    void loadLatestMessages(selectedId)
      .then(() => {
        if (active) hydratedConversationsRef.current.add(selectedId);
      })
      .catch((error: unknown) => {
        showToast(error instanceof Error ? error.message : "消息加载失败");
      });
    return () => {
      active = false;
    };
  }, [selectedId, showToast, loadLatestMessages]);

  /**
   * 补齐当前会话里出现过的发送者资料。
   *
   * 触发点放在"消息变化"而不是"打开会话"：向上翻页会带出更早的发送者，
   * 其中可能有已经退群的人。
   */
  useEffect(() => {
    if (!selectedId) return;
    const senderIds = new Set<string>();
    for (const message of messages[selectedId] ?? []) {
      senderIds.add(message.senderId);
    }
    if (senderIds.size > 0) {
      void ensureProfiles([...senderIds]);
    }
  }, [selectedId, messages, ensureProfiles]);

  /**
   * 把已读游标推进到当前会话的最新消息。
   *
   * 依赖 messages 而不只是 selectedId：用户正看着这个会话时收到的新消息也应当
   * 立即算作已读，否则角标会在他眼前涨起来。
   *
   * 用 ref 记住上次上报的 seq，使同一个位置只上报一次——不做这个去重的话，
   * 每次 messages 引用变化（包括别的会话收到消息）都会重发一次相同的请求。
   */
  useEffect(() => {
    if (!selectedId) return;
    const latest = latestMessageSeq(messages[selectedId] ?? []);
    if (latest <= 0) return;
    if ((lastReadReportedRef.current[selectedId] ?? 0) >= latest) return;
    lastReadReportedRef.current[selectedId] = latest;
    void markRead(selectedId, latest);
  }, [selectedId, messages, markRead]);

  const selectedConversation = conversations.find(
    (conversation) => conversation.id === selectedId,
  );
  const selectedMessages = selectedId ? messages[selectedId] ?? [] : [];
  /**
   * 用户资料查找表。
   *
   * 合并两个来源：{@code users}（我的联系人）与 {@code profiles}（按 ID 补齐的）。
   * 联系人列表收窄到"有会话往来的人"之后，历史消息里已退群成员的昵称在
   * {@code users} 里找不到——那些人的资料由 {@link ensureProfiles} 按需补齐。
   *
   * 联系人优先：它带着刚拉取的在线状态，而补齐来的资料可能是几分钟前取的。
   */
  const userMap = useMemo(() => {
    const merged = new Map(Object.entries(profiles));
    for (const user of users) merged.set(user.id, user);
    return merged;
  }, [users, profiles]);
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
          <div className="panel-header-actions">
            <span className={`connection-pill ${connected ? "online" : ""}`}>
              <i /> {connected ? "在线" : "重连中"}
            </span>
            {/*
              新建群聊。此前界面上没有任何"新建会话"的入口——因为服务端也没有
              对应的接口，会话全部来自演示种子。单聊的入口在联系人列表里。
            */}
            <button
              className="new-conversation-button"
              data-testid="new-group-button"
              title="发起群聊"
              onClick={() => setShowGroupForm(true)}
            >
              <Icon name="plus" />
            </button>
          </div>
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
                data-testid="conversation-item"
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
          <ContactPanel
            contacts={users.filter((user) => user.id !== session.user.id)}
            onOpenConversation={(userId) => void openDirectConversation(userId)}
            onSearch={searchUserByUsername}
          />
        )}

        <div className="wallet-card" data-testid="wallet-card">
          <span>
            <small>演示钱包余额</small>
            <strong data-testid="wallet-balance">{currency.format((wallet?.balance ?? 0) / 100)}</strong>
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
                <button
                  title={selectedConversation.type === "group" ? "群成员" : "会话信息"}
                  data-testid="conversation-info-button"
                  onClick={() => void openMemberPanel(selectedConversation.id)}
                >
                  <Icon name="more" />
                </button>
              </div>
            </header>
            {/*
              消息列表单独包一层错误边界。它是最可能出问题的部分——
              渲染的是服务端返回的、结构可能不符合预期的载荷（缺字段、
              类型不对、未知的消息 type）。一条异常消息不该让整个工作台白屏，
              用户至少要还能切换会话、看到余额、退出登录。
            */}
            <ErrorBoundary
              fallback={(error, reset) => (
                <div className="message-list message-list-error" role="alert">
                  <p>这个会话的消息未能正常显示。</p>
                  <code>{error.message}</code>
                  <button className="ghost-button" onClick={reset} type="button">
                    重试
                  </button>
                </div>
              )}
              // key 让切换会话时边界自动重置：不加的话，一个会话出错后
              // 切到别的会话仍然显示错误界面
              key={selectedId ?? "none"}
              name="message-list"
            >
              <MessageList
                messages={selectedMessages}
                currentUser={session.user}
                userMap={userMap}
                hasMoreOnServer={hasMoreHistory[selectedConversation.id] ?? false}
                loadingHistory={loadingHistory[selectedConversation.id] ?? false}
                onLoadEarlier={() => void loadEarlierMessages(selectedConversation.id)}
                onOpenRedPacket={(id) => void openRedPacket(id)}
              />
            </ErrorBoundary>
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

      {showGroupForm && (
        <GroupCreateForm
          users={users.filter((user) => user.id !== session.user.id)}
          onClose={() => setShowGroupForm(false)}
          onSubmit={createGroupConversation}
        />
      )}
      {showMemberPanel && selectedConversation && (
        <MemberPanel
          conversation={selectedConversation}
          members={members}
          users={users}
          currentUserId={session.user.id}
          onAddMembers={(userIds) => addMembers(selectedConversation.id, userIds)}
          onRemoveMember={(userId) => removeMember(selectedConversation.id, userId)}
          onChangeRole={(userId, role) =>
            changeMemberRole(selectedConversation.id, userId, role)}
          onTransferOwnership={(userId) =>
            transferOwnership(selectedConversation.id, userId)}
          onLeave={() => leaveConversation(selectedConversation.id)}
          onClose={() => setShowMemberPanel(false)}
        />
      )}
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
/**
 * 一屏渲染的消息条数上限。
 *
 * 此前列表把<b>全部</b>消息一次性渲染出来，且没有虚拟滚动。一个活跃会话
 * 累积几千条之后，每次新消息到达都会触发对整个列表的 diff——
 * 输入时的卡顿会随会话长度线性恶化，而这种劣化在演示数据下永远看不到。
 *
 * 这里用"窗口 + 按需展开"而不是引入虚拟滚动库，理由是它更契合 IM 的使用方式：
 * 用户几乎总是在看最新的消息，向上翻是低频操作。虚拟滚动要处理不定高度的
 * 气泡测量、滚动锚定、跳转定位，复杂度远高于收益。
 */
const MESSAGE_WINDOW = 80;

/** 每次点击"查看更早"多展开的条数 */
const MESSAGE_WINDOW_STEP = 120;

function MessageList({
  messages,
  currentUser,
  userMap,
  hasMoreOnServer,
  loadingHistory,
  onLoadEarlier,
  onOpenRedPacket,
}: {
  messages: Message[];
  currentUser: User;
  userMap: Map<string, User>;
  hasMoreOnServer: boolean;
  loadingHistory: boolean;
  onLoadEarlier: () => void;
  onOpenRedPacket: (id: string) => void;
}) {
  const endRef = useRef<HTMLDivElement>(null);
  const [visibleCount, setVisibleCount] = useState(MESSAGE_WINDOW);

  // 切换会话时重置窗口。不重置的话，从一个已展开到 500 条的会话切到
  // 另一个会话时会直接渲染 500 条
  const conversationKey = messages[0]?.conversationId;
  useEffect(() => {
    setVisibleCount(MESSAGE_WINDOW);
  }, [conversationKey]);

  /**
   * 新消息到达时滚到底部。
   *
   * 依赖最新一条的 id 而不是 messages.length：向上翻页也会让长度增加，
   * 而那时把视口拽到底部正好抹掉用户刚加载出来的内容。
   */
  const latestId = messages.at(-1)?.id;
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [latestId]);

  // 取最后 visibleCount 条：用户关心的是最新消息
  const hiddenCount = Math.max(0, messages.length - visibleCount);
  const visible = hiddenCount > 0 ? messages.slice(hiddenCount) : messages;

  /**
   * "查看更早"有两层：先展开本地已加载但未渲染的部分（纯渲染，无请求），
   * 本地展开完了再向服务端要下一页。
   *
   * 合成一个按钮而不是两个：对用户来说"看更早的消息"是一件事，
   * "这些消息在内存里还是在服务器上"是实现细节。
   */
  const canLoadEarlier = hiddenCount > 0 || hasMoreOnServer;
  const loadEarlier = () => {
    if (hiddenCount > 0) {
      setVisibleCount((count) => count + MESSAGE_WINDOW_STEP);
      return;
    }
    onLoadEarlier();
  };

  return (
    <div className="message-list">
      {canLoadEarlier && (
        <div className="message-load-earlier">
          <button
            onClick={loadEarlier}
            disabled={loadingHistory}
            data-testid="load-earlier-button"
            type="button"
          >
            {loadingHistory
              ? "正在加载…"
              : hiddenCount > 0
                ? `查看更早的消息（已加载 ${hiddenCount} 条未显示）`
                : "查看更早的消息"}
          </button>
        </div>
      )}
      {visible.map((message, index) => {
        const mine = message.senderId === currentUser.id;
        const sender = userMap.get(message.senderId) ?? currentUser;
        // 日期分隔符按实际日期插入。此前这里硬编码了一个"今天"，
        // 无论消息是哪天发的都显示"今天"——历史消息全部被标成今天。
        const divider = dateDividerFor(message, visible[index - 1]);
        return (
          <Fragment key={message.id}>
            {divider && (
              <div className="date-divider">
                <span>{divider}</span>
              </div>
            )}
            <article className={`message-row ${mine ? "mine" : ""}`} data-testid="message-row">
              {!mine && <Avatar label={sender.avatar} tone="blue" />}
              <div className="message-stack">
                {!mine && <small className="sender-name">{sender.displayName}</small>}
                {message.type === "red_packet" ? (
                  <button
                    className="red-packet-card"
                    data-testid="red-packet-card"
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
                  <div className="message-bubble" data-testid="message-bubble">{contentString(message, "text")}</div>
                )}
                <time>{formatMessageTime(message.sentAt)}{mine ? " · 已发送" : ""}</time>
              </div>
              {mine && <Avatar label={currentUser.avatar} tone="mint" />}
            </article>
          </Fragment>
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
      {/*
        每个按钮都需要 aria-label：title 属性在计算可访问名称时优先级低于按钮内容，
        因此"发送红包"那个按钮对屏幕阅读器只会读出"¥"，而图标按钮什么也读不出来
        （Icon 渲染的是 svg）。加上 aria-label 后屏幕阅读器可用，
        测试也能按语义而不是 class 名定位。
      */}
      <div className="composer-tools">
        <button aria-label="表情" title="表情" type="button"><Icon name="smile" /></button>
        <button aria-label="文件" title="文件" type="button"><Icon name="paperclip" /></button>
        <button
          aria-label="发送红包"
          className="red-tool"
          onClick={onRedPacket}
          title="发送红包"
          type="button"
        >
          ¥
        </button>
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
/**
 * 联系人面板
 *
 * <b>为什么需要搜索框</b>：`GET /users` 此前返回全站所有注册者，于是"找到某个人"
 * 等价于"在列表里滚动"。收窄成"与我有会话往来的人"之后，从未打过交道的人
 * 不会出现在列表里——没有搜索入口的话，两个陌生用户永远无法建立第一次对话。
 *
 * 搜索要求<b>完整</b>用户名（服务端不做模糊匹配，那会让用户名可被枚举），
 * 因此提示语必须说清这一点，否则用户会以为搜索坏了。
 */
function ContactPanel({
  contacts,
  onOpenConversation,
  onSearch,
}: {
  contacts: User[];
  onOpenConversation: (userId: string) => void;
  onSearch: (username: string) => Promise<User[]>;
}) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<User[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = query.trim();
    if (!trimmed) {
      setResults(null);
      return;
    }
    setSearching(true);
    setError(null);
    try {
      setResults(await onSearch(trimmed));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "查找失败");
      setResults(null);
    } finally {
      setSearching(false);
    }
  };

  const reset = () => {
    setQuery("");
    setResults(null);
    setError(null);
  };

  // 搜索结果里已经是联系人的人不重复展示为"新增"
  const contactIds = new Set(contacts.map((contact) => contact.id));
  const shown = results ?? contacts;

  return (
    <div className="contact-list">
      <form className="contact-search" onSubmit={(event) => void submit(event)}>
        <label className="search-box">
          <Icon name="search" />
          <input
            value={query}
            data-testid="user-search-input"
            placeholder="输入完整用户名查找"
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <button type="submit" data-testid="user-search-button" disabled={searching}>
          {searching ? "查找中…" : "查找"}
        </button>
      </form>

      {error && <div className="inline-error">{error}</div>}

      {results !== null && (
        <div className="contact-search-meta">
          <small>
            {results.length === 0
              ? "没有找到该用户名对应的账号"
              : `找到 ${results.length} 个账号`}
          </small>
          <button type="button" onClick={reset} data-testid="clear-search-button">
            返回联系人
          </button>
        </div>
      )}

      {results === null && contacts.length === 0 && (
        <p className="contact-empty">
          还没有联系人。用上面的搜索框按完整用户名找到对方，即可开始对话。
        </p>
      )}

      {/*
        联系人从 div 改成 button：点一下就打开与这个人的单聊。
        在补上会话创建接口之前它只能是静态展示——没有任何办法与列表里的
        人建立对话，而这正是"prod 下发不出第一条消息"的表现。
      */}
      {shown.map((user) => (
        <button
          className="contact-item"
          data-testid="contact-item"
          key={user.id}
          onClick={() => onOpenConversation(user.id)}
        >
          <div className="avatar-with-status">
            <Avatar label={user.avatar} tone="blue" />
            <i className={user.status} />
          </div>
          <span>
            <strong>{user.displayName}</strong>
            <small>
              {results !== null && !contactIds.has(user.id)
                ? `@${user.username}`
                : user.status === "online" ? "在线" : "最近在线"}
            </small>
          </span>
          <Icon name="plus" />
        </button>
      ))}
    </div>
  );
}

/**
 * 新建群聊
 *
 * 群名与成员都由服务端再校验一次——这里的校验只是为了立即给出反馈，
 * 不是安全边界。成员上限、用户是否存在、创建者是否被重复列入都在服务端判定。
 */
function GroupCreateForm({
  users,
  onClose,
  onSubmit,
}: {
  users: User[];
  onClose: () => void;
  onSubmit: (input: CreateGroupConversationInput) => Promise<void>;
}) {
  const [title, setTitle] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const toggle = (userId: string) => {
    setSelected((current) => current.includes(userId)
      ? current.filter((id) => id !== userId)
      : [...current, userId]);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = title.trim();
    if (!trimmed) {
      setError("请输入群名称");
      return;
    }
    if (selected.length === 0) {
      setError("请至少选择一位成员");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      await onSubmit({ title: trimmed, memberIds: selected });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "创建失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal onClose={onClose} className="group-form-modal">
      <div className="modal-heading">
        <span className="modal-symbol group">群</span>
        <div><p className="eyebrow">NEW CONVERSATION</p><h3>发起群聊</h3></div>
      </div>
      <form onSubmit={(event) => void submit(event)}>
        <label className="text-field">
          <span>群名称</span>
          <input
            maxLength={64}
            value={title}
            data-testid="group-title-input"
            placeholder="给这个群起个名字"
            onChange={(event) => setTitle(event.target.value)}
          />
        </label>
        <div className="member-picker">
          <span className="picker-label">选择成员（{selected.length}）</span>
          {/*
            候选人只有<b>联系人</b>——即与我有过会话往来的人。这是有意的：
            把一个从未打过交道的陌生人直接拉进群，对他来说等同于被陌生人加入
            一个未知的群聊。要先与某人建立单聊，他才会出现在这里。
          */}
          {users.length === 0 && (
            <p className="picker-empty">
              还没有联系人。先在联系人页按用户名找到对方并开始对话，
              之后就能把他加进群聊。
            </p>
          )}
          <div className="picker-list">
            {users.map((user) => (
              <button
                type="button"
                key={user.id}
                data-testid="group-member-option"
                className={`picker-item ${selected.includes(user.id) ? "selected" : ""}`}
                onClick={() => toggle(user.id)}
              >
                <Avatar label={user.avatar} tone="blue" />
                <strong>{user.displayName}</strong>
                {selected.includes(user.id) && <Icon name="check" />}
              </button>
            ))}
          </div>
        </div>
        {error && <div className="inline-error">{error}</div>}
        <button
          className="primary-button"
          data-testid="group-submit-button"
          disabled={loading}
          type="submit"
        >
          {loading ? "正在创建…" : "创建群聊"}
        </button>
      </form>
    </Modal>
  );
}

/**
 * 会话成员与角色管理
 *
 * <b>界面按自己的角色显示可用操作，但这不是权限边界</b>——真正的判定全部在服务端
 * （见 MemberRole 的权限矩阵）。隐藏按钮只是避免让人点一个必然失败的操作，
 * 直接调接口同样会被拒绝。
 *
 * 单聊没有角色也没有管理操作，只展示两位成员。
 */
function MemberPanel({
  conversation,
  members,
  users,
  currentUserId,
  onAddMembers,
  onRemoveMember,
  onChangeRole,
  onTransferOwnership,
  onLeave,
  onClose,
}: {
  conversation: Conversation;
  members: ConversationMember[];
  users: User[];
  currentUserId: string;
  onAddMembers: (userIds: string[]) => Promise<void>;
  onRemoveMember: (userId: string) => Promise<void>;
  onChangeRole: (userId: string, role: Exclude<MemberRole, "OWNER">) => Promise<void>;
  onTransferOwnership: (userId: string) => Promise<void>;
  onLeave: () => Promise<void>;
  onClose: () => void;
}) {
  const [adding, setAdding] = useState(false);
  const [picked, setPicked] = useState<string[]>([]);

  const isGroup = conversation.type === "group";
  const myRole = members.find((member) => member.userId === currentUserId)?.role;
  const canManage = isGroup && (myRole === "OWNER" || myRole === "ADMIN");
  const canAssignRoles = isGroup && myRole === "OWNER";
  // 群主必须先转让才能退群——服务端会拒绝，这里也不显示按钮
  const canLeave = isGroup && myRole !== "OWNER";

  const memberIds = new Set(members.map((member) => member.userId));
  const candidates = users.filter((user) => !memberIds.has(user.id));

  const roleLabel = (role: MemberRole) =>
    role === "OWNER" ? "群主" : role === "ADMIN" ? "管理员" : "成员";

  const confirmAdd = async () => {
    if (picked.length === 0) {
      setAdding(false);
      return;
    }
    await onAddMembers(picked);
    setPicked([]);
    setAdding(false);
  };

  return (
    <Modal onClose={onClose} className="member-panel-modal">
      <div className="modal-heading">
        <span className="modal-symbol group">{conversation.avatar}</span>
        <div>
          <p className="eyebrow">{isGroup ? "GROUP" : "DIRECT"}</p>
          <h3>{conversation.title}</h3>
        </div>
      </div>

      <div className="member-roster" data-testid="member-roster">
        {members.map((member) => (
          <div className="member-row" data-testid="member-row" key={member.userId}>
            <Avatar label={member.avatar} tone="blue" />
            <span className="member-identity">
              <strong>
                {member.displayName}
                {member.userId === currentUserId && <em> （我）</em>}
              </strong>
              {isGroup && <small data-testid="member-role">{roleLabel(member.role)}</small>}
            </span>
            {isGroup && member.userId !== currentUserId && (
              <span className="member-actions">
                {canAssignRoles && member.role !== "OWNER" && (
                  <button
                    type="button"
                    title={member.role === "ADMIN" ? "撤销管理员" : "设为管理员"}
                    onClick={() => void onChangeRole(
                      member.userId,
                      member.role === "ADMIN" ? "MEMBER" : "ADMIN",
                    )}
                  >
                    {member.role === "ADMIN" ? "撤销管理" : "设为管理"}
                  </button>
                )}
                {canAssignRoles && member.role !== "OWNER" && (
                  <button
                    type="button"
                    title="转让群主"
                    onClick={() => void onTransferOwnership(member.userId)}
                  >
                    转让群主
                  </button>
                )}
                {canManage && member.role !== "OWNER" && (
                  <button
                    type="button"
                    className="danger"
                    data-testid="remove-member-button"
                    title="移出群聊"
                    onClick={() => void onRemoveMember(member.userId)}
                  >
                    移出
                  </button>
                )}
              </span>
            )}
          </div>
        ))}
      </div>

      {canManage && (
        adding ? (
          <div className="member-picker">
            <span className="picker-label">添加成员（{picked.length}）</span>
            <div className="picker-list">
              {candidates.length === 0 && (
                <p className="picker-empty">没有可添加的联系人</p>
              )}
              {candidates.map((user) => (
                <button
                  type="button"
                  key={user.id}
                  className={`picker-item ${picked.includes(user.id) ? "selected" : ""}`}
                  onClick={() => setPicked((current) => current.includes(user.id)
                    ? current.filter((id) => id !== user.id)
                    : [...current, user.id])}
                >
                  <Avatar label={user.avatar} tone="blue" />
                  <strong>{user.displayName}</strong>
                  {picked.includes(user.id) && <Icon name="check" />}
                </button>
              ))}
            </div>
            <div className="picker-actions">
              <button type="button" onClick={() => { setAdding(false); setPicked([]); }}>
                取消
              </button>
              <button
                type="button"
                className="primary-button"
                data-testid="confirm-add-members"
                onClick={() => void confirmAdd()}
              >
                确认添加
              </button>
            </div>
          </div>
        ) : (
          <button
            type="button"
            className="ghost-button"
            data-testid="add-members-button"
            onClick={() => setAdding(true)}
          >
            <Icon name="plus" /> 添加成员
          </button>
        )
      )}

      {canLeave && (
        <button
          type="button"
          className="ghost-button danger"
          data-testid="leave-conversation-button"
          onClick={() => void onLeave()}
        >
          退出群聊
        </button>
      )}
      {isGroup && myRole === "OWNER" && (
        <small className="safe-note">
          <Icon name="lock" /> 群主需先转让群主身份才能退群
        </small>
      )}
    </Modal>
  );
}

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
          <button
            aria-label="领取红包"
            className="open-packet-button"
            onClick={onClaim}
            type="button"
          >
            開
          </button>
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

type IconName = "chat" | "people" | "search" | "phone" | "video" | "more" | "wallet" | "logout" | "smile" | "paperclip" | "lock" | "mic" | "micOff" | "plus" | "check";

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
    plus: <path d="M12 5v14M5 12h14" />,
    check: <path d="m4 12 5 5L20 6" />,
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
