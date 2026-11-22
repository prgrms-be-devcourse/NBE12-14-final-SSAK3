import { Client } from '@stomp/stompjs'
import { getCsrfTokenForStomp } from '../stt/api.js'

// 강연방 STOMP 연결의 공통 진입점이다. 연결·CSRF 확인·재접속 시 재구독·오류 큐 구독과
// 탭 복귀·온라인 복귀 때의 재조회 호출을 맡고, 이벤트 해석과 재조회 내용은 호출한 쪽이 정한다.
//
// subscriptions: [{ destination, headers, handler }] 연결(재연결 포함)마다 다시 구독한다.
// onErrorQueue: 주면 /user/queue/errors 를 구독한다.
// onDisconnect / onConnect: 연결이 끊기거나 구독까지 끝났을 때 부른다.
// onStompError: 연결 해제 처리 뒤에 부른다. onAuthError: CSRF 확인이 401·403 으로 실패하면 부른다.
// refresh: 단순 브로커는 놓친 이벤트를 재전송하지 않으므로, 주기·탭 복귀·온라인 복귀 때 상태를 다시 맞춘다.
export function openRoomSocket({
  role, subscriptions, onErrorQueue, onConnect, onDisconnect, onStompError, onAuthError, refresh,
  refreshInterval = 30000,
}) {
  let active = true
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  const client = new Client({
    brokerURL: `${scheme}://${location.host}/ws`,
    reconnectDelay: 3000,
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
          if (error.status === 401 || error.status === 403) onAuthError?.(error)
        }
        throw error
      }
    },
    onConnect() {
      if (!active) return
      for (const { destination, headers, handler } of subscriptions) {
        client.subscribe(destination, frame => { if (active) handler(frame) }, headers)
      }
      if (onErrorQueue) client.subscribe('/user/queue/errors', frame => { if (active) onErrorQueue(frame) })
      onConnect?.()
    },
    onWebSocketClose: disconnected,
    onStompError() {
      disconnected()
      if (active) onStompError?.()
    },
  })

  function disconnected() {
    if (active) onDisconnect?.()
  }

  const refreshVisible = () => { if (active && !document.hidden && navigator.onLine) void refresh?.() }
  const offline = () => { disconnected(); client.forceDisconnect() }
  const timer = refresh ? window.setInterval(refreshVisible, refreshInterval) : null

  client.activate()
  document.addEventListener('visibilitychange', refreshVisible)
  window.addEventListener('online', refreshVisible)
  window.addEventListener('offline', offline)

  return {
    get connected() { return client.connected },
    publish(destination, headers, body) {
      if (!active || !client.connected) throw new Error('STOMP_DISCONNECTED')
      client.publish({ destination, headers, body })
    },
    close() {
      active = false
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refreshVisible)
      window.removeEventListener('online', refreshVisible)
      window.removeEventListener('offline', offline)
      void client.deactivate({ force: true })
    },
  }
}
