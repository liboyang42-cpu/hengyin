const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const ll = require('../../utils/location/last-location.js')

const mem = () => { const m = {}; return { getStorageSync: (k) => m[k], setStorageSync: (k, v) => { m[k] = v } } }

test('无存储 → 固定坐标 + 未获取定位提示', () => {
  const r = ll.resolve(mem())
  assert.equal(r.source, 'fixed')
  assert.equal(r.latitude, ll.FIXED.latitude)
  assert.match(r.hint, /未获取定位/)
})

test('save 后 resolve 用上次位置;非法坐标不写入', () => {
  const env = mem()
  assert.equal(ll.save({ latitude: 0, longitude: 0 }, env), false)
  assert.equal(ll.save({ latitude: 'x', longitude: 1 }, env), false)
  assert.equal(ll.save({ latitude: 30.1, longitude: 120.2 }, env), true)
  const r = ll.resolve(env)
  assert.deepEqual([r.source, r.latitude, r.longitude], ['last', 30.1, 120.2])
})

test('存储抛错时不崩,回退固定坐标', () => {
  const env = { getStorageSync() { throw new Error('x') }, setStorageSync() { throw new Error('x') } }
  assert.equal(ll.resolve(env).source, 'fixed')
  assert.equal(ll.save({ latitude: 1, longitude: 2 }, env), false)
})

test('searchmap / square list 不再写死上海坐标，接入 last-location', () => {
  for (const p of ['pages/searchmap/index.js', 'pages/square/list/index.js']) {
    const s = fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')
    assert.doesNotMatch(s, /latitude:\s*31\.23/, p)
    assert.match(s, /last-location/, p)
  }
})
