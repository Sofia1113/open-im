import type { Message } from "@open-im/contracts";

/**
 * 消息日期分隔符
 *
 * <b>为什么独立成模块</b>：这些是纯函数，但它们原本写在 App.tsx 里。
 * App.tsx 顶层 import 了 api.ts，而 api.ts 在模块加载时就访问 localStorage——
 * 于是任何想测试这段纯逻辑的测试都会因为 `localStorage is not defined` 而失败，
 * 除非给整个测试套件配上 jsdom 环境。
 *
 * 这是一个具体的例子，说明为什么 1300 行的单文件组件是个问题：
 * 它把纯逻辑和副作用绑在一起，纯逻辑因此变得不可测。修复方式不是给测试
 * 配一个浏览器环境，而是把纯函数搬出来。
 *
 * <b>修正的缺陷</b>：消息列表顶部曾硬编码一个 `<span>今天</span>`，
 * 无论列表里的消息实际是哪天发的。断线补拉出几个月前的历史消息后，
 * 它们全部被显示在"今天"下面——一个不会报错、不影响数据、但让用户
 * 对时间线产生错误认知的显示错误。
 */

/**
 * 该消息前是否需要日期分隔符，以及显示什么。
 *
 * @param previous 列表中的上一条消息。为空（第一条）时总是返回分隔符
 * @returns 分隔符文案，或 null 表示与上一条同一天、不需要插入
 */
export function dateDividerFor(
  message: Message,
  previous: Message | undefined,
): string | null {
  const current = new Date(message.sentAt);
  if (previous && isSameDay(new Date(previous.sentAt), current)) {
    return null;
  }
  return describeDay(current);
}

/**
 * 两个时刻是否属于同一天（按本地时区）。
 *
 * 必须比较年份——只比较月和日会让"去年的今天"被判成同一天，
 * 于是跨年的两条消息之间不会插入分隔符。
 */
export function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

/**
 * 日期的人类可读描述。
 *
 * 今天/昨天用相对表述，更早的用绝对日期。往年的日期带上年份——
 * 否则"3月5日"在年末看到时无法判断是哪一年。
 */
export function describeDay(value: Date): string {
  const now = new Date();
  if (isSameDay(value, now)) {
    return "今天";
  }
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (isSameDay(value, yesterday)) {
    return "昨天";
  }
  const sameYear = value.getFullYear() === now.getFullYear();
  return new Intl.DateTimeFormat("zh-CN", {
    year: sameYear ? undefined : "numeric",
    month: "long",
    day: "numeric",
    weekday: sameYear ? "short" : undefined,
  }).format(value);
}
