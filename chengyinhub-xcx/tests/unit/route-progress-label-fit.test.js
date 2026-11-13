'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')

function load() {
  let def
  global.Component = (d) => { def = d }
  delete require.cache[require.resolve('../../pages/topic/components/cy/route-progress/index.js')]
  require('../../pages/topic/components/cy/route-progress/index.js')
  return def
}
function marksOf(total, done) {
  const def = load()
  const ctx = { data: Object.assign({}, def.data, { total, done, labels: Array.from({ length: total }, (_, i) => '第' + (i + 1) + '站'), tone: 'city' }), _clear() {}, setData(o) { Object.assign(this.data, o) } }
  def.methods._prepare.call(ctx)
  return ctx.data.marks
}

test('7 站以内每个圆点都带标签', () => {
  assert.ok(marksOf(7, 2).every((m) => m.show))
})

test('10 站只留首、尾和当前站的标签,圆点数不变', () => {
  const marks = marksOf(10, 5)
  assert.equal(marks.length, 9)
  assert.deepEqual(marks.map((m, i) => (m.show ? i + 1 : 0)).filter(Boolean), [1, 5, 9])
})

test('10 站当前站贴着首尾时不再加标签,免得叠住', () => {
  assert.deepEqual(marksOf(10, 2).filter((m) => m.show).length, 2)
  assert.deepEqual(marksOf(10, 8).filter((m) => m.show).length, 2)
})
