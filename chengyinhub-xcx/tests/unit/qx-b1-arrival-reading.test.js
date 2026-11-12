// qx-b1 U05-01..03 · §23.2 / §27.3:到达定位时刻、拒绝原因与批准替代、到达读数按回执只记一次。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const ar = require('../../pages/play/utils/arrival-reading.js')

const read = rel => fs.readFileSync(path.join(__dirname, '../..', rel), 'utf8')
const memoryWx = () => { const store = {}; return { store, getStorageSync: k => store[k], setStorageSync: (k, v) => { store[k] = JSON.parse(JSON.stringify(v)) } } }

test('locationTimestamp 只来自真实采样时刻;没有就不带', () => {
  assert.equal(ar.timestampOf({ latitude: 1 }), '')
  const stamped = ar.stamp({ latitude: 31, longitude: 121, accuracy: 12 })
  assert.match(ar.timestampOf(stamped), /^[1-9][0-9]{12}$/)
  assert.equal(stamped.accuracy, 12)
})

test('到达读数只认 level 0..3 与非空 log;不换算奖励', () => {
  assert.deepEqual(ar.reading({ level: 2, log: '  门后有风  ' }), { level: 2, log: '门后有风' })
  assert.equal(ar.reading({ level: 4, log: 'x' }), null)
  assert.equal(ar.reading({ level: 1, log: '' }), null)
  assert.equal(ar.reading(null), null)
})

test('同一人同一局同一站只记一次;换局是新的一条', () => {
  const wx = memoryWx()
  const k = ar.key(7, 'run-1', 11)
  assert.equal(ar.record(wx, k, { level: 1, log: '第一次' }), true)
  assert.equal(ar.record(wx, k, { level: 3, log: '重读回执' }), false, '同回执重读不追加')
  assert.deepEqual(ar.lookup(wx, k), { level: 1, log: '第一次' })
  assert.equal(ar.record(wx, ar.key(7, 'run-2', 11), { level: 0, log: '新局' }), true)
  assert.equal(ar.lookup(wx, ar.key(8, 'run-1', 11)), null, '换账号读不到别人的读数')
})

test('拒绝只取服务端原因码与批准替代;替代站必须是本局已下发且未完成的真实节点', () => {
  const nodes = [{ nodeId: 11, name: '豆浆摊' }, { nodeId: 12, name: '老书店', done: false }, { nodeId: 13, name: '已过站', done: true }]
  const view = ar.rejection({ arrivalReasonCode: 'ARRIVE_TOO_FAR', canRetry: true, approvedFallbacks: ['12', '13', '99', '12'] }, nodes)
  assert.deepEqual(view, { reasonCode: 'ARRIVE_TOO_FAR', canRetry: true, fallbacks: [{ nodeId: '12', name: '老书店' }] })
  assert.deepEqual(ar.rejection({ errorCode: 'X', retryable: true }, nodes), { reasonCode: '', canRetry: false, fallbacks: [] },
    '没有新字段的旧回包不推断')
})

test('页面接线:到达带时刻、成功回执记读数、读数接在本站手记后、替代站只开卡片', () => {
  const js = read('pages/play/index.js')
  assert.match(js, /located \? \{ locationTimestamp: located \} : \{\}/)
  assert.match(js, /that\._noteArrivalReading\(nodeId, r\.data && r\.data\.arrivalReading\)/)
  assert.match(js, /const read = this\._arrivalReadingFor\(b\.nodeId\)/)
  assert.match(js, /Object\.assign\(guard, arrivalReading\.rejection\(r, that\.data\.nodes\)\)/)
  const open = js.slice(js.indexOf('openArrivalFallback(e) {'), js.indexOf('openArrivalFallback(e) {') + 500)
  assert.ok(!/arrive\(|onComplete\(/.test(open), '替代站不能由客户端标记到达')
  assert.match(read('pages/play/index.wxml'), /bindtap="openArrivalFallback"/)
})
