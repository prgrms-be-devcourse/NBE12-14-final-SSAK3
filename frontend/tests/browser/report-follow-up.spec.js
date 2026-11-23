import { devices, expect, test } from '@playwright/test'

const reportPath = '/host/rooms/10/report'
const startedAt = '2026-11-18T00:00:00Z'
const endedAt = '2026-11-18T00:01:00Z'
const original = '가스 한도는 뭔가요? 실패한 거래에도 수수료를 내나요?'
const quote = '실패한 거래에도 수수료를 내나요?'
const transcript = '연산에 사용한 가스의 양을 먼저 계산합니다.'
const group = {
  title: '실패한 거래의 수수료', kind: 'DRAFT', text: '사용한 가스의 양을 기준으로 예시를 정리해 보세요.',
  questions: [{ requests: [{ messageId: 14, quote, reexplanation: false },
    { messageId: 15, quote: '실패한 예시를 다시 보여 주세요.', reexplanation: true }] }],
  segmentIds: [31],
}
const otherGroups = ['한도 설정', '배포 비용', '환불 조건'].map((title, i) => ({
  title, kind: 'PREPARATION', text: title + '을 설명하는 예시를 준비해 보세요.',
  questions: [{ requests: [{ messageId: 20 + i, quote: title, reexplanation: false }] }], segmentIds: [],
}))

async function openReport(page, followUp) {
  const room = { roomId: 10, title: '가스 수수료 강연', status: 'ENDED', startedAt, endedAt }
  const chatMessages = [
    { messageId: 14, createdAt: '2026-11-18T00:00:14.500Z', text: original },
    { messageId: 15, createdAt: '2026-11-18T00:00:16Z', text: '선생님, 실패한 예시를 다시 보여 주세요.' },
    ...otherGroups.map(({ title }, i) => ({ messageId: 20 + i, createdAt: '2026-11-18T00:00:21Z', text: title + '이 궁금해요.' })),
  ]
  const content = { overview: { mainInterests: [], reviewPoints: [], suggestions: [] }, followUp,
    timeline: [{ startAt: startedAt, endAt: endedAt, chatMessages,
      sttSegments: [{ segmentId: 31, startAt: '2026-11-18T00:00:04Z', endAt: '2026-11-18T00:00:07Z', transcript }] }] }
  await page.route('**/api/v1/rooms/10', route => route.fulfill({ json: { data: room } }))
  await page.route('**/api/v1/rooms/10/reports', route => route.fulfill({
    json: { data: { roomId: 10, status: 'COMPLETED', content } },
  }))
  await page.goto(reportPath)
  return page.getByRole('region', { name: '후속 답변 후보', exact: true })
}

for (const [device, options] of [['PC', { viewport: { width: 1280, height: 900 } }], ['모바일', devices['Pixel 7']]]) {
  test.describe(device, () => {
    test.use({ viewport: options.viewport, isMobile: !!options.isMobile, hasTouch: !!options.hasTouch })
    test('후속 답변 후보에서 질문과 발언 근거를 따로 읽고 나머지 묶음을 펼친다', async ({ page }) => {
      const section = await openReport(page, { status: 'CANDIDATES', groups: [group, ...otherGroups] })
      await expect(section.getByRole('article')).toHaveCount(3)
      const sections = page.getByRole('heading', { level: 2 })
      await expect(sections).toHaveText(['강연을 돌아보는 세 가지 관점', '후속 답변 후보', '시간대별 상세 기록'])
      const card = section.getByRole('article').first()
      await expect(card).toContainText('답변 확인 필요')
      await expect(card).toContainText('보충 설명 초안')
      await card.getByText('청중 질문 원문', { exact: false }).click()
      const highlighted = card.locator('mark')
      await expect(highlighted).toHaveText([quote, '실패한 예시를 다시 보여 주세요.'])
      await expect(card.locator('.report-original').first()).toHaveText(original)
      await expect(card.getByText('09:00:14.500', { exact: true })).toBeVisible()
      await expect(card.getByText(transcript)).toBeHidden()
      await card.getByText('참고한 강사 발언', { exact: false }).click()
      await expect(card.getByText(transcript)).toBeVisible()
      await expect(card.getByText('09:00:04', { exact: true })).toBeVisible()
      await expect(highlighted.first()).toBeVisible()
      const preparation = section.getByRole('article').nth(1)
      await expect(preparation).toContainText('준비 방향')
      await expect(preparation.getByText('참고한 강사 발언', { exact: false })).toHaveCount(0)
      await section.getByRole('button', { name: /더 보기/ }).click()
      await expect(section.getByRole('article')).toHaveCount(4)
      await expect(section.getByRole('heading', { name: '환불 조건' })).toBeVisible()
      await expect(highlighted.first()).toBeVisible()
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    })
  })
}

for (const [status, message] of [
  ['NO_QUESTIONS', '청중 기록에서 후속 검토할 질문을 찾지 못했습니다.'],
  ['ALL_ANSWERED', '청중 질문에 대한 답변이 강사 발언에서 확인되었습니다.'],
  ['UNAVAILABLE', '근거 기록을 확인하기 어려워 후속 답변 후보를 표시할 수 없습니다.'],
]) {
  test(status + ' 상태에서도 전체 요약과 시간대별 기록을 읽을 수 있다', async ({ page }) => {
    const section = await openReport(page, { status, groups: [] })
    await expect(section.getByText(message, { exact: true })).toBeVisible()
    await expect(section.getByRole('article')).toHaveCount(0)
    await expect(page.getByRole('heading', { name: '강연을 돌아보는 세 가지 관점' })).toBeVisible()
    await page.locator('.report-window summary').click()
    await expect(page.locator('.report-window').getByText(original, { exact: true })).toBeVisible()
  })
}
