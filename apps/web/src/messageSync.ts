import type { Message } from "@open-im/contracts";

export const MESSAGE_PAGE_SIZE = 100;

export function latestMessageSeq(messages: Message[]): number {
  return messages.reduce((latest, message) => Math.max(latest, message.seq), 0);
}

/**
 * 已加载消息中最早的 seq，作为向前翻页的游标。
 *
 * 返回 0 表示还没有任何消息——此时应当走"取最新一页"而不是"取更早一页"。
 */
export function earliestMessageSeq(messages: Message[]): number {
  return messages.reduce(
    (earliest, message) => (earliest === 0 ? message.seq : Math.min(earliest, message.seq)),
    0,
  );
}

export function mergeMessages(current: Message[], incoming: Message[]): Message[] {
  const byId = new Map(current.map((message) => [message.id, message]));
  for (const message of incoming) byId.set(message.id, message);
  return [...byId.values()].sort((left, right) => left.seq - right.seq);
}

/**
 * 断线补拉：从 afterSeq 往后翻到没有更多。
 *
 * <b>只用于重连补拉，不用于打开会话。</b>打开会话曾经也走这里（从 afterSeq=0
 * 开始），那等于把整个会话的全部历史拉进内存——五万条消息的会话点开就是
 * 500 次串行请求。现在打开会话走 `/messages/latest` 取最新一页，
 * 更早的内容由用户向上滚动时按需加载。
 *
 * 补拉仍然需要翻页到底：离线期间漏掉的消息必须全部补齐，否则本地的 seq
 * 会出现空洞，而客户端正是靠 seq 连续性判断有没有漏收。
 */
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
