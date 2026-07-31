import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { ErrorBoundary } from "./ErrorBoundary";
import "./styles.css";

// ============================================================
// OpenIM Web 客户端入口
// 挂载 React 应用根节点。
// ============================================================

const root = document.getElementById("root");
if (!root) throw new Error("Root element is missing");

createRoot(root).render(
  <StrictMode>
    {/*
      最外层错误边界。没有它的话，渲染期间任何未捕获的异常都会让 React
      卸载整棵树，用户看到一片空白且没有任何可操作的出路。

      工作台内部还有一层更细的边界（见 App.tsx），因此消息列表出错时
      "退出登录"这类外层控件仍然可用。
    */}
    <ErrorBoundary name="root">
      <App />
    </ErrorBoundary>
  </StrictMode>,
);
