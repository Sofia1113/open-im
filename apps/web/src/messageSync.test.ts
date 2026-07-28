import type { Message } from "@open-im/contracts";
import { describe, expect, it } from "vitest";
import {
  collectMessagePages,
  latestMessageSeq,
  mergeMessages,
} from "./messageSync";

function message(seq: number): Message {
  return {
    id: `message-${seq}`,
    conversationId: "conversation-1",
    seq,
    senderId: "alice",
    clientMessageId: `client-message-${seq}`,
    type: "text",
    content: { text: `message ${seq}` },
    sentAt: "2026-07-28T00:00:00Z",
  };
}

describe("message synchronization", () => {
  it("collects every page without creating a history gap", async () => {
    const allMessages = Array.from({ length: 112 }, (_, index) => message(index + 1));
    const cursors: number[] = [];

    const result = await collectMessagePages(0, async (cursor) => {
      cursors.push(cursor);
      return allMessages.filter((item) => item.seq > cursor).slice(0, 100);
    });

    expect(cursors).toEqual([0, 100]);
    expect(result).toHaveLength(112);
    expect(result[0]?.seq).toBe(1);
    expect(result.at(-1)?.seq).toBe(112);
  });

  it("merges realtime and history messages idempotently", () => {
    const result = mergeMessages(
      [message(2), message(3)],
      [message(1), message(2)],
    );

    expect(result.map((item) => item.seq)).toEqual([1, 2, 3]);
    expect(latestMessageSeq(result)).toBe(3);
  });

  it("rejects a full page that does not advance the cursor", async () => {
    await expect(
      collectMessagePages(100, async () =>
        Array.from({ length: 100 }, () => message(100)),
      ),
    ).rejects.toThrow("消息同步游标没有前进");
  });
});
