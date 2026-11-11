'use strict'
/* qx-green(2026-11-10):round-2 的 20 组 @keyframes 改成 transition 分段重放(MOTION.md「不新增 @keyframes」)。
 * beats() 是 utils/page-motion.js 里的分段驱动:依次把 data[key] 设成各段的值,每段由样式里的 transition 补间。 */
const assert = require('node:assert/strict')
const test = require('node:test')
const path = require('node:path')

const pm = require(path.join(__dirname, '../../utils/page-motion.js'))

function fakePage() {
  const page = { data: {}, log: [], setData(d) { Object.assign(this.data, d); this.log.push(Object.assign({}, d)) } }
  return page
}

test('beats:按段依次换值,times 播完停在最后一段;开播前停在 rest', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = fakePage()
  pm.beats(page, 'b', [[1, 100], [2, 100]], { delay: 50, times: 2, rest: 0 })
  assert.equal(page.data.b, 0, '开播前是 rest')
  t.mock.timers.tick(50); assert.equal(page.data.b, 1)
  t.mock.timers.tick(100); assert.equal(page.data.b, 2)
  t.mock.timers.tick(100); assert.equal(page.data.b, 1, '第二遍')
  t.mock.timers.tick(100); assert.equal(page.data.b, 2)
  t.mock.timers.tick(1000); assert.equal(page.data.b, 2, '两遍播完不再动')
  assert.deepEqual(page.log.map((d) => d.b), [0, 1, 2, 1, 2])
})

test('beats:不给 times 一直循环,stop() 与页面卸载都能停住', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = fakePage()
  const stop = pm.beats(page, 'g', [[0, 34], [1, 2366]], { rest: 1 })
  t.mock.timers.tick(0); t.mock.timers.tick(34); t.mock.timers.tick(2366); t.mock.timers.tick(34)
  assert.deepEqual(page.log.map((d) => d.g), [1, 0, 1, 0, 1])
  stop()
  t.mock.timers.tick(10000)
  assert.equal(page.log.length, 5, 'stop 之后不再 setData')
  const other = fakePage()
  pm.beats(other, 'g', [[0, 10], [1, 10]])
  other._unloaded = true
  t.mock.timers.tick(100)
  assert.deepEqual(other.log.map((d) => d.g), [0], '卸载后不再动')
})

test('beats:减少动态效果时只停在 rest,不起计时器', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const page = fakePage()
  page.data.reducedMotion = true
  pm.beats(page, 'g', [[0, 34], [1, 2366]], { rest: 1 })
  t.mock.timers.tick(10000)
  assert.deepEqual(page.log.map((d) => d.g), [1])
})

test('游戏主题库的公开目录口:subpackageMember 用本分包逐字副本(分包不互引),与 pages/topic 原件一字不差', () => {
  const fs = require('node:fs')
  const root = path.join(__dirname, '../..')
  const canonical = fs.readFileSync(path.join(root, 'pages/topic/utils/public-catalog.js'), 'utf8')
  const copy = fs.readFileSync(path.join(root, 'subpackageMember/utils/public-catalog.js'), 'utf8')
  assert.equal(copy, canonical, '改 pages/topic/utils/public-catalog.js 时同步副本')
  assert.match(fs.readFileSync(path.join(root, 'subpackageMember/utils/pass-library.js'), 'utf8'), /require\('\.\/public-catalog\.js'\)/)
})
