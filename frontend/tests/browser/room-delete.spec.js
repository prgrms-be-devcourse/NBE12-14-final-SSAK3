import { test, expect } from '@playwright/test'

const rooms = Array.from({ length: 6 }, (_, index) => ({
  roomId: 20 - index, title: '준비 강연 ' + (20 - index),
  createdAt: new Date(Date.UTC(2026, 10, 20, 22, 15 - index)).toISOString(),
}))
const target = rooms[0]
const readyList = page => page.getByRole('region', { name: '준비 중인 강연', exact: true })

async function setup(page) {
  const state = { deletes: 0, reads: 0, homeLimits: [], homeStatus: 200, detail: 'READY', result: 204, live: [] }
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), url = new URL(req.url())
    const ok = data => route.fulfill({ json: { data } })
    const fail = (status, code) => route.fulfill({ status, json: { code } })
    switch (url.pathname) {
      case '/api/v1/auth/me': return ok({ userId: 9, name: '강연자' })
      case '/api/v1/auth/csrf': return ok({ token: 'test', headerName: 'X-CSRF-TOKEN' })
      case '/api/v1/rooms/home': {
        const limit = Number(url.searchParams.get('readyLimit'))
        state.homeLimits.push(limit)
        if (state.homeStatus !== 200) return fail(state.homeStatus, 'ERROR')
        // Simulate delayed/stale list data even after a successful DELETE.
        return ok({ liveRooms: state.live, readyRooms: { rooms: rooms.slice(0, limit), nextCursor: limit < rooms.length ? 'next' : null } })
      }
      case '/api/v1/rooms/ready': return ok({ rooms: rooms.slice(3), nextCursor: null })
      case '/api/v1/rooms/20':
        if (req.method() === 'DELETE') {
          state.deletes++
          expect(req.headers()['x-csrf-token']).toBe('test')
          if (state.result === 204) return route.fulfill({ status: 204 })
          if (state.result === 'lost') return route.abort('connectionreset')
          return fail(state.result, { 403: 'FORBIDDEN', 404: 'ROOM_NOT_FOUND', 409: 'INVALID_ROOM_STATUS' }[state.result])
        }
        state.reads++
        if (typeof state.detail === 'number') return fail(state.detail, state.detail === 404 ? 'ROOM_NOT_FOUND' : 'ERROR')
        return ok({ ...target, status: state.detail })
      default: return fail(404, 'UNKNOWN_ROUTE')
    }
  })
  await page.goto('/host/rooms/new')
  return state
}

async function openDialog(page) {
  const row = readyList(page).getByRole('listitem').filter({ hasText: target.title })
  await row.getByRole('button', { name: target.title + ' 메뉴' }).click()
  await row.getByRole('menuitem', { name: '강연방 삭제' }).click()
  return page.getByRole('dialog', { name: '강연방을 삭제할까요?' })
}

for (const width of [1280, 390]) {
  test(`deletion confirms the selected room and focuses cancel at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 840 })
    const state = await setup(page)
    const dialog = await openDialog(page)
    await expect(dialog.getByText(target.title, { exact: true })).toBeVisible()
    await expect(dialog.locator('time')).toHaveText(/11월 21일.*07:15.*한국 시간/)
    await expect(dialog.getByRole('button', { name: '취소' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(dialog).toHaveCount(0)
    await expect(page.getByRole('button', { name: target.title + ' 메뉴' })).toBeFocused()
    expect(state.deletes).toBe(0)
  })
}

test('deleted room stays removed when refresh fails and a later response is stale', async ({ page }) => {
  const state = await setup(page)
  await page.getByRole('button', { name: '더 보기' }).click()
  await expect(readyList(page).getByRole('listitem')).toHaveCount(6)
  await page.getByLabel('강연 제목').fill('작성 중인 방')
  await page.getByLabel('강연 설명').fill('작성 중인 설명')
  const dialog = await openDialog(page)
  state.homeStatus = 503
  await dialog.getByRole('button', { name: '영구 삭제' }).click()
  await expect(page.getByText('‘준비 강연 20’ 강연방을 삭제했습니다.')).toBeVisible()
  await expect(readyList(page).getByRole('listitem')).toHaveCount(5)
  await expect(readyList(page).getByText(target.title, { exact: true })).toHaveCount(0)
  const retry = page.getByRole('button', { name: '다시 불러오기' })
  await expect(retry).toBeEnabled()
  state.homeStatus = 200
  await retry.click()
  await expect(retry).toHaveCount(0)
  await expect(readyList(page).getByRole('listitem')).toHaveCount(5)
  await expect(readyList(page).getByText(target.title, { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('강연 제목')).toHaveValue('작성 중인 방')
  await expect(page.getByLabel('강연 설명')).toHaveValue('작성 중인 설명')
  expect(state.homeLimits).toEqual([3, 6, 6])
  expect(state.deletes).toBe(1)
  expect(state.reads).toBe(0)
})

test('a room that started while confirming appears in the refreshed live list', async ({ page }) => {
  const state = await setup(page)
  const dialog = await openDialog(page)
  state.result = 409
  state.live = [{ ...target, startedAt: target.createdAt }]
  await dialog.getByRole('button', { name: '영구 삭제' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByText(/이미 시작되어 삭제할 수 없습니다/)).toBeVisible()
  await expect(page.getByRole('region', { name: '진행 중인 내 강연' }).getByText(target.title)).toBeVisible()
  await expect(readyList(page).getByText(target.title)).toHaveCount(0)
  expect(state.deletes).toBe(1)
})

test('lost delete response and unavailable status allow only a status check until READY is verified', async ({ page }) => {
  const state = await setup(page)
  let dialog = await openDialog(page)
  state.result = 'lost'
  state.detail = 503
  await dialog.getByRole('button', { name: '영구 삭제' }).click()
  await expect(dialog.getByText(/강연방 상태를 확인하지 못했습니다/)).toBeVisible()
  await dialog.getByRole('button', { name: '취소' }).click()
  dialog = await openDialog(page)
  await expect(dialog.getByRole('button', { name: '영구 삭제' })).toHaveCount(0)
  state.detail = 'READY'
  await dialog.getByRole('button', { name: '상태 다시 확인' }).click()
  await expect(dialog.getByText(/아직 준비 중인 강연방/)).toBeVisible()
  expect(state.deletes).toBe(1)
  expect(state.reads).toBe(2)
  state.result = 204
  await dialog.getByRole('button', { name: '영구 삭제' }).click()
  await expect(page.getByText(/강연방을 삭제했습니다/)).toBeVisible()
  expect(state.deletes).toBe(2)
})

test('lost delete response followed by ROOM_NOT_FOUND reports absence without resending', async ({ page }) => {
  const state = await setup(page)
  state.result = 'lost'
  state.detail = 404
  const dialog = await openDialog(page)
  await dialog.getByRole('button', { name: '영구 삭제' }).click()
  await expect(page.getByText(/강연방이 더 이상 존재하지 않습니다/)).toBeVisible()
  await expect(readyList(page).getByText(target.title)).toHaveCount(0)
  expect(state.deletes).toBe(1)
  expect(state.reads).toBe(1)
})

test('losing permission closes the dialog and clears cached room information', async ({ page }) => {
  const state = await setup(page)
  state.result = 403
  await page.getByLabel('강연 제목').fill('새 발표')
  const dialog = await openDialog(page)
  await dialog.getByRole('button', { name: '영구 삭제' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('alert')).toContainText('이 강연방을 삭제할 권한이 없습니다.')
  await expect(readyList(page)).toHaveCount(0)
  await expect(page.getByLabel('강연 제목')).toHaveValue('새 발표')
  expect(state.deletes).toBe(1)
})
