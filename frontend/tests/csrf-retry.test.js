import test from 'node:test'
import assert from 'node:assert/strict'
import { getCsrfTokenForStomp } from '../src/stt/api.js'

const response = (status, body) => new Response(JSON.stringify(body), {
    status, headers: { 'content-type': 'application/json' },
})

test('CSRF 최초 503 응답 뒤 재요청에 성공하면 토큰을 반환한다', async t => {
    const original = globalThis.fetch
    let attempts = 0
    globalThis.fetch = async () => ++attempts === 1
        ? response(503, { code: 'INTERNAL_ERROR' })
        : response(200, { data: { headerName: 'X-CSRF-TOKEN', token: 'recovered' } })
    t.after(() => { globalThis.fetch = original })

    let retries = 0
    const token = await getCsrfTokenForStomp(() => true, () => { retries++ }, 0)
    assert.deepEqual(token, { headerName: 'X-CSRF-TOKEN', token: 'recovered' })
    assert.equal(attempts, 2)
    assert.equal(retries, 1)
})

test('CSRF 403은 자동 재시도하지 않는다', async t => {
    const original = globalThis.fetch
    let attempts = 0
    globalThis.fetch = async () => {
        attempts++
        return response(403, { code: 'FORBIDDEN' })
    }
    t.after(() => { globalThis.fetch = original })

    await assert.rejects(getCsrfTokenForStomp(() => true, () => {}, 0),
        error => error.status === 403)
    assert.equal(attempts, 1)
})

test('재시도 대기 중 화면이 닫히면 추가 CSRF 요청을 보내지 않는다', async t => {
    const original = globalThis.fetch
    let attempts = 0
    let active = true
    globalThis.fetch = async () => {
        attempts++
        return response(503, { code: 'INTERNAL_ERROR' })
    }
    t.after(() => { globalThis.fetch = original })

    const token = await getCsrfTokenForStomp(() => active, () => { active = false }, 0)
    assert.equal(token, null)
    assert.equal(attempts, 1)
})
