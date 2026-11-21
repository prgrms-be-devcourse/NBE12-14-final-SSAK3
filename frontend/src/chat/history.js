export async function readHistory(fetchPage, knownThrough, active) {
  const pages = []
  let cursor = null
  do {
    const page = await fetchPage(cursor)
    if (!active()) return []
    if (!Array.isArray(page?.messages)) throw new Error('INVALID_CHAT_PAGE')
    pages.push(page.messages)
    if (!page.hasNext || page.messages[0]?.messageId <= knownThrough) break
    if (!Number.isSafeInteger(page.nextCursor) || page.nextCursor <= 0 || (cursor !== null && page.nextCursor >= cursor)) {
      throw new Error('INVALID_CHAT_CURSOR')
    }
    cursor = page.nextCursor
  } while (active())
  return pages.reverse().flat()
}

// A broadcast may arrive before its REST snapshot or in a different order.
export function mergeMessages(current, incoming) {
  if (!incoming.length) return current
  if (incoming.length === 1 && (!current.length || incoming[0].messageId > current.at(-1).messageId)) {
    return [...current, incoming[0]]
  }
  const byId = new Map(current.map(message => [message.messageId, message]))
  for (const message of incoming) byId.set(message.messageId, message)
  if (byId.size === current.length) return current
  return [...byId.values()].sort((left, right) => left.messageId - right.messageId)
}
