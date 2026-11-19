import { test, expect } from '@playwright/test'

async function setup(page) {
  const state = { user: 1, auth: true, create: 'ok', posts: [], roomCalls: 0, inviteCalls: 0, detailError: 0, inviteError: 0, status: 'READY' }
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    const ok = data => route.fulfill({ json: { data } })
    const fail = status => route.fulfill({ status, json: { code: status === 401 ? 'UNAUTHENTICATED' : 'ERROR' } })
    if (path.endsWith('/auth/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    if (path.endsWith('/auth/login')) { state.auth = true; return ok({}) }
    if (path.endsWith('/auth/me')) return state.auth ? ok({ userId: state.user, name: '테스트 강연자' }) : fail(401)
    if (path === '/api/v1/rooms' && req.method() === 'POST') {
      expect(req.headers()['x-csrf-token']).toBe('token')
      state.posts.push(req.postDataJSON())
      if (state.create === 'lost') return route.abort()
      if (state.create === 'expired') { state.auth = false; return fail(401) }
      return ok({ roomId: 10 })
    }
    if (path.endsWith('/invitation')) {
      state.inviteCalls++
      if (state.inviteError) return fail(state.inviteError)
      return ok({ joinCode: 'ABC123', joinUrl: 'https://react.example/join/ABC123', qrText: 'https://react.example/join/ABC123' })
    }
    if (path === '/api/v1/rooms/10') {
      state.roomCalls++
      if (state.detailError) return fail(state.detailError)
      return ok({ roomId: 10, title: 'Redis 기초 강연', description: '캐시와 만료 시간을 함께 알아봅니다.', status: state.status,
        createdAt: '2026-11-19T01:00:00Z', endedAt: state.status === 'ENDED' ? '2026-11-19T02:00:00Z' : null })
    }
    return fail(404)
  })
  return state
}

test('create validates title and submits once with CSRF then replaces page with detail', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/host/rooms/new')
  await page.getByLabel('강연 제목').fill('   ')
  await page.getByRole('button', { name: '강연방 만들기' }).click()
  await expect(page.getByText('강연 제목을 입력해 주세요.')).toBeVisible()
  expect(state.posts).toHaveLength(0)
  await page.getByLabel('강연 제목').fill(' Redis 기초 강연 ')
  await page.getByRole('button', { name: '강연방 만들기' }).dblclick()
  await expect(page).toHaveURL(/\/host\/rooms\/10$/)
  expect(state.posts).toEqual([{ title: 'Redis 기초 강연', description: '' }])
  await expect(page.getByLabel('참여 링크')).toHaveValue('https://react.example/join/ABC123')
  expect(await page.evaluate(() => sessionStorage.getItem('react:room-draft:v1'))).toBeNull()
})

test('lost response and draft survive reload without automatic resubmit', async ({ page }) => {
  const state = await setup(page); state.create = 'lost'
  await page.goto('/host/rooms/new')
  await page.getByLabel('강연 제목').fill('복원할 제목')
  await page.getByLabel('강연 설명').fill('복원할 설명')
  await page.getByRole('button', { name: '강연방 만들기' }).click()
  await expect(page.getByText('생성 여부를 확인하지 못했습니다.')).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('강연 제목')).toHaveValue('복원할 제목')
  await expect(page.getByText('생성 여부를 확인하지 못했습니다.')).toBeVisible()
  expect(state.posts).toHaveLength(1)
  page.once('dialog', dialog => dialog.dismiss())
  await page.getByRole('button', { name: '강연방 만들기' }).click()
  expect(state.posts).toHaveLength(1)
  state.create = 'ok'
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '강연방 만들기' }).click()
  await expect(page).toHaveURL(/\/host\/rooms\/10$/)
  expect(state.posts).toHaveLength(2)
})

for (const sameAccount of [true, false]) test(`login return restores draft only for same account: ${sameAccount}`, async ({ page }) => {
  const state = await setup(page); state.create = 'expired'
  await page.goto('/host/rooms/new')
  await page.getByLabel('강연 제목').fill('내 초안')
  await page.getByRole('button', { name: '강연방 만들기' }).click()
  await expect(page.getByLabel('이메일')).toBeVisible()
  if (!sameAccount) state.user = 2
  await page.getByLabel('이메일').fill('host@example.test')
  await page.getByLabel('비밀번호').fill('password')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/host\/rooms\/new$/)
  await expect(page.getByLabel('강연 제목')).toHaveValue(sameAccount ? '내 초안' : '')
  expect(state.posts).toHaveLength(1)
})

test('invitation-only failure retries invitation while retaining room', async ({ page }) => {
  const state = await setup(page); state.inviteError = 500
  await page.goto('/host/rooms/10')
  await expect(page.getByRole('heading', { name: 'Redis 기초 강연' })).toBeVisible()
  await expect(page.getByText('참여 정보를 불러오지 못했습니다.')).toBeVisible()
  state.inviteError = 0
  await page.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(page.getByLabel('참여 링크')).toBeVisible()
  expect(state.posts).toHaveLength(0)
})

test('ended state closes enlarged QR, hides invitation and stops polling', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/host/rooms/10')
  await page.getByRole('button', { name: 'QR 크게 보기' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  state.status = 'ENDED'
  await expect(page.getByText('종료된 강연입니다. 새로운 참여는 불가능합니다.')).toBeVisible({ timeout: 8000 })
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByLabel('참여 링크')).toHaveCount(0)
  const count = state.roomCalls
  await page.waitForTimeout(5200)
  expect(state.roomCalls).toBe(count)
})

for (const status of [403, 404, 500]) test(`detail refresh failure ${status} removes QR and treats room appropriately`, async ({ page }) => {
  const state = await setup(page)
  await page.goto('/host/rooms/10')
  await expect(page.getByLabel('참여 링크')).toBeVisible()
  state.detailError = status
  await expect(page.getByLabel('참여 링크')).toHaveCount(0, { timeout: 8000 })
  if (status === 500) {
    await expect(page.getByRole('heading', { name: 'Redis 기초 강연' })).toBeVisible()
    state.detailError = 0
    await page.getByRole('button', { name: '다시 확인' }).click()
    await expect(page.getByLabel('참여 링크')).toBeVisible()
  } else await expect(page.getByRole('heading', { name: 'Redis 기초 강연' })).toHaveCount(0)
})

test('invitation authorization failure removes room information', async ({ page }) => {
  const state = await setup(page); state.inviteError = 403
  await page.goto('/host/rooms/10')
  await expect(page.getByText('이 강연방을 볼 권한이 없습니다.')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Redis 기초 강연' })).toHaveCount(0)
})

test('mobile layout and accessible QR modal', async ({ page }, testInfo) => {
  await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/host/rooms/10')
  await page.getByRole('button', { name: 'QR 크게 보기' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).not.toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('room-mobile.png'), fullPage: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: testInfo.outputPath('room-desktop.png'), fullPage: true })
})

test('returnTo never redirects to external destinations', async ({ page }) => {
  await setup(page)
  await page.goto('/?returnTo=https://example.com')
  await expect(page).toHaveURL(/\/host\/rooms\/new$/)
  await expect(page.getByLabel('강연 제목')).toBeVisible()
})

test('hidden tab pauses polling and returning triggers an immediate read', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/host/rooms/10')
  await expect(page.getByLabel('참여 링크')).toBeVisible()
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  const count = state.roomCalls
  await page.waitForTimeout(5200)
  expect(state.roomCalls).toBe(count)
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect.poll(() => state.roomCalls).toBe(count + 1)
})

test('draft reset requires confirmation and removes restored uncertainty', async ({ page }, testInfo) => {
  await setup(page)
  await page.goto('/host/rooms/new')
  await page.getByLabel('강연 제목').fill('Redis 기초 강연')
  await page.getByLabel('강연 설명').fill('캐시와 만료 시간을 함께 알아봅니다.')
  await page.screenshot({ path: testInfo.outputPath('room-create.png'), fullPage: true })
  page.once('dialog', dialog => dialog.dismiss())
  await page.getByRole('button', { name: '입력 초기화' }).click()
  await expect(page.getByLabel('강연 제목')).toHaveValue('Redis 기초 강연')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '입력 초기화' }).click()
  await page.reload()
  await expect(page.getByLabel('강연 제목')).toHaveValue('')
  await expect(page.getByLabel('강연 설명')).toHaveValue('')
})
