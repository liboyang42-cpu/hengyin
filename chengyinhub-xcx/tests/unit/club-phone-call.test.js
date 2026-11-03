'use strict'
// 2026-11-02 用户:有号码权限的人在名册/客户详情上能直接拨打玩家电话。
// 工具只认 11 位手机号,脱敏串、替代说明一律不给拨号。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const calls = []
global.wx = { makePhoneCall: o => { calls.push(o.phoneNumber); if (o.phoneNumber === '13900000000') o.fail({ errMsg: 'makePhoneCall:fail' }) } }
const { callableNumber, callMember } = require('../../pages/club/utils/club-phone-call.js')

test('只有完整 11 位手机号可拨', () => {
  assert.equal(callableNumber(' 13812345621 '), '13812345621')
  assert.equal(callableNumber('138****5621'), '')
  assert.equal(callableNumber('由俱乐部带团，请联系领队'), '')
  assert.equal(callableNumber(null), '')
})
test('掩码不拨号;完整号码拨号,失败回调只在真失败时触发', () => {
  let failed = 0
  assert.equal(callMember('138****5621', () => failed++), false)
  assert.deepEqual(calls, [])
  assert.equal(callMember('13812345621', () => failed++), true)
  assert.equal(callMember('13900000000', () => failed++), true)
  assert.deepEqual(calls, ['13812345621', '13900000000'])
  assert.equal(failed, 1)
})
