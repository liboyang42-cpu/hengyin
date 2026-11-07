'use strict'

// C-19(2026-11-02 walk-fix):俱乐部活动卡原来用主题日期范围的起点判「已开始」,场次还没开就说已开始。
// 现在优先用 /api/club/topics 富化的 nextSessionStart(进行中的那场,否则最近一场未开始的)。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const src = fs.readFileSync(path.join(ROOT, 'pages/club/detail/index.js'), 'utf8')

function extract(name) {
  const m = new RegExp('\\nfunction ' + name + '\\([\\s\\S]*?\\n\\}\\n').exec(src)
  assert.ok(m, '缺 ' + name)
  return m[0]
}

function load() {
  const { toTimestamp } = require(path.join(ROOT, 'utils/datetime.js'))
  const body = extract('topicStatusOf')
  return new Function('toTimestamp', body + '\nreturn topicStatusOf')(toTimestamp)
}

test('主题日期已开始、但本俱乐部场次还没开:显示未开始', () => {
  const topicStatusOf = load()
  const future = new Date(Date.now() + 3 * 3600 * 1000)
  const pad = (n) => String(n).padStart(2, '0')
  const sh = new Date(future.getTime() + 8 * 3600 * 1000)
  const text = sh.getUTCFullYear() + '-' + pad(sh.getUTCMonth() + 1) + '-' + pad(sh.getUTCDate()) + ' ' + pad(sh.getUTCHours()) + ':' + pad(sh.getUTCMinutes()) + ':00'
  const result = topicStatusOf({ startDate: '2020-01-01', nextSessionStart: text })
  assert.equal(result.statusText, '未开始')
  // 没有场次富化时保持旧行为(按主题日期)
  assert.equal(topicStatusOf({ startDate: '2020-01-01' }).statusText, '已开始')
})

test('卡片时间文案也用场次时间', () => {
  assert.match(src, /eventTimeText\(t\.nextSessionStart \|\| t\.startDate\)/)
})
