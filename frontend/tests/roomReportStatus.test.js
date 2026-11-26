import { test } from 'node:test'
import assert from 'node:assert/strict'
import { roomReportSource } from '../src/room/reportStatus.js'

test('자동 접수 대기는 서버의 종료 시각과 오류 응답 시각 사이에 남은 시간만 사용한다', async t => {
  const endedAt = '2026-11-23T01:00:00Z'
  let serverTime = '2026-11-23T01:00:01.350Z'
  t.mock.method(globalThis, 'fetch', async () => Response.json({
    code: 'REPORT_NOT_FOUND', timestamp: serverTime,
  }, { status: 404 }))
  const source = roomReportSource(10, endedAt)
  assert.deepEqual(await source.load(), { status: 'AWAITING_RECEIPT', checkAfterMs: 650 })
  serverTime = '2026-11-23T01:00:02Z'
  assert.deepEqual(await source.load(), { status: 'NOT_CREATED', checkAfterMs: 30000 })
})

test('방이 없거나 서버 시각을 확인할 수 없으면 수동 생성 상태를 반환하지 않는다', async t => {
  let code = 'ROOM_NOT_FOUND'
  t.mock.method(globalThis, 'fetch', async () => Response.json({ code }, { status: 404 }))
  const source = roomReportSource(10, '2026-11-23T01:00:00Z')
  await assert.rejects(source.load(), { code: 'ROOM_NOT_FOUND' })
  code = 'REPORT_NOT_FOUND'
  await assert.rejects(source.load())
})
