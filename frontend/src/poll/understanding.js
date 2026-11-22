// 진행 중인 이해도 체크가 여러 개 보일 수는 없다(강연방당 OPEN 투표 1개). 그래도 첫 번째만 쓴다.
export function findOpenUnderstanding(polls) {
  return polls?.find(poll => poll.type === 'UNDERSTANDING' && poll.status === 'OPEN') ?? null
}

// 남은 시간은 closesAt − serverTime 으로 계산하고 표시용으로만 쓴다. 마감 판단은 서버가 한다.
// 기기 시계가 틀려도 어긋나지 않도록 받은 시점부터 단조 시계로 줄여 나간다.
export function withDeadline(poll, serverTime, now = performance.now()) {
  const left = Date.parse(poll.closesAt) - Date.parse(serverTime)
  return { ...poll, deadline: now + (Number.isFinite(left) ? Math.max(0, left) : 0) }
}

export function remainingSeconds(poll, now = performance.now()) {
  return Math.ceil(Math.max(0, poll.deadline - now) / 1000)
}

export function isClosedEvent(event, pollId) {
  return event?.type === 'POLL_CLOSED' && event.data?.pollId === pollId
}
