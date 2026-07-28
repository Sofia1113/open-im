import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import "./styles.css";

// ============================================================
// OpenIM Web 客户端入口
// 挂载 React 应用根节点。
// ============================================================

const root = document.getElementById("root");
if (!root) throw new Error("Root element is missing");

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
