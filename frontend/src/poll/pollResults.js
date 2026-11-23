// 일반 투표에서 최근 마감된 투표를 몇 개까지 보여 줄지. 결과를 투표마다 조회하므로 상한을 둔다.
export const recentClosedLimit = 5

// type 인 투표만 골라 진행 중인 것을 먼저, 마감된 것은 최근 순으로 limit 개까지 나열한다.
export function selectPolls(open, closed, type, limit = Infinity) {
  const ofType = polls => (polls ?? []).filter(poll => poll.type === type)
  const recent = ofType(closed).sort((a, b) => b.pollId - a.pollId).slice(0, limit)
  return [...ofType(open), ...recent]
}
