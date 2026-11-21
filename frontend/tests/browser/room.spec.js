import { test, expect } from '@playwright/test'

const cursorAfter = room => 'after/' + room.roomId + '+'

function readyPage(rooms, offset, limit) {
  const items = rooms.slice(offset, offset + limit)
  return { rooms: items, nextCursor: offset + items.length < rooms.length ? cursorAfter(items.at(-1)) : null }
}

async function setup(page) {
  const state = { user: 1, auth: true, create: 'ok', posts: [], roomCalls: 0, inviteCalls: 0, detailError: 0, inviteError: 0, status: 'READY',
    liveRooms: [], readyRooms: [], homeError: 0, homeLimits: [], pageError: 0, pageCursors: [], meCalls: 0 }
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname
    const ok = data => route.fulfill({ json: { data } })
    const fail = status => route.fulfill({ status, json: { code: status === 401 ? 'UNAUTHENTICATED' : 'ERROR' } })
    if (path.endsWith('/auth/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    if (path.endsWith('/auth/login')) { state.auth = true; return ok({}) }
    if (path.endsWith('/auth/me')) {
      state.meCalls++
      return state.auth ? ok({ userId: state.user, name: '테스트 강연자' }) : fail(401)
    }
    if (path === '/api/v1/rooms/home' && req.method() === 'GET') {
      const limit = Number(url.searchParams.get('readyLimit') ?? 3)
      state.homeLimits.push(limit)
      return state.homeError ? fail(state.homeError) : ok({ liveRooms: state.liveRooms, readyRooms: readyPage(state.readyRooms, 0, limit) })
    }
    if (path === '/api/v1/rooms/ready' && req.method() === 'GET') {
      const cursor = url.searchParams.get('cursor')
      state.pageCursors.push(cursor)
      const index = state.readyRooms.findIndex(room => cursorAfter(room) === cursor)
      if (index < 0) return fail(400)
      return state.pageError ? fail(state.pageError) : ok(readyPage(state.readyRooms, index + 1, 3))
    }
    if (path.endsWith('/ai/analyses')) return ok({ roomId: 10, analyses: [] })
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
    const detailRoom = state.readyRooms.find(room => path === '/api/v1/rooms/' + room.roomId)
    if (path === '/api/v1/rooms/10' || detailRoom) {
      state.roomCalls++
      if (state.detailError) return fail(state.detailError)
      return ok({ roomId: detailRoom?.roomId ?? 10, title: detailRoom?.title ?? 'Redis 기초 강연', description: '캐시와 만료 시간을 함께 알아봅니다.', status: state.status,
        createdAt: '2026-11-19T01:00:00Z', endedAt: state.status === 'ENDED' ? '2026-11-19T02:00:00Z' : null })
    }
    return fail(404)
  })
  return state
}

test.describe('home live rooms', () => {
  test.use({ timezoneId: 'UTC' })
  const morning = { roomId: 10, title: 'Redis 기초 강연', startedAt: '2026-11-20T22:15:00Z' }
  const afternoon = { roomId: 11, title: 'Redis 심화 강연', startedAt: '2026-11-21T05:00:00Z' }
  const workshops = Array.from({ length: 8 }, (_, i) => ({
    roomId: 27 - i, title: '준비한 워크숍 ' + (8 - i), createdAt: new Date(Date.UTC(2026, 10, 20, 22, 15 - i)).toISOString(),
  }))
  const readyList = page => page.getByRole('region', { name: '준비 중인 강연', exact: true })
  const openRooms = page => readyList(page).getByRole('link', { name: '강연방 열기', exact: true })
  const more = page => readyList(page).getByRole('button', { name: '더 보기', exact: true })
  const empty = '현재 진행 중인 강연이 없습니다'
  const returnLinks = page => page.getByRole('link', { name: /강연으로 돌아가기/ })
  const retry = page => page.getByRole('button', { name: '다시 불러오기' })

  async function setVisibility(page, visible) {
    await page.evaluate(value => {
      Object.defineProperties(document, {
        hidden: { configurable: true, value: !value },
        visibilityState: { configurable: true, value: value ? 'visible' : 'hidden' },
      })
      document.dispatchEvent(new Event('visibilitychange'))
    }, visible)
  }

  test('an empty initial list shows only the empty state above the creation form', async ({ page }) => {
    await setup(page)
    await page.goto('/host/rooms/new')
    const list = page.getByRole('region', { name: '진행 중인 내 강연' })
    await expect(list).toHaveText(empty)
    await expect(readyList(page)).toHaveCount(0)
    await expect(list.getByRole('alert')).toHaveCount(0)
    await expect(retry(list)).toHaveCount(0)
    await expect(page.getByLabel('강연 제목')).toBeEditable()
  })

  test('home shows all returned rooms in newest-first order with KST start times and opens the selected room in its current state', async ({ page }, testInfo) => {
    const state = await setup(page)
    state.liveRooms = [afternoon, morning]
    await page.goto('/')
    const list = page.getByRole('region', { name: '진행 중인 내 강연' })
    await expect(list.getByText(morning.title, { exact: true })).toBeVisible()
    await expect(list.getByText(afternoon.title, { exact: true })).toBeVisible()
    await expect(list.locator('time').nth(1)).toHaveText(/11월 21일.*07:15/)
    const links = returnLinks(list)
    await expect(links).toHaveCount(2)
    await expect(links.nth(0)).toHaveAttribute('href', '/host/rooms/11')
    await expect(links.nth(1)).toHaveAttribute('href', '/host/rooms/10')
    await list.screenshot({ path: testInfo.outputPath('live-rooms-desktop.png') })
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await list.screenshot({ path: testInfo.outputPath('live-rooms-mobile.png') })
    state.status = 'ENDED'
    await links.nth(1).click()
    await expect(page).toHaveURL(/\/host\/rooms\/10$/)
    await expect(page.getByText('종료된 강연입니다. 새로운 참여는 불가능합니다.')).toBeVisible()
  })

  test('one room stays on home; time and hiding cause no reads, returning to the tab refreshes the list', async ({ page }) => {
    const state = await setup(page)
    state.liveRooms = [morning]
    await page.clock.install()
    await page.goto('/host/rooms/new')
    await expect(returnLinks(page)).toHaveAttribute('href', '/host/rooms/10')
    await expect(page.getByLabel('강연 제목')).toBeVisible()
    const calls = state.homeLimits.length
    state.liveRooms = []
    await page.clock.fastForward(60000)
    await setVisibility(page, false)
    await page.clock.fastForward(60000)
    expect(state.homeLimits.length).toBe(calls)
    await setVisibility(page, true)
    await expect(page.getByText(empty, { exact: true })).toBeVisible()
    await expect(returnLinks(page)).toHaveCount(0)
    expect(state.homeLimits.length).toBe(calls + 1)
    await expect(page).toHaveURL(/\/host\/rooms\/new$/)
  })

  test('first load error retries just the list and preserves the draft', async ({ page }) => {
    const state = await setup(page)
    state.homeError = 503
    await page.goto('/host/rooms/new')
    await expect(page.getByRole('alert')).toHaveText(/강연방 목록을 불러오지 못했습니다/)
    await expect(readyList(page)).toHaveCount(0)
    await expect(page.getByText(empty, { exact: true })).toHaveCount(0)
    await page.getByLabel('강연 제목').fill('새 강연 준비')
    await page.getByLabel('강연 설명').fill('다음 발표에 사용할 내용')
    const meCalls = state.meCalls
    const homeCalls = state.homeLimits.length
    state.homeError = 0
    await retry(page).click()
    await expect(page.getByText(empty, { exact: true })).toBeVisible()
    await expect(retry(page)).toHaveCount(0)
    await expect(page.getByLabel('강연 제목')).toHaveValue('새 강연 준비')
    await expect(page.getByLabel('강연 설명')).toHaveValue('다음 발표에 사용할 내용')
    expect(state.homeLimits.length).toBe(homeCalls + 1)
    expect(state.meCalls).toBe(meCalls)
    expect(state.posts).toHaveLength(0)
  })

  test('home orders LIVE, READY and create on desktop and mobile, and opens the selected READY room', async ({ page }, testInfo) => {
    const state = await setup(page)
    state.liveRooms = [morning]
    state.readyRooms = workshops
    await page.goto('/host/rooms/new')
    const ready = readyList(page)
    const live = page.getByRole('region', { name: '진행 중인 내 강연' })
    const form = page.getByRole('form', { name: '새 강연방' })
    await expect(openRooms(page)).toHaveCount(3)
    await expect(ready.getByRole('listitem')).toHaveText(workshops.slice(0, 3).map(room => new RegExp(room.title)))
    await expect(ready.locator('time').first()).toHaveText(/11월 21일.*07:15.*생성/)
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 900 })
      const [liveBox, readyBox, formBox] = await Promise.all([live.boundingBox(), ready.boundingBox(), form.boundingBox()])
      expect(liveBox.y + liveBox.height).toBeLessThanOrEqual(readyBox.y + 1)
      expect(readyBox.y + readyBox.height).toBeLessThanOrEqual(formBox.y + 1)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath('ready-home-' + width + '.png'), fullPage: true })
    }
    expect(state.pageCursors).toEqual([])
    state.status = 'ENDED'
    await openRooms(page).nth(1).click()
    await expect(page).toHaveURL(/\/host\/rooms\/26$/)
    await expect(page.getByRole('heading', { name: workshops[1].title })).toBeVisible()
    await expect(page.getByText('종료된 강연입니다. 새로운 참여는 불가능합니다.')).toBeVisible()
    expect(state.posts).toEqual([])
  })

  test('a failed next page retries the same cursor without refreshing the two lists or resetting the draft', async ({ page }) => {
    const state = await setup(page)
    state.readyRooms = workshops
    await page.goto('/host/rooms/new')
    await expect(openRooms(page)).toHaveCount(3)
    const homeCalls = state.homeLimits.length
    await page.getByLabel('강연 제목').fill('작성 중인 새 강연')
    state.pageError = 503
    await more(page).click()
    const retryPage = readyList(page).getByRole('button', { name: '다시 시도', exact: true })
    await expect(retryPage).toBeVisible()
    await expect(openRooms(page)).toHaveCount(3)
    await expect(retry(page)).toHaveCount(0)
    state.pageError = 0
    await retryPage.click()
    await expect(openRooms(page)).toHaveCount(6)
    await more(page).click()
    await expect(openRooms(page)).toHaveCount(8)
    await expect(more(page)).toHaveCount(0)
    await expect(readyList(page).getByRole('listitem')).toHaveText(workshops.map(room => new RegExp(room.title)))
    expect(state.pageCursors).toEqual([cursorAfter(workshops[2]), cursorAfter(workshops[2]), cursorAfter(workshops[5])])
    expect(state.homeLimits).toHaveLength(homeCalls)
    await expect(page.getByLabel('강연 제목')).toHaveValue('작성 중인 새 강연')
  })

  test('tab return refreshes the six expanded rows and moves started rooms to LIVE; reload and re-entry start with three', async ({ page }) => {
    const state = await setup(page)
    state.readyRooms = workshops
    await page.goto('/host/rooms/new')
    await more(page).click()
    await expect(openRooms(page)).toHaveCount(6)
    const [started, ...remaining] = workshops
    await setVisibility(page, false)
    state.readyRooms = remaining
    state.liveRooms = [{ ...started, startedAt: morning.startedAt }]
    await setVisibility(page, true)
    const live = page.getByRole('region', { name: '진행 중인 내 강연' })
    await expect(live.getByText(started.title, { exact: true })).toBeVisible()
    await expect(readyList(page).getByRole('listitem')).toHaveText(remaining.slice(0, 6).map(room => new RegExp(room.title)))
    await expect(readyList(page).getByText(started.title)).toHaveCount(0)
    expect(state.homeLimits.at(-1)).toBe(6)
    expect(state.pageCursors).toHaveLength(1)
    await page.reload()
    await expect(openRooms(page)).toHaveCount(3)
    expect(state.homeLimits.at(-1)).toBe(3)
    await more(page).click()
    await expect(openRooms(page)).toHaveCount(6)
    await openRooms(page).first().click()
    await expect(page.getByRole('heading', { name: remaining[0].title })).toBeVisible()
    await page.getByRole('button', { name: '홈', exact: true }).click()
    await expect(openRooms(page)).toHaveCount(3)
    expect(state.homeLimits.at(-1)).toBe(3)
  })

  test('a late additional page cannot overwrite the list and cursor obtained on tab return', async ({ page }) => {
    const state = await setup(page)
    state.readyRooms = workshops
    await page.goto('/host/rooms/new')
    let receiveRoute
    const pendingPage = new Promise(resolve => { receiveRoute = resolve })
    const isNextPage = url => url.pathname === '/api/v1/rooms/ready'
    await page.route(isNextPage, route => receiveRoute(route), { times: 1 })
    await more(page).click()
    const oldRequest = await pendingPage
    expect(new URL(oldRequest.request().url()).searchParams.get('cursor')).toBe(cursorAfter(workshops[2]))

    await setVisibility(page, false)
    state.readyRooms = workshops.slice(4)
    await setVisibility(page, true)
    await expect(readyList(page).getByRole('listitem')).toHaveText(state.readyRooms.slice(0, 3).map(room => new RegExp(room.title)))
    const received = page.waitForEvent('requestfinished', request => request === oldRequest.request())
    await oldRequest.fulfill({ json: { data: readyPage(workshops, 3, 3) } })
    await received
    await more(page).click()
    await expect(readyList(page).getByRole('listitem')).toHaveText(state.readyRooms.map(room => new RegExp(room.title)))
    await expect(more(page)).toHaveCount(0)
    expect(state.pageCursors).toEqual([cursorAfter(workshops[6])])
  })

  test('a forbidden additional page removes both cached lists', async ({ page }) => {
    const state = await setup(page)
    state.liveRooms = [morning]
    state.readyRooms = workshops
    await page.goto('/host/rooms/new')
    await expect(openRooms(page)).toHaveCount(3)
    state.pageError = 403
    await more(page).click()
    await expect(page.getByRole('alert')).toContainText('권한')
    await expect(returnLinks(page)).toHaveCount(0)
    await expect(readyList(page)).toHaveCount(0)
    await expect(page.getByText(morning.title)).toHaveCount(0)
  })

  for (const previous of ['populated', 'empty']) test(`failed refresh keeps the last ${previous} result and draft until recovery`, async ({ page }) => {
    const state = await setup(page)
    state.liveRooms = previous === 'populated' ? [morning] : []
    state.readyRooms = previous === 'populated' ? workshops : []
    await page.goto('/host/rooms/new')
    const lastResult = page.getByText(previous === 'populated' ? morning.title : empty, { exact: true })
    await expect(lastResult).toBeVisible()
    if (previous === 'populated') {
      await more(page).click()
      await expect(openRooms(page)).toHaveCount(6)
    }
    await page.getByLabel('강연 제목').fill('작성 중인 제목')
    await page.getByLabel('강연 설명').fill('작성 중인 설명')
    state.homeError = 503
    await setVisibility(page, false)
    await setVisibility(page, true)
    if (previous === 'populated') {
      await expect(page.getByRole('alert')).toHaveText(/마지막으로 확인한 목록이에요/)
      await expect(lastResult).toBeVisible()
      await expect(returnLinks(page)).toHaveAttribute('href', '/host/rooms/10')
      await expect(openRooms(page)).toHaveCount(6)
    } else {
      await expect(page.getByRole('alert')).toHaveText(/목록을 갱신하지 못했어요. 마지막 조회 때는 진행 중인 강연이 없었습니다/)
      await expect(lastResult).toHaveCount(0)
    }
    state.homeError = 0
    state.liveRooms = [afternoon]
    const homeCalls = state.homeLimits.length
    const additionalCalls = state.pageCursors.length
    await retry(page).click()
    await expect(page.getByText(afternoon.title, { exact: true })).toBeVisible()
    await expect(lastResult).toHaveCount(0)
    await expect(retry(page)).toHaveCount(0)
    expect(state.homeLimits.length).toBe(homeCalls + 1)
    expect(state.homeLimits.at(-1)).toBe(previous === 'populated' ? 6 : 3)
    expect(state.pageCursors).toHaveLength(additionalCalls)
    await expect(page.getByLabel('강연 제목')).toHaveValue('작성 중인 제목')
    await expect(page.getByLabel('강연 설명')).toHaveValue('작성 중인 설명')
    expect(state.posts).toHaveLength(0)
  })
})

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
  await expect(page.getByLabel('참여 링크')).toHaveCount(0)
  await page.getByRole('button', { name: '청중 초대', exact: true }).click()
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
  await page.getByRole('button', { name: '청중 초대', exact: true }).click()
  await expect(page.getByText('참여 정보를 불러오지 못했습니다.')).toBeVisible()
  state.inviteError = 0
  await page.getByRole('button', { name: '다시 불러오기' }).click()
  await expect(page.getByLabel('참여 링크')).toBeVisible()
  expect(state.posts).toHaveLength(0)
})

test('ended state closes enlarged QR, hides invitation and stops polling', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/host/rooms/10')
  await page.getByRole('button', { name: '청중 초대', exact: true }).click()
  await page.getByRole('button', { name: 'QR 크게 보기' }).click()
  await expect(page.getByRole('dialog', { name: 'QR코드로 강연에 참여하세요' })).toBeVisible()
  state.status = 'ENDED'
  await expect(page.getByText('종료된 강연입니다. 새로운 참여는 불가능합니다.')).toBeVisible({ timeout: 8000 })
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '청중 초대', exact: true })).toHaveCount(0)
  await expect(page.getByLabel('참여 링크')).toHaveCount(0)
  const count = state.roomCalls
  await page.waitForTimeout(5200)
  expect(state.roomCalls).toBe(count)
})

for (const status of [403, 404, 500]) test(`detail refresh failure ${status} removes QR and treats room appropriately`, async ({ page }) => {
  const state = await setup(page)
  await page.goto('/host/rooms/10')
  await page.getByRole('button', { name: '청중 초대', exact: true }).click()
  await expect(page.getByLabel('참여 링크')).toBeVisible()
  state.detailError = status
  await expect(page.getByLabel('참여 링크')).toHaveCount(0, { timeout: 8000 })
  if (status === 500) {
    await expect(page.getByRole('heading', { name: 'Redis 기초 강연' })).toBeVisible()
    state.detailError = 0
    await page.getByRole('button', { name: '다시 확인' }).click()
    await page.getByRole('button', { name: '청중 초대', exact: true }).click()
    await expect(page.getByLabel('참여 링크')).toBeVisible()
  } else await expect(page.getByRole('heading', { name: 'Redis 기초 강연' })).toHaveCount(0)
})

test('invitation authorization failure removes room information', async ({ page }) => {
  const state = await setup(page); state.inviteError = 403
  await page.goto('/host/rooms/10')
  await expect(page.getByText('이 강연방을 볼 권한이 없습니다.')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Redis 기초 강연' })).toHaveCount(0)
})

test('mobile menu opens invite sheet; QR and sheet dismiss separately and restore focus', async ({ page }, testInfo) => {
  await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/host/rooms/10')
  await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('button', { name: '청중 초대', exact: true }).click()
  const sheet = page.getByRole('dialog', { name: '청중 초대', exact: true })
  await expect(sheet).toBeVisible()
  await expect(page.getByRole('button', { name: '메뉴 닫기' })).not.toBeVisible()
  await expect(sheet.getByText('ABC123', { exact: true })).toBeVisible()
  await expect.poll(() => sheet.evaluate(el => Math.abs(el.getBoundingClientRect().bottom - innerHeight))).toBeLessThan(2)
  await page.screenshot({ path: testInfo.outputPath('invitation-mobile.png') })
  await page.getByRole('button', { name: 'QR 크게 보기' }).click()
  await expect(page.getByRole('img', { name: '확대된 청중 참여 QR코드' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(sheet.getByRole('button', { name: 'QR 크게 보기' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(sheet).not.toBeVisible()
  await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeFocused()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('room-mobile.png'), fullPage: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: testInfo.outputPath('room-desktop.png'), fullPage: true })
})

test('desktop invitation opens beside the menu without moving the room, supports copying and outside dismissal', async ({ page, context }, testInfo) => {
  await setup(page)
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.goto('/host/rooms/10')
  const title = page.getByRole('heading', { name: 'Redis 기초 강연' })
  const trigger = page.getByRole('button', { name: '청중 초대', exact: true })
  await expect(title).toBeVisible()
  await page.evaluate(() => document.fonts.ready.then(() => true))
  const before = await title.boundingBox()
  await trigger.click()
  const panel = page.getByRole('dialog', { name: '청중 초대', exact: true })
  await expect(panel.getByText('ABC123', { exact: true })).toBeVisible()
  expect(await panel.evaluate(el => el.matches(':modal'))).toBe(false)
  expect(await title.boundingBox()).toEqual(before)
  await panel.getByRole('button', { name: '링크 복사' }).click()
  await expect(panel.getByText('참여 링크를 복사했습니다.')).toBeVisible()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('https://react.example/join/ABC123')
  await page.screenshot({ path: testInfo.outputPath('invitation-desktop.png') })
  await page.getByRole('button', { name: '로그아웃' }).focus()
  await page.keyboard.press('Escape')
  await expect(panel).toHaveCount(0)
  await trigger.click()
  // The far right of the viewport is outside both the rail and the popup.
  await page.mouse.click((page.viewportSize()).width - 12, 80)
  await expect(panel).toHaveCount(0)
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
  await page.getByRole('button', { name: '청중 초대', exact: true }).click()
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
