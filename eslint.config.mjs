import js from "@eslint/js";
import globals from "globals";
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";

/**
 * ESLint 配置
 *
 * 规则取舍的原则：只保留能指出真实缺陷的规则，不引入纯风格争议项。
 * 缩进、引号、分号等格式问题由 .editorconfig 与编辑器负责，
 * 放进 lint 只会制造与代码审查无关的噪音。
 */
export default tseslint.config(
  {
    // 构建产物与依赖不参与检查
    ignores: [
      "**/dist/**",
      "**/node_modules/**",
      "**/target/**",
      "**/coverage/**",
      "apps/server/**",
      // k6 脚本运行在 k6 自己的运行时中，使用 __ENV/__VU 等专有全局量，
      // 用浏览器/Node 的规则集检查它只会产生误报
      "load-test/**",
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,

  {
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: "module",
      globals: {
        ...globals.browser,
        ...globals.es2022,
      },
    },
    plugins: {
      "react-hooks": reactHooks,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,

      // 未使用变量通常是重构残留；下划线前缀表示"有意忽略"
      "@typescript-eslint/no-unused-vars": [
        "error",
        {
          argsIgnorePattern: "^_",
          varsIgnorePattern: "^_",
          caughtErrors: "none",
        },
      ],

      // any 会让 tsconfig 里开启的严格检查形同虚设
      "@typescript-eslint/no-explicit-any": "error",

      // 抑制类型错误必须写明原因，避免无声地掩盖问题
      "@typescript-eslint/ban-ts-comment": [
        "error",
        { "ts-expect-error": "allow-with-description", "ts-ignore": true },
      ],

      // 常见的真实缺陷来源
      eqeqeq: ["error", "always", { null: "ignore" }],
      "no-console": ["warn", { allow: ["warn", "error"] }],
      "no-var": "error",
      "prefer-const": "error",

      // 浮空的 Promise 往往意味着漏掉的错误处理
      "no-floating-decimal": "error",
    },
  },

  {
    // 测试文件放宽：断言辅助函数里出现的一次性变量不值得为之改名
    files: ["**/*.test.{ts,tsx}"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
);
