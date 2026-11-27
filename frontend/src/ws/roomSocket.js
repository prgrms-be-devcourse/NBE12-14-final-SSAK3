import { Client, ReconnectionTimeMode } from '@stomp/stompjs'
import { getCsrfTokenForStomp } from '../stt/api.js'

// 강연방 STOMP 연결의 공통 진입점이다. 연결·CSRF 확인·재접속 시 재구독·오류 큐 구독과
// 탭 복귀·온라인 복귀 때의 재조회 호출을 맡고, 이벤트 해석과 재조회 내용은 호출한 쪽이 정한다.
//
// 같은 역할(role)의 호출은 WebSocket 연결 하나를 나눠 쓴다. 서버는 구독·전송마다 방 권한을 검사하므로
// 연결을 합쳐도 기능별 권한은 그대로다. 호출마다 구독만 따로 등록·해제하고, 마지막 호출이 닫히면 연결을 끊는다.
//
// subscriptions: [{ destination, headers, handler }] 연결(재연결 포함)마다 다시 구독한다.
// onErrorQueue: 주면 /user/queue/errors 를 구독한다.
// onDisconnect / onConnect: 연결이 끊기거나 구독까지 끝났을 때 부른다. 이미 연결된 연결에 합류하면 바로 부른다.
// onStompError: 연결 해제 처리 뒤에 부른다. onAuthError: CSRF 확인이 401·403 으로 실패하면 부른다.
// refresh: 단순 브로커는 놓친 이벤트를 재전송하지 않으므로, 주기·탭 복귀·온라인 복귀 때 상태를 다시 맞춘다.
const hubs = new Map()

export function openRoomSocket({
  role, subscriptions, onErrorQueue, onConnect, onDisconnect, onStompError, onAuthError, refresh,
  refreshInterval = 30000,
}) {
  let hub = hubs.get(role)
  if (!hub) {
    hub = openHub(role, () => hubs.delete(role))
    hubs.set(role, hub)
  }

  const member = { active: true, subs: [], subscriptions, onErrorQueue, onConnect, onDisconnect, onStompError, onAuthError }
  const refreshVisible = () => { if (member.active && !document.hidden && navigator.onLine) void refresh?.() }
  const timer = refresh ? window.setInterval(refreshVisible, refreshInterval) : null

  document.addEventListener('visibilitychange', refreshVisible)
  window.addEventListener('online', refreshVisible)
  hub.join(member)

  return {
    get connected() { return hub.client.connected },
    publish(destination, headers, body) {
      if (!member.active || !hub.client.connected) throw new Error('STOMP_DISCONNECTED')
      hub.client.publish({ destination, headers, body })
    },
    close() {
      if (!member.active) return
      member.active = false
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refreshVisible)
      window.removeEventListener('online', refreshVisible)
      hub.leave(member)
    },
  }
}

function openHub(role, onEmpty) {
  const members = new Set()
  const entries = new Map()
  let active = true
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  const client = new Client({
    brokerURL: `${scheme}://${location.host}/ws`,
    // 서버가 끊겼을 때 모든 클라이언트가 3초마다 일제히 재접속하지 않도록 간격을 지수로 늘린다.
    reconnectDelay: 3000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    maxReconnectDelay: 30000,
    connectionTimeout: 8000,
    beforeConnect: async () => {
      try {
        // SCRUM-164: 새 연결과 재연결마다 현재 HTTP 세션의 CSRF 토큰을 확인한다.
        // 일시적인 CSRF 요청 실패는 재시도한 뒤 연결한다.
        const csrf = await getCsrfTokenForStomp(() => active, disconnected)
        if (!active || !csrf) return
        client.connectHeaders = { 'X-Chat-Role': role, [csrf.headerName]: csrf.token }
      } catch (error) {
        if (active) {
          disconnected()
          if (error.status === 401 || error.status === 403) each(member => member.onAuthError?.(error))
        }
        throw error
      }
    },
    onConnect() {
      if (!active) return
      each(subscribe)
      each(member => member.onConnect?.())
    },
    onWebSocketClose: disconnected,
    onStompError() {
      disconnected()
      if (active) each(member => member.onStompError?.())
    },
  })

  // 알림을 받은 쪽이 닫혀도 순회가 흐트러지지 않게 복사본을 돈다.
  function each(action) {
    for (const member of [...members]) if (member.active) action(member)
  }

  function disconnected() {
    if (!active) return
    // 끊긴 연결의 구독은 모두 사라졌으므로 재접속 때 새로 맺는다.
    entries.clear()
    each(member => {
      member.subs = []
      member.onDisconnect?.()
    })
  }

  // 같은 주소·헤더의 구독은 STOMP 구독 하나를 나눠 쓰고, 프레임은 구독한 호출마다 전달한다.
  // 서버가 구독마다 프레임을 만들어 보내므로 중복 구독을 합치면 같은 이벤트가 한 번만 실린다.
  function subscribe(member) {
    const wanted = member.subscriptions.map(({ destination, headers, handler }) => ({ destination, headers, handler }))
    if (member.onErrorQueue) wanted.push({ destination: '/user/queue/errors', headers: undefined, handler: member.onErrorQueue })
    member.subs = wanted.map(({ destination, headers, handler }) => {
      const key = `${destination}\n${JSON.stringify(headers ?? {})}`
      let entry = entries.get(key)
      if (!entry) {
        entry = { key, listeners: new Set(), sub: null }
        const shared = entry
        entry.sub = client.subscribe(destination, frame => shared.listeners.forEach(listener => listener(frame)), headers)
        entries.set(key, entry)
      }
      const listener = frame => { if (member.active) handler(frame) }
      entry.listeners.add(listener)
      return { entry, listener }
    })
  }

  function unsubscribe(member) {
    for (const { entry, listener } of member.subs) {
      entry.listeners.delete(listener)
      if (entry.listeners.size > 0 || entries.get(entry.key) !== entry) continue
      entries.delete(entry.key)
      try { entry.sub.unsubscribe() } catch { /* 이미 끊긴 연결 */ }
    }
    member.subs = []
  }

  const offline = () => { disconnected(); client.forceDisconnect() }
  window.addEventListener('offline', offline)
  client.activate()

  return {
    client,
    join(member) {
      members.add(member)
      // 이미 연결돼 있으면 구독까지 끝낸 뒤 알린다. 호출한 쪽이 반환값을 받은 다음에 불리도록 미룬다.
      if (client.connected) {
        queueMicrotask(() => {
          if (!active || !member.active || !client.connected) return
          subscribe(member)
          member.onConnect?.()
        })
      }
    },
    leave(member) {
      members.delete(member)
      unsubscribe(member)
      if (members.size > 0) return
      active = false
      window.removeEventListener('offline', offline)
      void client.deactivate({ force: true })
      onEmpty()
    },
  }
}
