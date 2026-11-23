import { expect } from '@playwright/test'
import { setup as setupRecording } from './stt-fixture.js'

export async function setupHostRoom(context, status = 'LIVE') {
  const recording = await setupRecording(context)
  await context.routeWebSocket('**/ws', socket => {
    socket.onMessage(frame => {
      if (frame.toString().startsWith('CONNECT\n')) socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0')
    })
  })
  await context.route(url => url.pathname === '/api/v1/rooms/home', route =>
    route.fulfill({ json: { data: { liveRooms: [], readyRooms: { rooms: [], nextCursor: null } } } }))
  const room = { status, endedAt: '2026-11-19T02:00:00Z', failDetail: false, start: 'ok', end: 'ok', events: [], records: null, messages: [] }
  await context.route('**/api/v1/rooms/10{,/**}', async route => {
    const req = route.request()
    const path = new URL(req.url()).pathname.replace('/api/v1/rooms/10', '')
    room.events.push(req.method() + ' ' + path)
    const ok = data => route.fulfill({ json: { data } })
    if (path === '') {
      if (room.failDetail) return route.abort()
      return ok({ roomId: 10, title: '강연 진행 실습', status: room.status, createdAt: '2026-11-19T00:00:00Z',
        startedAt: room.status === 'READY' ? null : '2026-11-19T01:00:00Z',
        endedAt: room.status === 'ENDED' ? room.endedAt : null })
    }
    if (path === '/start' || path === '/end') {
      expect(req.method()).toBe('POST')
      expect(req.headers()['x-csrf-token']).toBe(recording.csrfToken)
      const outcome = room[path.slice(1)]
      if (outcome === 'lost-before') return route.abort()
      room.status = path === '/start' ? 'LIVE' : 'ENDED'
      if (outcome === 'lost-after') return route.abort()
      const timeField = path === '/start' ? 'startedAt' : 'endedAt'
      return ok({ roomId: 10, status: room.status, [timeField]: room.status === 'ENDED' ? room.endedAt : '2026-11-19T01:00:00Z' })
    }
    if (path === '/invitation') return ok({ joinCode: 'ABC123', joinUrl: 'https://example.test/join/ABC123',
      qrText: 'https://example.test/join/ABC123' })
    if (path === '/ai/analyses') return ok({ roomId: 10, analyses: [] })
    if (path === '/messages') return ok({ messages: room.messages, nextCursor: null, hasNext: false })
    if (path === '/stt/segments' && room.records) return ok(room.records)
    return route.fallback()
  })
  return { room, recording }
}
