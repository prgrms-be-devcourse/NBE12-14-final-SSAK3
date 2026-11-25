import { percentage } from './understandingSummary.js'

// 막대를 쌓는 순서(아래부터). 서버의 UnderstandingStatus 이름과 같다.
export const stackOrder = ['UNDERSTOOD', 'CONFUSED', 'NOT_UNDERSTOOD']
export const statusLabels = { UNDERSTOOD: '이해됨', CONFUSED: '헷갈림', NOT_UNDERSTOOD: '모르겠음' }

// 그래프 그림판(viewBox)의 막대가 놓이는 영역. 위쪽이 100%, 아래쪽이 0% 다.
export const plot = { left: 44, right: 540, top: 0, height: 160 }
const maxBarWidth = 56

const clock = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

export const roundLabel = index => `${index + 1}회`
export const roundTime = round => clock.format(new Date(round.openedAt))
export const formatRatio = value => `${value.toFixed(1)}%`

const tidy = value => Math.round(value * 10) / 10

// 응답자를 100% 로 본 선택지별 비율. 응답하지 않은 사람은 어느 선택지로도 세지 않고, 응답자가 없으면 모두 0 이다.
export function shares(round) {
  return Object.fromEntries(stackOrder.map(status => [status, percentage(round.counts?.[status] ?? 0, round.respondedParticipants)]))
}

// 라운드마다 막대 하나. 이해됨이 맨 아래이고 그 위로 헷갈림, 모르겠음을 쌓는다. 응답자가 없으면 칸이 없다.
export function bars(rounds) {
  const slot = (plot.right - plot.left) / Math.max(rounds.length, 1)
  const width = tidy(Math.min(maxBarWidth, slot * 0.56))
  return rounds.map((round, index) => {
    const responded = round.respondedParticipants
    let bottom = plot.top + plot.height
    const segments = responded > 0 ? stackOrder.map(status => {
      const height = (round.counts?.[status] ?? 0) / responded * plot.height
      bottom -= height
      return { status, y: tidy(bottom), height: tidy(height) }
    }).filter(segment => segment.height > 0) : []
    return { x: tidy(plot.left + slot * index + (slot - width) / 2), width, centerX: tidy(plot.left + slot * (index + 0.5)), segments }
  })
}

// 이해 부족 기준선의 높이. 헷갈림+모르겠음이 ratio% 이상이면 이해됨 칸이 이 선 아래에서 끝난다.
export const thresholdY = ratio => tidy(plot.top + plot.height * ratio / 100)

// 화면 낭독용 한 줄 요약. 기준을 넘은 라운드가 있으면 번호를 알려 준다.
export function describe(rounds, ratio) {
  const low = rounds.flatMap((round, index) => round.lowUnderstanding ? [roundLabel(index)] : [])
  const base = `이해도 체크 라운드별 변화 막대 그래프. 마감된 ${rounds.length}개 라운드를 표시합니다.`
  return low.length
    ? `${base} ${low.join(', ')}에서 이해 부족 기준(${ratio}%)을 넘었습니다.`
    : `${base} 이해 부족 기준(${ratio}%)을 넘은 라운드는 없습니다.`
}
