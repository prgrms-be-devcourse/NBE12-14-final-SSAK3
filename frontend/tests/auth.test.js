import test from 'node:test'
import assert from 'node:assert/strict'
import { validateAuth } from '../src/auth/validation.js'
import { returnPath } from '../src/room/state.js'

test('로그인 후 내 강연 기록으로 복귀할 수 있다', () => {
  assert.equal(returnPath('/host/rooms/history'), '/host/rooms/history')
  assert.equal(returnPath('/host/rooms/history/other'), null)
})

const valid = { name: '강연자', email: 'host@example.com', password: 'password', confirm: 'password' }
test('signup checks UTF-8 byte limit instead of just character count', () => {
  assert.equal(validateAuth({ ...valid, password: '가'.repeat(24), confirm: '가'.repeat(24) }, true).password, undefined)
  assert.ok(validateAuth({ ...valid, password: '가'.repeat(25) }, true).password)
  assert.ok(validateAuth({ ...valid, password: 'short' }, true).password)
})
test('required fields, password confirmation and name boundary are enforced', () => {
  assert.deepEqual(validateAuth(valid, true), {})
  assert.ok(validateAuth({ ...valid, name: '  ' }, true).name)
  assert.ok(validateAuth({ ...valid, name: '가'.repeat(101) }, true).name)
  assert.ok(validateAuth({ ...valid, email: 'abc' }, true).email)
  assert.ok(validateAuth({ ...valid, confirm: 'different' }, true).confirm)
})
test('login does not introduce signup-only password length rules', () => {
  assert.deepEqual(validateAuth({ email: 'host@example.com', password: 'old' }, false), {})
  assert.ok(validateAuth({ email: 'host@example.com', password: ' ' }, false).password)
})
