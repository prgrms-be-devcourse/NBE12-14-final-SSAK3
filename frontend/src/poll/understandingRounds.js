import { percentage } from './understandingSummary.js'

export const statusLabels = {
  UNDERSTOOD: '이해됨',
  CONFUSED: '헷갈림',
  NOT_UNDERSTOOD: '모르겠음',
}

export const statusOrder = Object.keys(statusLabels)

const clock = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

export const roundLabel = index => `${index + 1}회차`

export function roundTime(round) {
  const time = Date.parse(round.openedAt)

  return Number.isFinite(time)
      ? clock.format(new Date(time))
      : '—'
}

export const formatRatio = value =>
    Number.isFinite(value)
        ? `${value.toFixed(1)}%`
        : '—'

export function shares(round) {
  return Object.fromEntries(
      statusOrder.map(status => [
        status,
        percentage(
            round.counts?.[status] ?? 0,
            round.respondedParticipants,
        ),
      ]),
  )
}

export function barSegments(round) {
  if (round.respondedParticipants <= 0) {
    return []
  }

  const values = shares(round)

  return statusOrder
      .map(status => {
        const count = round.counts?.[status] ?? 0

        return {
          status,
          count,
          value: values[status],

          width: (
              count / round.respondedParticipants
          ) * 100,
        }
      })
      .filter(segment => segment.count > 0)
}

// 라운드 API 조회에 실패하더라도 이미 받은
// 마감 투표 결과는 표시할 수 있도록 변환한다.
export function roundFromPoll(poll) {
  const counts = Object.fromEntries(
      statusOrder.map(status => [status, 0]),
  )

  for (const option of poll.options ?? []) {
    if (Object.hasOwn(counts, option.understandingStatus)) {
      counts[option.understandingStatus] = option.count
    }
  }

  const lackCount =
      counts.CONFUSED +
      counts.NOT_UNDERSTOOD

  return {
    pollId: poll.pollId,
    openedAt: poll.createdAt,
    closedAt: poll.closedAt,

    totalParticipants: poll.totalParticipants,
    respondedParticipants: poll.respondedParticipants,

    counts,
    lackCount,

    lackRatio: percentage(
        lackCount,
        poll.respondedParticipants,
    ),

    // 서버의 이해 부족 판정은 임의로 추정하지 않는다.
    lowUnderstanding: null,
  }
}

export function mergeClosedRounds(polls, serverRounds) {
  const merged = new Map()

  for (const poll of polls) {
    if (poll.status === 'CLOSED') {
      merged.set(
          poll.pollId,
          roundFromPoll(poll),
      )
    }
  }

  // 서버의 확정 라운드 데이터가 있으면 우선 사용한다.
  for (const round of serverRounds ?? []) {
    merged.set(round.pollId, round)
  }

  return [...merged.values()].sort(
      (first, second) =>
          first.pollId - second.pollId,
  )
}