import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'

async function createRoom(host, title) {
  const password = randomUUID()
  await host.goto('/signup')
  await host.getByLabel('이름', { exact: true }).fill('테스트 강연자')
  await host.getByLabel('이메일').fill(`room-${randomUUID()}@example.test`)
  await host.getByLabel('비밀번호', { exact: true }).fill(password)
  await host.getByLabel('비밀번호 확인').fill(password)
  await host.getByRole('button', { name: '회원가입', exact: true }).click()
  await expect(host).toHaveURL(/\/login$/)
  await host.getByLabel('비밀번호', { exact: true }).fill(password)
  await host.getByRole('button', { name: '로그인', exact: true }).click()
  await host.getByLabel('강연 제목').fill(title)
  await host.getByRole('button', { name: '강연방 만들기' }).click()
  await expect(host).toHaveURL(/\/host\/rooms\/\d+$/)
  const roomId = Number(new URL(host.url()).pathname.split('/').pop())
  await host.getByRole('button', { name: '청중 초대', exact: true }).click()
  const link = host.getByLabel('참여 링크')
  await expect(link).toBeVisible()
  const invitation = await link.inputValue()
  await host.getByRole('button', { name: '청중 초대 닫기' }).click()
  return { roomId, invitation }
}

async function enterAsAudience(audience, { roomId, invitation }) {
  const messages = []
  let subscribed = false
  audience.on('websocket', socket => {
    socket.on('framesent', ({ payload }) => {
      if (String(payload).includes(`destination:/topic/rooms/${roomId}/status`) && String(payload).startsWith('SUBSCRIBE')) subscribed = true
    })
    socket.on('framereceived', ({ payload }) => {
      const frame = String(payload).split('\n\n')
      if (frame[0].startsWith('MESSAGE')) messages.push(JSON.parse(frame[1].replace(/\0$/, '')))
    })
  })
  await audience.goto(invitation)
  await audience.getByLabel('닉네임').fill('참여자 하나')
  await audience.getByRole('button', { name: '입장', exact: true }).click()
  await expect(audience.getByRole('heading', { name: '강연 시작 대기 중' })).toBeVisible()
  await expect.poll(() => subscribed).toBe(true)
  await expect(audience.getByText('연결 확인 중', { exact: true })).toHaveCount(0)
  return messages
}

test('강연자가 준비 중인 방을 삭제하면 독립된 청중 창이 실제 ROOM_DELETED를 받고 자동 퇴장한다', async ({ page: host, browser }, info) => {
  const title = '청중 대기와 방 삭제 확인'
  const room = await createRoom(host, title)
  const audience = await browser.newPage({ viewport: { width: 390, height: 844 } })
  try {
    const messages = await enterAsAudience(audience, room)
    await host.getByRole('button', { name: '홈', exact: true }).click()
    await host.getByRole('button', { name: `${title} 메뉴`, exact: true }).click()
    await host.getByRole('menuitem', { name: '강연방 삭제' }).click()
    const dialog = host.getByRole('dialog')
    await expect(dialog).toContainText(title)
    await expect(dialog.getByRole('button', { name: '취소' })).toBeFocused()
    await host.screenshot({ path: info.outputPath('host-confirmation.png') })
    await audience.screenshot({ path: info.outputPath('audience-waiting.png') })
    const response = host.waitForResponse(r => r.request().method() === 'DELETE' && r.url().endsWith(`/api/v1/rooms/${room.roomId}`))
    await dialog.getByRole('button', { name: '영구 삭제' }).click()
    expect((await response).status()).toBe(204)
    await expect.poll(() => messages).toContainEqual({ type: 'ROOM_DELETED', data: { roomId: room.roomId } })
    await expect(audience).toHaveURL(/\/participation-ended\/unavailable$/)
    await expect(audience.getByRole('alert')).toHaveText('더 이상 참여할 수 없는 강연방입니다')
    await expect(host.getByRole('button', { name: `${title} 메뉴` })).toHaveCount(0)
    await audience.screenshot({ path: info.outputPath('audience-removed.png') })
    await audience.reload()
    await expect(audience.getByRole('heading', { name: '참여 종료' })).toBeVisible()
    await audience.goto(room.invitation)
    await expect(audience.getByRole('alert')).toHaveText('더 이상 참여할 수 없는 강연방입니다')
  } finally {
    await audience.close()
  }
})

test('강연 시작은 대기 창에서 반영되고 정상 종료는 같은 참여 종료 화면에 다른 사유를 표시한다', async ({ page: host, browser }) => {
  const room = await createRoom(host, '대기에서 진행으로')
  const audience = await browser.newPage()
  try {
    const messages = await enterAsAudience(audience, room)
    await host.getByRole('button', { name: '강연 시작', exact: true }).click()
    await expect(audience.getByRole('heading', { name: '강연 진행 중' })).toBeVisible()
    await expect(audience).toHaveURL(room.invitation)
    await host.getByRole('button', { name: '강연 종료', exact: true }).click()
    await host.getByRole('dialog').getByRole('button', { name: '강연 종료' }).click()
    await expect(audience).toHaveURL(/\/participation-ended\/ended$/)
    await expect(audience.getByText('강연이 종료되었습니다', { exact: true })).toBeVisible()
    expect(messages.filter(event => event.type === 'ROOM_STATUS_CHANGED').map(event => event.data.status)).toEqual(['LIVE', 'ENDED'])
  } finally {
    await audience.close()
  }
})

test('늦게 입장한 청중과 강연자가 채팅하고 재접속 후에도 같은 입장 경계가 적용된다', async ({ page: host, browser }, info) => {
  const room = await createRoom(host, '실시간 질문 실습')
  const audience = await browser.newPage({ viewport: { width: 390, height: 844 } })
  const panel = page => page.getByRole('region', { name: '강연 채팅' })
  async function send(page, content) {
    const chat = panel(page)
    await chat.getByRole('textbox', { name: '메시지' }).fill(content)
    await chat.getByRole('button', { name: '보내기', exact: true }).click()
    await expect(chat.getByRole('log').getByText(content, { exact: true })).toBeVisible()
    await expect(chat.getByRole('textbox', { name: '메시지' })).toHaveValue('')
  }
  const history = (page, role) => page.request.get(
    new URL(`/api/v1/rooms/${room.roomId}/messages`, room.invitation).href,
    { headers: { 'X-Chat-Role': role } },
  )
  const earlier = '강연 초기 안내입니다.'
  const question = '방금 보신 예시를 다시 설명해 주세요.'
  const answer = '네, 같은 예시로 다시 설명할게요.'
  const whileOffline = '이어서 실습을 시작합니다.'
  try {
    await host.getByRole('button', { name: '강연 시작', exact: true }).click()
    await send(host, earlier)
    const first = await history(host, 'HOST')
    expect(first.status()).toBe(200)
    const savedMessages = (await first.json()).data.messages
    expect(savedMessages.map(item => item.content)).toEqual([earlier])
    // 같은 초에 입장하면 규칙상 이전 메시지도 보이므로 다음 초에 입장한다.
    await expect.poll(Date.now).toBeGreaterThanOrEqual(Date.parse(savedMessages[0].receivedAt) + 1000)
    await audience.goto(room.invitation)
    await audience.getByLabel('닉네임').fill('늦게 온 청중')
    await audience.getByRole('button', { name: '입장', exact: true }).click()
    await expect(audience.getByRole('heading', { name: '강연 진행 중' })).toBeVisible()
    const restricted = await history(audience, 'PARTICIPANT')
    expect(restricted.status()).toBe(200)
    expect((await restricted.json()).data.messages).toEqual([])

    await send(audience, question)
    await expect(panel(host).getByText(question, { exact: true })).toBeVisible()
    await send(host, answer)
    await expect(panel(audience).getByText(answer, { exact: true })).toBeVisible()
    await expect(panel(audience).getByText('강연자', { exact: true })).toBeVisible()
    await expect(panel(audience).getByText(earlier)).toHaveCount(0)
    await host.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
    await host.screenshot({ path: info.outputPath('host-live-chat.png'), fullPage: true })
    await audience.screenshot({ path: info.outputPath('audience-live-chat.png') })

    await audience.context().setOffline(true)
    await send(host, whileOffline)
    await audience.context().setOffline(false)
    await expect(panel(audience).getByText(whileOffline, { exact: true })).toBeVisible({ timeout: 15000 })
    await audience.reload()
    await expect(panel(audience).getByText(whileOffline, { exact: true })).toBeVisible()
    await expect(panel(audience).getByText(earlier)).toHaveCount(0)
    const afterReload = await history(audience, 'PARTICIPANT')
    expect((await afterReload.json()).data.messages.map(m => [m.sender.type, m.content])).toEqual([
      ['PARTICIPANT', question], ['HOST', answer], ['HOST', whileOffline],
    ])
    await host.getByRole('button', { name: '강연 종료', exact: true }).click()
    await host.getByRole('dialog').getByRole('button', { name: '강연 종료' }).click()
    await expect(audience).toHaveURL(/\/participation-ended\/ended$/)
    await expect(panel(host).getByRole('textbox')).toHaveCount(0)
    await expect(panel(host).getByText(earlier, { exact: true })).toBeVisible()
    await expect(panel(host).getByText(question, { exact: true })).toBeVisible()
  } finally {
    await audience.close()
  }
})
