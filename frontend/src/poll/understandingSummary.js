// 서버의 PollResultMath.percentage 와 같은 규칙이다. 소수 첫째 자리 반올림(HALF_UP), 분모가 0 이면 0.
export function percentage(part, whole) {
  if (!whole) return 0
  return Math.floor((part * 2000 + whole) / (whole * 2)) / 10
}

// 이해도 체크 중 가장 최근 것. 마감된 투표 목록에서 지난 체크의 최종 결과를 고를 때 쓴다.
export function latestUnderstanding(polls) {
  return (polls ?? []).filter(poll => poll.type === 'UNDERSTANDING')
    .reduce((latest, poll) => (!latest || poll.pollId > latest.pollId ? poll : latest), null)
}

// 진행 중에는 POLL_RESULTS_UPDATED 가 선택지별 인원만 보낸다. 비율은 서버와 같은 규칙으로 다시 계산한다.
// 다른 투표의 이벤트거나 이미 마감된 집계면 화면을 그대로 둔다.
export function applyCounts(results, data) {
  if (!results || results.status !== 'OPEN' || data?.pollId !== results.pollId) return results
  const responded = data.respondedParticipants
  const total = data.totalParticipants
  return {
    ...results,
    totalParticipants: total,
    respondedParticipants: responded,
    unrespondedParticipants: data.unrespondedParticipants,
    responseRate: percentage(responded, total),
    options: results.options.map(option => {
      const count = data.counts?.[option.optionId] ?? 0
      return { ...option, count, percentageOfResponses: percentage(count, responded),
        percentageOfAllParticipants: percentage(count, total) }
    }),
  }
}

export function isResultsEvent(event) {
  return event?.type === 'POLL_RESULTS_UPDATED'
}
