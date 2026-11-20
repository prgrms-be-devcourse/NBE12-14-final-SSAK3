import { test, expect } from '@playwright/test'

const open = async page => {
  const calls = []
  page.on('request', request => { if (request.url().includes('/api/') || request.url().includes('api.openai')) calls.push(request.url()) })
  await page.goto('/preview/reports')
  await expect(page.getByRole('heading', { name: '강연을 돌아보는 세 가지 관점' })).toBeVisible()
  return calls
}
const scenario = (page, value) => page.getByLabel('시나리오').selectOption(value)

test('evidence opens its window, highlights the record and preserves other expansions', async ({ page }) => {
  const calls = await open(page)
  const evidence = page.locator('.report-evidence').first()
  await evidence.locator('summary').click()
  await expect(evidence).toHaveAttribute('open', '')
  await evidence.getByRole('button', { name: /해당 시간대 기록 보기/ }).first().click()
  await expect(page.locator('#report-record-4-CHAT_MESSAGE-1001')).toBeFocused()
  await expect(page.locator('#report-record-4-CHAT_MESSAGE-1001')).toHaveClass(/report-highlight/)
  await expect(evidence).toHaveAttribute('open', '')
  const windows = page.locator('.report-window')
  await windows.nth(5).locator('summary').click()
  await expect(windows.nth(4)).toHaveAttribute('open', '')
  await expect(windows.nth(5)).toHaveAttribute('open', '')
  await page.reload()
  await expect(page.locator('.report-window[open]')).toHaveCount(0)
  expect(calls).toEqual([])
})

test('opposing perspectives have separate evidence; sparse summaries stay empty', async ({ page }) => {
  await open(page)
  await scenario(page, 'opposing')
  const perspectives = page.locator('.report-perspective')
  await expect(perspectives).toHaveCount(2)
  await perspectives.nth(0).locator('summary').click()
  await expect(perspectives.nth(0).locator('.report-evidence-record')).toHaveCount(1)
  await expect(perspectives.nth(1).locator('details')).not.toHaveAttribute('open', '')
  await scenario(page, 'sparse')
  await expect(page.getByText('이 항목에 표시할 요약이 없습니다.', { exact: true })).toHaveCount(2)
})

test('limitations and empty/STT-only results are visible without inventing audience responses', async ({ page }) => {
  await open(page)
  await scenario(page, 'missing-data')
  await expect(page.locator('.report-global-limit')).not.toHaveAttribute('open', '')
  await expect(page.locator('.report-card .report-limits')).toHaveCount(2)
  await page.locator('.report-window').nth(19).locator('summary').click()
  await expect(page.locator('.report-window').nth(19).getByText(/청중 의견 AI 분석 실패/)).toBeVisible()
  await scenario(page, 'empty')
  await expect(page.getByText('표시할 시간대별 기록이 없습니다.')).toBeVisible()
  await scenario(page, 'stt')
  await expect(page.locator('.report-card')).toHaveCount(0)
  await page.locator('.report-window').nth(4).locator('summary').click()
  await expect(page.locator('#report-record-4-STT_SEGMENT-9001')).toBeVisible()
})

test('generation polls every five seconds and stops when complete', async ({ page }) => {
  await open(page)
  await page.clock.install()
  await scenario(page, 'ready')
  await page.getByRole('button', { name: '보고서 생성', exact: true }).click()
  await expect(page.getByRole('heading', { name: '보고서 생성 대기 중' })).toBeVisible()
  await page.clock.runFor(4999)
  await expect(page.getByRole('heading', { name: '보고서 생성 대기 중' })).toBeVisible()
  await page.clock.runFor(10001)
  await expect(page.getByText('보고서 완료', { exact: true })).toBeVisible()
  await page.clock.runFor(10000)
  await expect(page.getByText('보고서 완료', { exact: true })).toBeVisible()
})

test('hidden tab pauses polling and returning resumes an immediate read', async ({ page }) => {
  await open(page)
  await page.clock.install()
  await scenario(page, 'ready')
  await page.getByRole('button', { name: '보고서 생성', exact: true }).click()
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await page.clock.runFor(30000)
  await expect(page.getByRole('heading', { name: '보고서 생성 대기 중' })).toBeVisible()
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await page.clock.runFor(10000)
  await expect(page.getByText('보고서 완료', { exact: true })).toBeVisible()
})

test('uncertain creation only offers status check; failure never offers regeneration', async ({ page }) => {
  await open(page)
  await scenario(page, 'uncertain')
  await page.getByRole('button', { name: '보고서 생성', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('요청 결과를 확인하지 못했습니다')
  await expect(page.getByRole('button', { name: '보고서 생성', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '상태 확인', exact: true }).click()
  await expect(page.getByRole('heading', { name: '보고서 생성 대기 중' })).toBeVisible()
  await scenario(page, 'failed')
  await expect(page.getByRole('heading', { name: '보고서 생성에 실패했습니다.' })).toBeVisible()
  await expect(page.getByRole('button', { name: /생성/ })).toHaveCount(0)
  await scenario(page, 'forbidden')
  await expect(page.locator('.report-card')).toHaveCount(0)
  await expect(page.getByRole('alert')).toContainText('접근 권한')
})

test('mobile layout fits viewport and retains evidence navigation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await open(page)
  await page.locator('.report-evidence').first().locator('summary').click()
  await page.locator('.report-evidence').first().getByRole('button', { name: /해당 시간대 기록 보기/ }).first().click()
  await expect(page.locator('#report-record-4-CHAT_MESSAGE-1001')).toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('network failure halts reads until manual check; live and settling cannot generate', async ({ page }) => {
  await open(page)
  await page.clock.install()
  await scenario(page, 'network')
  await expect(page.getByRole('alert')).toContainText('보고서 상태를 조회하지 못했습니다')
  await page.clock.runFor(30000)
  await expect(page.getByRole('alert')).toBeVisible()
  await page.getByRole('button', { name: '상태 확인', exact: true }).click()
  await expect(page.getByText('보고서 완료', { exact: true })).toBeVisible()
  for (const value of ['live', 'settling']) {
    await scenario(page, value)
    await expect(page.getByRole('button', { name: '보고서 생성', exact: true })).toBeDisabled()
  }
})
