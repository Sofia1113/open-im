import { describe, expect, it } from "vitest";
import {
  createRedPacketSchema,
  demoLoginSchema,
  sendMessageSchema,
} from "../src/index";

describe("shared contracts", () => {
  it("normalizes and validates text messages", () => {
    const parsed = sendMessageSchema.parse({
      conversationId: "conversation-1",
      clientMessageId: "client-message-1",
      type: "text",
      content: { text: "  hello  " },
    });

    expect(parsed.content.text).toBe("hello");
  });

  it("rejects red packets whose amount cannot cover every share", () => {
    const parsed = createRedPacketSchema.safeParse({
      conversationId: "conversation-1",
      mode: "random",
      totalAmount: 2,
      totalCount: 3,
      greeting: "hello",
      clientMessageId: "client-message-1",
      idempotencyKey: "red-packet-request-1",
    });

    expect(parsed.success).toBe(false);
  });

  it("rejects oversized device identifiers", () => {
    const parsed = demoLoginSchema.safeParse({
      userId: "alice",
      deviceId: "d".repeat(129),
      deviceName: "Browser",
    });

    expect(parsed.success).toBe(false);
  });
});
