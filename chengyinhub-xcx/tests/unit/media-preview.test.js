const test = require('node:test')
const assert = require('node:assert/strict')
const { open } = require('../../utils/media-preview.js')

test('open 补 current:按 URL / 下标 / 缺省回退首张', () => {
  const calls = []
  global.wx = { previewImage: (o) => calls.push(o) }
  assert.equal(open(['a', 'b', 'c'], 'c'), true)
  assert.deepEqual(calls[0], { current: 'c', urls: ['a', 'b', 'c'] })
  open(['a', 'b'], 1)
  assert.equal(calls[1].current, 'b')
  open(['a', 'b'], 'zzz')
  assert.equal(calls[2].current, 'a')
  open('solo')
  assert.deepEqual(calls[3], { current: 'solo', urls: ['solo'] })
})

test('open 过滤空值,无图不调起', () => {
  const calls = []
  global.wx = { previewImage: (o) => calls.push(o) }
  assert.equal(open(['', null, undefined]), false)
  assert.equal(open(undefined), false)
  assert.equal(calls.length, 0)
  open(['', 'x'], 'x')
  assert.deepEqual(calls[0].urls, ['x'])
})
