import samples from './sample-content.json'

export const previewOptions = {
  normal: '일반 반응', sparse: '소수 반응', opposing: '상반된 의견', 'missing-data': '기록 누락·분석 실패',
  live: '강연 진행 중', settling: '기록 정리 중', ready: '생성 가능', pending: '생성 대기',
  processing: '생성 중', failed: '생성 실패', empty: '수집 기록 없음', stt: '발언 기록만 있음',
  network: '조회 연결 오류', uncertain: '생성 응답 유실', forbidden: '접근 권한 없음',
}
export const sampleRoom = { roomId: 10, title: '이더리움과 스마트 컨트랙트 이해하기',
  startedAt: '2026-11-18T00:00:00Z', endedAt: '2026-11-18T00:48:59Z' }
const at = seconds => new Date(Date.parse(sampleRoom.startedAt) + seconds * 1000).toISOString()
const emptyOverview = () => ({ mainInterests: [], reviewPoints: [], suggestions: [] })

// Deliberately invented teaching text. No uploaded lecture transcript is shipped to browsers.
const syntheticSegments = [
  { segmentId: 9001, transcript: '이 예제에서는 배포된 컨트랙트에 요청을 보내 상태가 바뀌는 과정을 살펴봅니다.', startAt: at(260), endAt: at(280) },
  { segmentId: 9017, transcript: '예시로 연산마다 가스가 차감되는 과정을 숫자로 표시해 보겠습니다.', startAt: at(1121), endAt: at(1135) },
  { segmentId: 9021, transcript: '가스가 부족하면 실행이 중단되는 경우를 가정하고 결과를 비교해 보겠습니다.', startAt: at(1387), endAt: at(1400) },
  { segmentId: 9022, transcript: 'Web3 개념을 정리한 뒤 짧은 코드 예제를 함께 살펴보겠습니다.', startAt: at(1802), endAt: at(1818) },
]

function contentFor(kind) {
  const source = samples[kind] || samples.normal
  const noAudience = kind === 'empty' || kind === 'stt'
  const missing = kind === 'missing-data'
  const chats = noAudience ? [] : source.chats
  const segments = kind === 'empty' ? [] : syntheticSegments.filter(s => !missing || s.startAt < at(1080) || s.startAt >= at(1440))
  const gap = { id: 4001, startAt: at(1080), endAt: at(1440) }
  const understanding = { pollId: 2001, type: 'UNDERSTANDING', question: '지금 설명을 이해하셨나요?',
    openedAt: at(1140), closedAt: at(1200), respondedParticipants: 2, totalParticipants: 4,
    unrespondedParticipants: 2, counts: { UNDERSTOOD: 1, CONFUSED: 1, NOT_UNDERSTOOD: 0 } }
  const poll = { pollId: 3001, type: 'GENERAL', question: '추가로 보고 싶은 예시는?',
    openedAt: at(1320), closedAt: at(1380), respondedParticipants: 3, totalParticipants: 4,
    unrespondedParticipants: 1, counts: { '가스 한도 소진': 2, '상태 변화': 1 } }
  const timeline = Array.from({ length: 49 }, (_, i) => ({
    startAt: at(i * 60), endAt: at(Math.min((i + 1) * 60, 2939)),
    sttSegments: segments.filter(s => s.startAt < at((i + 1) * 60) && s.endAt > at(i * 60)),
    sttGaps: missing && i >= 18 && i < 24 ? [gap] : [],
    chatMessages: chats.filter(c => Math.floor(c.offsetSeconds / 60) === i)
      .map(c => ({ messageId: c.id, text: c.text, createdAt: at(c.offsetSeconds) })),
    understandingChecks: !noAudience && (kind === 'normal' || !samples[kind]) && i === 19 ? [understanding] : [],
    polls: !noAudience && (kind === 'normal' || !samples[kind]) && i === 22 ? [poll] : [],
    audienceOpinions: missing && i === 19 ? [{ status: 'FAILED', startAt: at(1140), endAt: at(1170) }]
      : kind === 'normal' && i === 19 ? [{ status: 'COMPLETED', topic: '가스 원리',
        summary: '가스가 무한 반복을 막는 원리에 관한 질문이 있었습니다.', sourceMessageIds: [1003] }] : [],
  }))
  const dataLimitations = missing ? ['18:00~24:00 발언 기록이 없어 설명·답변 여부를 확인하기 어렵습니다.',
    '19:00~19:30 청중 의견 AI 분석에 실패했습니다. 남아 있는 원문 기록을 활용했습니다.'] : []
  const overview = noAudience ? emptyOverview() : structuredClone(source.overview)
  // Displayable limitations come from the server in production; this adapter simulates them.
  for (const items of Object.values(overview)) for (const item of items) {
    if (missing) item.limitations = [...dataLimitations]
  }
  if (kind === 'empty') dataLimitations.push('수집된 기록이 없어 요약할 수 없습니다.')
  if (kind === 'stt') dataLimitations.push('청중 반응 기록이 부족해 관심사와 보충 설명 지점을 판단하기 어렵습니다.')
  return { overview, timeline, dataLimitations,
    evidencePolicy: 'AI 요약과 제안은 검토용입니다. 동일 시간대의 관찰은 인과관계를 의미하지 않습니다.' }
}

// In-memory transport only. load() is a read, create() is the only creation action.
export function createPreviewSource(kind) {
  let status = ({ live: 'LIVE', settling: 'NOT_READY', ready: 'NOT_CREATED', uncertain: 'NOT_CREATED',
    pending: 'PENDING', processing: 'PROCESSING', failed: 'FAILED' })[kind] || 'COMPLETED'
  let reads = 0
  let failNext = kind === 'network'
  const content = contentFor(kind)
  return {
    async load() {
      if (kind === 'forbidden') throw Object.assign(new Error('FORBIDDEN'), { status: 403 })
      if (failNext) { failNext = false; throw new Error('NETWORK') }
      if (['PENDING', 'PROCESSING'].includes(status)) {
        reads++
        if (reads >= 3) status = 'COMPLETED'
        else if (reads >= 2) status = 'PROCESSING'
      }
      return { status, content: status === 'COMPLETED' ? content : null }
    },
    async create() {
      if (status === 'NOT_CREATED') { status = 'PENDING'; reads = 0 }
      if (kind === 'uncertain') throw new Error('NETWORK')
      return { status, content: status === 'COMPLETED' ? content : null }
    },
  }
}
