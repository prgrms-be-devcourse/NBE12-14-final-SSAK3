import { devices, expect, test } from '@playwright/test'
import { setupHostRoom } from './host-room-fixture.js'

const historyUrl = '/host/rooms/history'
const apiPath = '/api/v1/rooms/history'
const serverEpoch = Date.parse('2026-11-23T03:00:00Z')
const title = id => `지난 강연 ${id}회`

function historyRoom(id, reportStatus = 'COMPLETED') {
  return { roomId: id, title: title(id), endedAt: new Date(serverEpoch - (90 - id) * 60_000).toISOString(), reportStatus }
}

async function setup(context, rooms) {
  await setupHostRoom(context, 'ENDED')
  const state = { rooms, now: serverEpoch, pageCalls: [], statusCalls: [], latestCalls: 0, pageError: false, statusError: false }
  await context.route(url => url.pathname === apiPath || url.pathname.startsWith(apiPath + '/'), async route => {
    const url = new URL(route.request().url())
    const ok = data => route.fulfill({ json: { data } })
    const fail = () => route.fulfill({ status: 503, json: { code: 'UNAVAILABLE' } })
    const serverTime = new Date(state.now).toISOString()
    expect(route.request().method()).toBe('GET')
    if (url.pathname === apiPath + '/latest') {
      state.latestCalls++
      return ok({ roomId: state.rooms[0]?.roomId ?? null, totalCount: state.rooms.length })
    }
    if (url.pathname === apiPath + '/statuses') {
      const ids = url.searchParams.get('roomIds').split(',').map(Number)
      state.statusCalls.push(ids)
      if (state.statusError) return fail()
      return ok({ rooms: state.rooms.filter(room => ids.includes(room.roomId))
        .map(({ roomId, reportStatus, reportUnconfirmed }) => ({ roomId, reportStatus, reportUnconfirmed })), serverTime })
    }
    const cursor = url.searchParams.get('cursor')
    state.pageCalls.push(cursor)
    if (state.pageError) return fail()
    const offset = cursor === null ? 0 : state.rooms.findIndex(room => 'after/' + room.roomId === cursor) + 1
    if (cursor !== null && !offset) return route.fulfill({ status: 400 })
    const items = state.rooms.slice(offset, offset + 10)
    return ok({ rooms: items, serverTime, totalCount: state.rooms.length,
      nextCursor: offset + items.length < state.rooms.length ? 'after/' + items.at(-1).roomId : null })
  })
  return state
}

const records = page => page.getByRole('link', { name: '강연 기록 열기', exact: true })
const row = (page, id) => page.getByRole('listitem').filter({ hasText: title(id) })
const more = page => page.getByRole('button', { name: '더 보기', exact: true })

async function menuHistory(page, mobile) {
  if (mobile) await page.getByRole('button', { name: '메뉴 열기' }).click()
  await page.getByRole('button', { name: '내 강연 기록', exact: true }).click()
  await expect(page).toHaveURL(historyUrl)
}

async function visibility(page, visible) {
  await page.evaluate(value => {
    Object.defineProperties(document, {
      hidden: { configurable: true, value: !value },
      visibilityState: { configurable: true, value: value ? 'visible' : 'hidden' },
    })
    document.dispatchEvent(new Event('visibilitychange'))
  }, visible)
}

for (const mobile of [false, true]) {
  test.describe(mobile ? '모바일 강연 기록' : 'PC 강연 기록', () => {
    const pixel = devices['Pixel 7']
    test.use(mobile ? { viewport: pixel.viewport, isMobile: true, hasTouch: true, deviceScaleFactor: pixel.deviceScaleFactor }
      : { viewport: { width: 1280, height: 900 } })

    test('메뉴에서 들어가면 10개씩 이어서 읽고 완료된 보고서만 바로 열 수 있다', async ({ page, context }) => {
      const rooms = Array.from({ length: 23 }, (_, i) => historyRoom(23 - i))
      rooms[0].reportStatus = null
      rooms[1].reportStatus = 'FAILED'
      const state = await setup(context, rooms)
      await page.goto('/host/rooms/new')
      await menuHistory(page, mobile)
      await expect(records(page)).toHaveCount(10)
      await expect(row(page, 23)).toContainText('보고서 접수 확인 필요')
      await expect(row(page, 22)).toContainText('보고서 생성 실패')
      await expect(page.getByRole('link', { name: '회고 보고서 보기' })).toHaveCount(8)
      await expect(row(page, 21).getByRole('link', { name: '회고 보고서 보기' })).toHaveAttribute('href', '/host/rooms/21/report')
      await expect(page.getByRole('button', { name: '보고서 만들기' })).toHaveCount(0)
      await more(page).click()
      await expect(records(page)).toHaveCount(20)
      await more(page).click()
      await expect(records(page)).toHaveCount(23)
      await expect(more(page)).toHaveCount(0)
      expect(state.pageCalls).toEqual([null, 'after/14', 'after/4'])
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    })

    test('미접수 방을 열어 접수한 뒤 뒤로 가면 보던 강연과 위치를 복원하고 그 방만 다시 확인한다', async ({ page, context }) => {
      const rooms = Array.from({ length: 30 }, (_, i) => historyRoom(33 - i))
      const chosen = rooms.find(room => room.roomId === 10)
      chosen.reportStatus = null
      const state = await setup(context, rooms)
      await context.route('**/api/v1/rooms/10/reports{,/status}', route => {
        if (route.request().method() === 'POST') {
          chosen.reportStatus = 'PENDING'
          return route.fulfill({ status: 202, json: { data: { reportId: 91, roomId: 10, status: 'PENDING' } } })
        }
        if (!chosen.reportStatus) return route.fulfill({ status: 404, json: { code: 'REPORT_NOT_FOUND', timestamp: new Date(state.now).toISOString() } })
        return route.fulfill({ json: { data: { roomId: 10, status: chosen.reportStatus, content: {
          overview: { mainInterests: [], reviewPoints: [], suggestions: [] }, timeline: [], dataLimitations: [], evidencePolicy: '',
        } } } })
      })
      await page.goto(historyUrl)
      await more(page).click()
      await expect(records(page)).toHaveCount(20)
      await more(page).click()
      await expect(records(page)).toHaveCount(30)
      const read = row(page, 10).getByRole('link', { name: '강연 기록 열기' })
      await read.evaluate(el => el.scrollIntoView({ block: 'center' }))
      const before = await page.evaluate(() => window.scrollY)
      expect(before).toBeGreaterThan(100)
      await read.click()
      await page.getByRole('button', { name: '보고서 만들기', exact: true }).click()
      await expect(page.getByText('강연 기록을 정리하고 있어요.', { exact: true })).toBeVisible()
      chosen.reportStatus = 'COMPLETED'
      await page.goBack()
      await expect(records(page)).toHaveCount(30)
      const reportLink = row(page, 10).getByRole('link', { name: '회고 보고서 보기' })
      await expect(reportLink).toBeVisible()
      await expect.poll(() => page.evaluate(y => Math.abs(window.scrollY - y), before)).toBeLessThan(8)
      expect(state.statusCalls).toEqual([[10]])
      expect(state.pageCalls).toHaveLength(3)
      await reportLink.click()
      await expect(page).toHaveURL('/host/rooms/10/report')
      await expect(page.getByRole('link', { name: '강연방으로 돌아가기' })).toBeVisible()
      await page.goBack()
      await expect(records(page)).toHaveCount(30)
      await expect.poll(() => state.statusCalls.length).toBe(2)
      expect(state.statusCalls[1]).toEqual([10])
      await menuHistory(page, mobile)
      await expect(records(page)).toHaveCount(10)
      await more(page).click()
      await expect(records(page)).toHaveCount(20)
      await page.reload()
      await expect(records(page)).toHaveCount(10)
    })
  })
}

test('첫 조회가 실패하면 빈 안내와 구분하고, 다음 페이지가 실패하면 같은 커서만 다시 요청한다', async ({ page, context }) => {
  const state = await setup(context, [])
  state.pageError = true
  await page.goto(historyUrl)
  const retry = page.getByRole('button', { name: '다시 불러오기', exact: true })
  await expect(retry).toBeVisible()
  await expect(page.getByText(/아직 종료한 강연이 없어요/)).toHaveCount(0)
  state.pageError = false
  await retry.click()
  await expect(page.getByText(/아직 종료한 강연이 없어요/)).toBeVisible()
  expect(state.pageCalls).toEqual([null, null])
  state.rooms = Array.from({ length: 13 }, (_, i) => historyRoom(13 - i))
  await page.reload()
  await expect(records(page)).toHaveCount(10)
  state.pageError = true
  await more(page).click()
  const retryMore = page.getByRole('button', { name: '다시 시도', exact: true })
  await expect(retryMore).toBeVisible()
  await expect(records(page)).toHaveCount(10)
  state.pageError = false
  await retryMore.click()
  await expect(records(page)).toHaveCount(13)
  expect(state.pageCalls.slice(2)).toEqual([null, 'after/4', 'after/4'])
})

test('5초 갱신은 진행 중인 보고서만 조회하고, 실패 안내를 한 번 표시한 뒤 탭 복귀 시 즉시 복구한다', async ({ page, context }) => {
  const rooms = [historyRoom(33, 'PENDING'), historyRoom(32, 'PROCESSING'), historyRoom(31, 'FAILED'), historyRoom(30)]
  const state = await setup(context, rooms)
  await page.clock.install({ time: new Date(serverEpoch) })
  await page.clock.pauseAt(new Date(serverEpoch + 1000))
  await page.goto(historyUrl)
  await expect(row(page, 33)).toContainText('보고서 생성 대기 중')
  await expect(row(page, 32)).toContainText('보고서 생성 중')
  state.statusError = true
  await page.clock.fastForward(5000)
  const warning = page.getByText('보고서 상태를 갱신하지 못했어요.', { exact: true })
  await expect(warning).toBeVisible()
  await expect(records(page)).toHaveCount(4)
  await expect(row(page, 33)).toContainText('보고서 생성 대기 중')
  expect(state.statusCalls[0].toSorted()).toEqual([32, 33])
  await page.clock.fastForward(5000)
  await expect.poll(() => state.statusCalls.length).toBe(2)
  await expect(warning).toHaveCount(1)

  state.statusError = false
  rooms[0].reportStatus = 'COMPLETED'
  rooms[1].reportStatus = 'PROCESSING'
  await page.clock.fastForward(5010)
  await expect(row(page, 33).getByRole('link', { name: '회고 보고서 보기' })).toBeVisible()
  await expect(warning).toHaveCount(0)
  await visibility(page, false)
  const requestsBeforeHide = state.statusCalls.length
  rooms[1].reportStatus = 'COMPLETED'
  await page.clock.fastForward(15000)
  expect(state.statusCalls).toHaveLength(requestsBeforeHide)
  expect(state.latestCalls).toBe(0)
  await visibility(page, true)
  await expect(row(page, 32).getByRole('link', { name: '회고 보고서 보기' })).toBeVisible()
  await expect.poll(() => state.latestCalls).toBe(1)
  expect(state.statusCalls.slice(requestsBeforeHide)).toEqual([[32]])
  await page.clock.fastForward(15000)
  expect(state.statusCalls).toHaveLength(requestsBeforeHide + 1)
})

test('내 강연 기록에서도 결과 확인 중인 보고서만 계속 조회한다', async ({ page, context }) => {
  const uncertain = { ...historyRoom(33, 'FAILED'), reportUnconfirmed: true }
  const state = await setup(context, [uncertain, historyRoom(32, 'FAILED')])
  await page.clock.install()
  await page.goto(historyUrl)
  await expect(row(page, 33)).toContainText('보고서 결과 확인 중')
  await expect(row(page, 32)).toContainText('보고서 생성 실패')
  uncertain.reportStatus = 'COMPLETED'
  delete uncertain.reportUnconfirmed
  await page.clock.fastForward(5100)
  await expect(row(page, 33).getByRole('link', { name: '회고 보고서 보기' })).toBeVisible()
  await page.clock.fastForward(10000)
  expect(state.statusCalls).toEqual([[33]])
})

test('미접수 재확인은 서버 종료 시각에서 남은 시간만 기다리고 해당 방만 한 번 조회한다', async ({ page, context }) => {
  const rooms = [historyRoom(33, null), historyRoom(32, null), historyRoom(31, null)]
  rooms[0].endedAt = new Date(serverEpoch - 500).toISOString()
  rooms[1].endedAt = new Date(serverEpoch - 1500).toISOString()
  const state = await setup(context, rooms)
  // 강사의 기기 시각이 하루 앞서도 서버에 기록된 종료 시각이 기준이다.
  await page.clock.install({ time: new Date(serverEpoch + 86400000) })
  await page.clock.pauseAt(new Date(serverEpoch + 86401000))
  await page.goto(historyUrl)
  await expect(row(page, 33)).toContainText('보고서 접수 확인 중')
  await expect(row(page, 32)).toContainText('보고서 접수 확인 중')
  await expect(row(page, 31)).toContainText('보고서 접수 확인 필요')
  await page.clock.fastForward(499)
  expect(state.statusCalls).toEqual([])
  state.now = serverEpoch + 500
  await page.clock.fastForward(1)
  await expect(row(page, 32)).toContainText('보고서 접수 확인 필요')
  expect(state.statusCalls).toEqual([[32]])
  await expect(row(page, 33)).toContainText('보고서 접수 확인 중')
  rooms[0].reportStatus = 'PENDING'
  state.now = serverEpoch + 1500
  await page.clock.fastForward(1000)
  await expect(row(page, 33)).toContainText('보고서 생성 대기 중')
  expect(state.statusCalls).toEqual([[32], [33]])
  rooms[0].reportStatus = 'COMPLETED'
  await page.clock.fastForward(5001)
  await expect(row(page, 33).getByRole('link', { name: '회고 보고서 보기' })).toBeVisible()
  expect(state.statusCalls).toEqual([[32], [33], [33]])
})

test('탭 복귀 시 새 기록은 안내만 하고 눌렀을 때 최신 10개로 돌아간다', async ({ page, context }) => {
  const rooms = Array.from({ length: 25 }, (_, i) => historyRoom(33 - i))
  const state = await setup(context, rooms)
  await page.goto(historyUrl)
  await more(page).click()
  await expect(records(page)).toHaveCount(20)
  await row(page, 16).evaluate(el => el.scrollIntoView({ block: 'center' }))
  const readingTop = await row(page, 16).evaluate(el => el.getBoundingClientRect().top)
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(100)
  await visibility(page, false)
  // 새 강연이 같은 초에 종료되어 기존 첫 항목의 ID는 그대로다.
  const newRoom = { ...historyRoom(2), endedAt: rooms[0].endedAt }
  rooms.splice(1, 0, newRoom)
  await visibility(page, true)
  const showNew = page.getByRole('button', { name: '새 강연 기록 보기', exact: true })
  await expect(showNew).toBeVisible()
  await expect(row(page, 2)).toHaveCount(0)
  await expect(records(page)).toHaveCount(20)
  await expect.poll(() => row(page, 16).evaluate((el, before) => Math.abs(el.getBoundingClientRect().top - before), readingTop)).toBeLessThan(8)
  expect(state.pageCalls).toHaveLength(2)
  expect(state.latestCalls).toBe(1)
  await showNew.click()
  await expect(row(page, 2)).toBeVisible()
  await expect(records(page)).toHaveCount(10)
  await expect(showNew).toHaveCount(0)
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeLessThan(8)
  expect(state.pageCalls.at(-1)).toBeNull()
})
