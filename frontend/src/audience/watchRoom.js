import { Client } from '@stomp/stompjs'
import { endReason, readParticipation } from './participation.js'

export function watchRoom({ code, roomId, onStatus, onEnded, onParticipant, onConnection }) {
  let active = true
  let checking = false
  let needsAnotherCheck = false
  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const client = new Client({
    brokerURL: `${wsProtocol}//${window.location.host}/ws`,
    reconnectDelay: 3000,
    connectionTimeout: 8000,
    onConnect() {
      if (!active) return
      client.subscribe(`/topic/rooms/${roomId}/status`, message => {
        if (!active) return
        let event
        try { event = JSON.parse(message.body) } catch { return }
        if (event?.data?.roomId !== roomId) return
        if (event.type === 'ROOM_DELETED') finish('unavailable')
        if (event.type !== 'ROOM_STATUS_CHANGED') return
        if (event.data.status === 'ENDED') finish('ended')
        if (event.data.status === 'LIVE') onStatus('LIVE')
      })
      void reconcile()
    },
    onWebSocketClose() {
      if (active) onConnection(false)
    },
    onStompError() {
      if (active) {
        onConnection(false)
        void reconcile()
      }
    },
  })

  function finish(reason) {
    active = false
    onEnded(reason)
  }

  async function reconcile() {
    if (!active) return
    if (checking) { needsAnotherCheck = true; return }
    checking = true
    try {
      const { room, participant } = await readParticipation(code, true)
      if (!active) return
      if (room.roomId !== roomId) { finish('unavailable'); return }
      if (room.status === 'ENDED') { finish('ended'); return }
      onParticipant(participant)
      if (!participant) {
        active = false
        return
      }
      onStatus(room.status)
      onConnection(client.connected && navigator.onLine)
    } catch (error) {
      if (!active) return
      const reason = endReason(error)
      if (reason) finish(reason)
      else onConnection(false)
    } finally {
      checking = false
      if (needsAnotherCheck) {
        needsAnotherCheck = false
        void reconcile()
      }
    }
  }

  function offline() {
    if (!active) return
    onConnection(false)
    client.forceDisconnect()
  }
  function refreshVisible() {
    if (!document.hidden) void reconcile()
  }
  // The simple broker does not acknowledge SUBSCRIBE or replay missed events.
  // A snapshot after subscribing and occasional visible-tab checks cover that gap.
  client.activate()
  const timer = window.setInterval(refreshVisible, 30000)
  document.addEventListener('visibilitychange', refreshVisible)
  window.addEventListener('online', reconcile)
  window.addEventListener('offline', offline)
  function close() {
    active = false
    window.clearInterval(timer)
    document.removeEventListener('visibilitychange', refreshVisible)
    window.removeEventListener('online', reconcile)
    window.removeEventListener('offline', offline)
    void client.deactivate({ force: true })
  }
  return { reconcile, close }
}
