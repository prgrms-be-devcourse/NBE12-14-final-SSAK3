import { openRoomSocket } from '../ws/roomSocket.js'
import { endReason, readParticipation } from './participation.js'

export function watchRoom({ code, roomId, onStatus, onEnded, onParticipant, onConnection }) {
  let active = true
  let checking = false
  let needsAnotherCheck = false
  // The simple broker does not acknowledge SUBSCRIBE or replay missed events.
  // A snapshot after subscribing and occasional visible-tab checks cover that gap.
  const socket = openRoomSocket({
    role: 'PARTICIPANT',
    subscriptions: [{
      destination: `/topic/rooms/${roomId}/status`,
      handler(message) {
        if (!active) return
        let event
        try { event = JSON.parse(message.body) } catch { return }
        if (event?.data?.roomId !== roomId) return
        if (event.type === 'ROOM_DELETED') finish('unavailable')
        if (event.type !== 'ROOM_STATUS_CHANGED') return
        if (event.data.status === 'ENDED') finish('ended')
        if (event.data.status === 'LIVE') onStatus('LIVE')
      },
    }],
    onConnect: () => void reconcile(),
    onDisconnect() {
      if (active) onConnection(false)
    },
    onStompError() {
      if (active) void reconcile()
    },
    refresh: reconcile,
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
      onConnection(socket.connected && navigator.onLine)
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

  function close() {
    active = false
    socket.close()
  }

  return { reconcile, close }
}
