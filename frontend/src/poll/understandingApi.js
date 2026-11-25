import { request } from '../stt/api.js'

// 이해도 체크는 투표(type=UNDERSTANDING)의 한 종류라 투표 API 를 그대로 쓴다.
export function understandingApi(roomId) {
  const base = `/rooms/${encodeURIComponent(roomId)}/polls`
  return {
    // 진행 중인 투표 목록. 청중에게는 질문·선택지·남은 시간만 내려온다.
    open: () => request(`${base}?status=OPEN`),
    // 재접속한 청중이 자신의 선택을 복원한다. 미응답이면 optionId 가 null 이다.
    mine: pollId => request(`${base}/${encodeURIComponent(pollId)}/responses/me`),
    // 강연자만: 마감된 이해도 체크를 시간순으로 비교한다. 이해 부족 기준(minCount·ratio)은 서버가 정한 값을 그대로 쓴다.
    rounds: () => request(`${base}/understanding-rounds`),
    // 마감 전에는 다시 보내 선택을 바꿀 수 있다.
    respond: (pollId, optionId) => request(`${base}/${encodeURIComponent(pollId)}/responses/me`,
      { method: 'PUT', body: { optionId } }),
  }
}
