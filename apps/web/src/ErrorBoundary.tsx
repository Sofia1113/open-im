import { Component, type ErrorInfo, type ReactNode } from "react";

/**
 * 错误边界
 *
 * <b>解决的问题</b>：在此之前应用没有任何错误边界。React 的默认行为是——
 * 渲染期间抛出的任何异常都会卸载整棵组件树，用户看到的是<b>一片空白</b>，
 * 控制台里有堆栈但普通用户不会去看。刷新可能恢复（如果异常来自某个瞬时状态），
 * 也可能不会（如果来自持久化的坏数据），而用户无从判断。
 *
 * 一个具体的例子：消息载荷里缺了某个预期字段时，
 * {@code contentString(message, "text")} 之类的取值会抛异常——
 * 一条格式异常的消息就能让整个工作台变成白屏，而其余会话完全正常。
 *
 * <b>为什么必须是 class 组件</b>：React 至今没有提供函数式的错误边界 API。
 * {@code componentDidCatch} 与 {@code getDerivedStateFromError} 只存在于 class 上。
 * 这是本项目里唯一的 class 组件，不是风格不统一，而是没有替代方案。
 *
 * <b>边界的粒度</b>：整个应用一个边界（见 main.tsx），加上工作台内部一个更细的
 * 边界。两层的理由是——消息列表崩了不该让用户连"退出登录"按钮都点不到，
 * 而那个按钮在外层。
 */
interface Props {
  children: ReactNode;
  /** 出错时的替代界面。不传则用默认的通用界面 */
  fallback?: (error: Error, reset: () => void) => ReactNode;
  /** 边界名，进日志用于区分是哪一层捕获的 */
  name: string;
}

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // 目前只打控制台。接入前端错误上报（Sentry 等）时，这里是唯一的接入点——
    // componentStack 比 error.stack 更有用，它指出是哪个组件挂了。
    console.error(
      `[ErrorBoundary:${this.props.name}] 渲染异常`,
      error,
      info.componentStack,
    );
  }

  private reset = (): void => {
    this.setState({ error: null });
  };

  render(): ReactNode {
    const { error } = this.state;
    if (!error) {
      return this.props.children;
    }
    if (this.props.fallback) {
      return this.props.fallback(error, this.reset);
    }
    return <DefaultFallback error={error} onReset={this.reset} />;
  }
}

/**
 * 默认的错误界面。
 *
 * 三个要点：
 * <ul>
 *   <li><b>说清发生了什么</b>，而不是只显示"出错了"——用户需要知道数据有没有丢</li>
 *   <li><b>给出两个不同力度的出路</b>：重试（不丢状态）与刷新（丢状态但更可能有效）</li>
 *   <li><b>展示错误摘要</b>：用户报告问题时能提供它，比"页面白了"有用得多</li>
 * </ul>
 * 刻意<b>不</b>提供"清除本地数据"按钮——那会让用户在不理解后果的情况下退出登录。
 */
function DefaultFallback({
  error,
  onReset,
}: {
  error: Error;
  onReset: () => void;
}) {
  return (
    <div className="error-boundary" role="alert">
      <div className="error-boundary-card">
        <div className="error-boundary-icon">!</div>
        <h2>界面遇到了一个问题</h2>
        <p>
          这部分内容没能正常显示。<strong>你的消息和余额都没有受影响</strong>
          ——它们保存在服务端，重新加载后会恢复。
        </p>
        <div className="error-boundary-actions">
          <button className="primary-button" onClick={onReset} type="button">
            重试
          </button>
          <button
            className="ghost-button"
            onClick={() => window.location.reload()}
            type="button"
          >
            重新加载页面
          </button>
        </div>
        <details className="error-boundary-details">
          <summary>错误详情</summary>
          <code>{error.message || error.name || "未知错误"}</code>
        </details>
      </div>
    </div>
  );
}
