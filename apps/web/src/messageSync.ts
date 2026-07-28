import type { Message } from "@open-im/contracts";

export const MESSAGE_PAGE_SIZE = 100;

export function latestMessageSeq(messages: Message[]): number {
  return messages.reduce((latest, message) => Math.max(latest, message.seq), 0);
}

export function mergeMessages(current: Message[], incoming: Message[]): Message[] {
  const byId = new Map(current.map((message) => [message.id, message]));
  for (const message of incoming) byId.set(message.id, message);
  return [...byId.values()].sort((left, right) => left.seq - right.seq);
}

export async function collectMessagePages(
  afterSeq: number,
  fetchPage: (cursor: number) => Promise<Message[]>,
): Promise<Message[]> {
  let cursor = Math.max(0, afterSeq);
  let collected: Message[] = [];

  while (true) {
    const page = await fetchPage(cursor);
    collected = mergeMessages(collected, page);
    if (page.length < MESSAGE_PAGE_SIZE) return collected;

    const nextCursor = latestMessageSeq(page);
    if (nextCursor <= cursor) {
      throw new Error("消息同步游标没有前进");
    }
    cursor = nextCursor;
  }
}
