import { randomUUID } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { devices, expect, test } from '@playwright/test'

async function createAndStart(page, title) {
  const password = randomUUID()
  await page.goto('/signup')
  await page.getByLabel('이름', { exact: true }).fill('보고서 검증 강사')
  await page.getByLabel('이메일').fill(`report-${randomUUID()}@example.test`)
  await page.getByLabel('비밀번호', { exact: true }).fill(password)
  await page.getByLabel('비밀번호 확인').fill(password)
  await page.getByRole('button', { name: '회원가입', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.getByLabel('비밀번호', { exact: true }).fill(password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await page.getByLabel('강연 제목').fill(title)
  await page.getByRole('button', { name: '강연방 만들기' }).click()
  await expect(page).toHaveURL(/\/host\/rooms\/\d+$/)
  const roomPath = new URL(page.url()).pathname
  await page.getByRole('button', { name: '강연 시작', exact: true }).click()
  await expect(page.getByRole('button', { name: '강연 종료', exact: true })).toBeVisible()
  return roomPath
}

async function recordSpeech(api, roomId, transcript) {
  const csrf = await api.get('/api/v1/auth/csrf')
  expect(csrf.ok()).toBe(true)
  const { headerName, token } = (await csrf.json()).data
  const headers = { [headerName]: token }
  const startKey = randomUUID()
  const base = `/api/v1/rooms/${roomId}/stt/sessions`
  const started = await api.post(base, { headers, data: { startKey } })
  expect(started.status()).toBe(201)
  const { sttSessionId, serverTime } = (await started.json()).data
  const saved = await api.post(`${base}/${sttSessionId}/segments`, {
    headers, data: { startKey, segmentKey: randomUUID(), transcript, segmentStartAt: serverTime, segmentEndAt: serverTime },
  })
  expect(saved.status()).toBe(201)
  const stopped = await api.post(`${base}/${sttSessionId}/stop`, { headers, data: { reason: 'MANUAL_STOP' } })
  expect(stopped.ok()).toBe(true)
}

for (const [kind, options, transcript] of [
  ['PC', { viewport: { width: 1280, height: 900 } }, null],
  ['모바일', devices['Pixel 7'], '수수료 계산에는 실제로 사용한 가스의 양이 반영됩니다.'],
]) {
  test(`${kind}에서 종료한 강연의 자동 생성 보고서를 열고 새로고침한 뒤 강연방으로 돌아간다`, async ({ browser, baseURL }) => {
    test.setTimeout(60_000)
    const context = await browser.newContext({ ...options, baseURL })
    try {
      const page = await context.newPage()
      const title = `${kind} 보고서 통합 확인`
      const roomPath = await createAndStart(page, title)
      const id = roomPath.split('/').pop()
      const reportApi = `/api/v1/rooms/${id}/reports`
      const reportRequests = []
      page.on('request', request => {
        const path = new URL(request.url()).pathname
        if (path === reportApi || path === reportApi + '/status') {
          reportRequests.push(`${request.method()} ${path}`)
        }
      })
      if (transcript) {
        await recordSpeech(context.request, id, transcript)
        // 발언이 끝나고 2초 뒤 강연을 종료하는 시나리오로 두 시각을 구분한다.
        await delay(2_000)
      }
      await page.getByRole('button', { name: '강연 종료', exact: true }).click()
      await page.getByRole('dialog').getByRole('button', { name: '강연 종료', exact: true }).click()
      const open = page.getByRole('link', { name: '회고 보고서 보기', exact: true })
      await expect(open).toBeVisible({ timeout: 35_000 })
      await expect(page).toHaveURL(roomPath)
      expect(reportRequests.length).toBeGreaterThan(0)
      expect(reportRequests.every(entry => entry === `GET ${reportApi}/status`)).toBe(true)

      await open.click()
      const reportPath = roomPath + '/report'
      await expect(page).toHaveURL(reportPath)
      const heading = page.getByRole('heading', { name: title, exact: true })
      await expect(heading).toBeVisible()
      const followUp = page.getByRole('region', { name: '후속 답변 후보' })
      await expect(followUp.getByText('청중 기록에서 후속 검토할 질문을 찾지 못했습니다.')).toBeVisible()
      if (transcript) {
        const windowSummary = page.locator('.report-window summary').filter({ hasText: '실제 기록 1건' })
        await expect(windowSummary).toBeVisible()
        await windowSummary.click()
        await expect(page.getByText(transcript, { exact: true })).toBeVisible()
      } else {
        await page.locator('.report-global-limit summary').click()
        await expect(page.getByText('수집된 기록이 없어 요약할 수 없습니다.', { exact: true })).toBeVisible()
        await expect(page.getByText('이 항목에 표시할 요약이 없습니다.', { exact: true })).toHaveCount(3)
      }
      expect(reportRequests).toContain(`GET ${reportApi}`)
      await page.reload()
      await expect(heading).toBeVisible()
      const back = page.getByRole('link', { name: '강연방으로 돌아가기' })
      await expect(back).toHaveAttribute('href', roomPath)
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(options.viewport.width)
      await back.click()
      await expect(page).toHaveURL(roomPath)
      await expect(open).toBeVisible()

      if (options.isMobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
      await page.getByRole('button', { name: '내 강연 기록', exact: true }).click()
      const historyPath = '/host/rooms/history'
      await expect(page).toHaveURL(historyPath)
      await expect(page.getByText(title, { exact: true })).toBeVisible()
      const record = page.getByRole('link', { name: '강연 기록 열기' })
      await expect(record).toHaveAttribute('href', roomPath)
      await expect(open).toHaveAttribute('href', reportPath)
      await open.click()
      await expect(heading).toBeVisible()
      await page.goBack()
      await expect(page).toHaveURL(historyPath)
      await record.click()
      await expect(page).toHaveURL(roomPath)
      expect(reportRequests).not.toContain(`POST ${reportApi}`)
    } finally {
      await context.close()
    }
  })
}
