import { test, expect } from '@playwright/test'

async function setup(page) {
  const state = { authenticated: false, meError: 0, signup: 'ok', login: 'ok', logout: 'ok', posts: [] }
  await page.route('**/api/v1/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    const ok = data => route.fulfill({ json: { data } })
    const fail = (status, code) => route.fulfill({ status, json: { code } })
    if (path.endsWith('/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'test' })
    if (path.endsWith('/me')) return state.meError ? fail(state.meError, 'ERROR')
      : state.authenticated ? ok({ userId: 1, name: '강연자' }) : fail(401, 'UNAUTHENTICATED')
    if (req.method() === 'POST') {
      expect(req.headers()['x-csrf-token']).toBe('test')
      state.posts.push({ path, body: req.postDataJSON() })
    }
    if (path.endsWith('/signup')) {
      if (state.signup === 'lost') return route.abort()
      if (state.signup === 'duplicate') return fail(409, 'EMAIL_ALREADY_EXISTS')
      return ok({ userId: 1 })
    }
    if (path.endsWith('/login')) {
      if (state.login === 'wrong') return fail(401, 'INVALID_CREDENTIALS')
      state.authenticated = true; return ok({ authenticated: true })
    }
    if (path.endsWith('/logout')) {
      if (state.logout === 'lost') return route.abort()
      state.authenticated = false; return route.fulfill({ status: 204 })
    }
    if (path.endsWith('/rooms/10')) return ok({ roomId: 10, title: '내 강연', status: 'ENDED', createdAt: '2026-11-19T01:00:00Z' })
    return fail(404, 'NOT_FOUND')
  })
  return state
}
async function fillSignup(page) {
  await page.getByLabel('이름', { exact: true }).fill('강연자')
  await page.getByLabel('이메일', { exact: true }).fill('host@example.com')
  await page.getByLabel('비밀번호', { exact: true }).fill('password123')
  await page.getByLabel('비밀번호 확인').fill('password123')
}
async function submitLogin(page) {
  await page.getByLabel('이메일', { exact: true }).fill('host@example.com')
  await page.getByLabel('비밀번호', { exact: true }).fill('password123')
  await page.getByRole('button', { name: '로그인', exact: true }).click()
}

test('signup hands only email to login and retains return target without automatic login', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/signup?returnTo=%2Fhost%2Frooms%2F10')
  await fillSignup(page)
  await page.getByRole('button', { name: '회원가입', exact: true }).dblclick()
  await expect(page).toHaveURL(/\/login\?returnTo=/)
  await expect(page.getByText('가입이 완료되었습니다. 로그인해 주세요.')).toBeVisible()
  await expect(page.getByLabel('이메일')).toHaveValue('host@example.com')
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('')
  expect(state.posts).toHaveLength(1)
  expect(state.posts[0].body).toEqual({ email: 'host@example.com', name: '강연자', password: 'password123' })
  expect(await page.evaluate(() => JSON.stringify({ history: history.state, local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain('password123')
  await submitLogin(page)
  await expect(page).toHaveURL(/\/host\/rooms\/10$/)
  await expect(page.getByRole('heading', { name: '내 강연' })).toBeVisible()
})

test('blur validation, correction and submit focus do not send invalid inputs', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/signup')
  await expect(page.locator('[aria-invalid=true]')).toHaveCount(0)
  await page.getByLabel('이메일').fill('abc')
  await page.getByLabel('비밀번호', { exact: true }).focus()
  await expect(page.getByText('이메일 형식을 확인해 주세요.')).toBeVisible()
  await page.getByLabel('이메일').fill('host@example.com')
  await expect(page.getByText('이메일 형식을 확인해 주세요.')).toHaveCount(0)
  await page.getByRole('button', { name: '회원가입', exact: true }).click()
  await expect(page.getByLabel('이름')).toBeFocused()
  expect(state.posts).toHaveLength(0)
})

test('duplicate email offers login with email filled', async ({ page }) => {
  const state = await setup(page); state.signup = 'duplicate'
  await page.goto('/signup'); await fillSignup(page)
  await page.getByRole('button', { name: '회원가입', exact: true }).click()
  await expect(page.getByText('이미 가입된 이메일입니다.')).toBeVisible()
  await page.getByRole('button', { name: '이 이메일로 로그인' }).click()
  await expect(page.getByLabel('이메일')).toHaveValue('host@example.com')
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('')
})

test('lost signup response retains inputs, warns and never automatically resends', async ({ page }) => {
  const state = await setup(page); state.signup = 'lost'
  await page.goto('/signup'); await fillSignup(page)
  await page.getByRole('button', { name: '회원가입', exact: true }).click()
  await expect(page.getByText('가입 여부를 확인하지 못했습니다.')).toBeVisible()
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('password123')
  expect(state.posts).toHaveLength(1)
  await page.getByRole('button', { name: '로그인으로 확인' }).click()
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('')
  expect(state.posts).toHaveLength(1)
})

test('incorrect credentials retain form, successful retry uses default destination', async ({ page }) => {
  const state = await setup(page); state.login = 'wrong'
  await page.goto('/login'); await submitLogin(page)
  await expect(page.getByText('이메일 또는 비밀번호를 확인해 주세요.')).toBeVisible()
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveValue('password123')
  state.login = 'ok'
  await page.getByRole('button', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL(/\/host\/rooms\/new$/)
})

test('session check connection failure is not treated as signed-out; retry can redirect signed-in user', async ({ page }) => {
  const state = await setup(page); state.meError = 500
  await page.goto('/login?returnTo=https://example.com')
  await expect(page.getByText('로그인 상태를 확인하지 못했습니다. 서버 연결을 확인해 주세요.')).toBeVisible()
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveCount(0)
  state.meError = 0; state.authenticated = true
  await page.getByRole('button', { name: '다시 확인' }).click()
  await expect(page).toHaveURL(/\/host\/rooms\/new$/)
})

test('logout confirmation and connection failure preserve draft until logout succeeds', async ({ page }) => {
  const state = await setup(page); state.authenticated = true
  await page.goto('/host/rooms/new')
  await page.getByLabel('강연 제목').fill('초안')
  page.once('dialog', dialog => dialog.dismiss())
  await page.getByRole('button', { name: '로그아웃' }).click()
  expect(state.posts).toHaveLength(0)
  state.logout = 'lost'
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByText('로그아웃 결과를 확인하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.')).toBeVisible()
  await expect(page.getByLabel('강연 제목')).toHaveValue('초안')
  state.logout = 'ok'
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/login$/)
  expect(await page.evaluate(() => sessionStorage.getItem('react:room-draft:v1'))).toBeNull()
})

test('auth pages fit mobile and use the agreed font', async ({ page }, testInfo) => {
  await setup(page)
  await page.goto('/login')
  await expect(page.getByLabel('이메일')).toBeVisible()
  await page.evaluate(() => document.fonts.ready)
  expect(await page.locator('.auth-app').evaluate(node => getComputedStyle(node).fontFamily)).toContain('Pretendard')
  await page.screenshot({ path: testInfo.outputPath('login-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '회원가입', exact: true }).click()
  await expect(page.getByLabel('비밀번호 확인')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('signup-mobile.png'), fullPage: true })
})

for (const signedIn of [false, true]) test(`root routes by server session: ${signedIn}`, async ({ page }) => {
  const state = await setup(page); state.authenticated = signedIn
  await page.goto('/')
  await expect(page).toHaveURL(signedIn ? /\/host\/rooms\/new$/ : /\/login$/)
  await expect(page.getByText('강연자 STT 연결 확인')).toHaveCount(0)
  if (signedIn) await expect(page.getByLabel('강연 제목')).toBeVisible()
  else await expect(page.getByLabel('이메일')).toBeVisible()
})

test('root connection failure stays on entry and retries without assuming signed-out', async ({ page }) => {
  const state = await setup(page); state.meError = 500
  await page.goto('/')
  await expect(page.getByText('로그인 상태를 확인하지 못했습니다. 서버 연결을 확인해 주세요.')).toBeVisible()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByLabel('비밀번호', { exact: true })).toHaveCount(0)
  state.meError = 0; state.authenticated = true
  await page.getByRole('button', { name: '다시 확인' }).click()
  await expect(page).toHaveURL(/\/host\/rooms\/new$/)
})

test('unknown route does not expose STT demo', async ({ page }) => {
  await setup(page)
  await page.goto('/not-a-page')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByText('강연자 STT 연결 확인')).toHaveCount(0)
})
