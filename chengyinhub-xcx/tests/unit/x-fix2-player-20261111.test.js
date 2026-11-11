// x-fix2(2026-11-11 验收问题单 ACCEPT-ISSUES-20261111 玩家侧)回归
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '')

test('P-1 已截止主题:头图按钮只讲「已截止」,不再给报名/自玩/解锁入口', () => {
  const m = require('../../pages/topic/utils/topic-motion.js')
  const now = '2026-11-11 12:00:00'
  assert.deepEqual(m.buildHeroCta({ lifecycle: 0, endDate: '2026-10-13' }, { now }), { kind: 'closed', text: '已截止' })
  assert.deepEqual(m.buildHeroCta({ selfPlay: 1, endDate: '2026-12-01', saleEndAt: '2026-11-01 00:00:00' }, { now, priceText: '¥9' }), { kind: 'closed', text: '已截止' })
  assert.equal(m.buildHeroCta({ endDate: '2026-12-01' }, { now }).kind, 'signup', '未截止照旧报名')
  assert.equal(m.buildHeroCta({ endDate: '2026-10-13' }, { now, isJoin: 1 }).kind, 'joined', '已购仍去票夹')
  assert.equal(m.buildHeroCta({ lifecycle: 1, endDate: '2026-10-13' }, { now }).kind, 'locked')
  assert.equal(m.topicSaleClosed({}, now), false, '没有截止事实不算截止')
  const wxml = read('pages/topic/index/index.wxml')
  assert.match(wxml, /class="hero-cta \{\{heroCta\.kind === 'locked' \|\| heroCta\.kind === 'closed' \? 'is-off'/)
  assert.match(wxml, /topic-story-paywall__cta" wx:if="\{\{heroCta\.kind !== 'closed'\}\}"/)
  assert.match(read('pages/topic/index/index.js'), /if \(kind === 'closed'\) return toast\('主题已截止'\)/)
})
