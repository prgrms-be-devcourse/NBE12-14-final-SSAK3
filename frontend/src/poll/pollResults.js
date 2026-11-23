// 최근 마감된 투표를 몇 개까지 보여 줄지. 결과를 투표마다 조회하므로 상한을 둔다.
export const recentClosedLimit = 5

// 일반 투표만 골라 진행 중인 것을 먼저, 마감된 것은 최근 순으로 recentClosedLimit 개까지 나열한다.
export function selectGeneralPolls(open, closed, limit = recentClosedLimit) {
  const general = polls => (polls ?? []).filter(poll => poll.type === 'GENERAL')
  const recent = general(closed).sort((a, b) => b.pollId - a.pollId).slice(0, limit)
  return [...general(open), ...recent]
}
