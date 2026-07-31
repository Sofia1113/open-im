import type { Message } from "@open-im/contracts";
import { describe, expect, it } from "vitest";

import { dateDividerFor, describeDay, isSameDay } from "./messageDate";

/**
 * 消息日期分隔符
 *
 * <b>修正的缺陷</b>：消息列表顶部曾硬编码一个 `<span>今天</span>`，
 * 无论列表里的消息实际是哪天发的。断线补拉出几个月前的历史消息后，
 * 它们全部显示在"今天"下面。
 *
 * 这类缺陷不会让任何断言失败、不会报错、不会影响数据——它只是让用户
 * 对时间线产生错误认知。没有测试的话它可以存在很久。
 */

/**
 * 构造一条测试消息。
 *
 * 字段齐全而不是用 `as Message` 强转：强转会让契约变更（新增必填字段）
 * 静默通过类型检查，而这里的目的之一就是让契约变更被发现。
 */
function messageAt(iso: string): Message {
  return {
    id: `m-${iso}`,
    conversationId: "c-1",
    senderId: "u-1",
    clientMessageId: `client-${iso}`,
    seq: 1,
    type: "text",
    content: { text: "hi" },
    sentAt: iso,
  };
}

/** 构造相对今天偏移若干天的 ISO 时间，避免测试与固定日期绑定 */
function daysAgo(days: number, hour = 12): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  date.setHours(hour, 0, 0, 0);
  return date.toISOString();
}

describe("isSameDay", () => {
  it("同一天的不同时刻视为同一天", () => {
    const morning = new Date("2026-07-30T01:00:00");
    const night = new Date("2026-07-30T23:59:00");
    expect(isSameDay(morning, night)).toBe(true);
  });

  it("相邻两天不视为同一天", () => {
    expect(
      isSameDay(new Date("2026-07-30T23:59:00"), new Date("2026-07-31T00:01:00")),
    ).toBe(false);
  });

  it("跨年同月同日不视为同一天", () => {
    // 只比较月和日会让去年的今天被判成同一天
    expect(
      isSameDay(new Date("2025-07-30T12:00:00"), new Date("2026-07-30T12:00:00")),
    ).toBe(false);
  });
});

describe("describeDay", () => {
  it("今天显示为今天", () => {
    expect(describeDay(new Date())).toBe("今天");
  });

  it("昨天显示为昨天", () => {
    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    expect(describeDay(yesterday)).toBe("昨天");
  });

  it("更早的日期显示具体日期而不是今天", () => {
    const lastWeek = new Date();
    lastWeek.setDate(lastWeek.getDate() - 7);

    const label = describeDay(lastWeek);

    // 关键断言：不能是"今天"或"昨天"——那正是修复前的行为
    expect(label).not.toBe("今天");
    expect(label).not.toBe("昨天");
    expect(label).toMatch(/月/);
  });

  it("往年的日期带上年份", () => {
    const lastYear = new Date();
    lastYear.setFullYear(lastYear.getFullYear() - 1);

    // 不带年份的话，"3月5日"在年末看到时无法判断是哪一年
    expect(describeDay(lastYear)).toMatch(/年/);
  });
});

describe("dateDividerFor", () => {
  it("第一条消息总是带分隔符", () => {
    expect(dateDividerFor(messageAt(daysAgo(0)), undefined)).toBe("今天");
  });

  it("与上一条同一天时不重复插入分隔符", () => {
    const first = messageAt(daysAgo(0, 9));
    const second = messageAt(daysAgo(0, 10));

    expect(dateDividerFor(second, first)).toBeNull();
  });

  it("跨天时插入新的分隔符", () => {
    const yesterday = messageAt(daysAgo(1));
    const today = messageAt(daysAgo(0));

    expect(dateDividerFor(today, yesterday)).toBe("今天");
  });

  it("历史消息显示自己的日期，而不是今天", () => {
    // 这是原缺陷的直接回归测试：补拉出来的历史消息曾全部被标为"今天"
    const older = messageAt(daysAgo(30));

    const label = dateDividerFor(older, undefined);

    expect(label).not.toBe("今天");
    expect(label).not.toBeNull();
  });

  it("连续多天的消息各自得到一个分隔符", () => {
    const messages = [daysAgo(3), daysAgo(2), daysAgo(1), daysAgo(0)].map(messageAt);

    const dividers = messages.map((message, index) =>
      dateDividerFor(message, messages[index - 1]),
    );

    // 四条消息分属四天，每条都应有分隔符
    expect(dividers.filter((d) => d !== null)).toHaveLength(4);
    expect(dividers[3]).toBe("今天");
    expect(dividers[2]).toBe("昨天");
  });
});
