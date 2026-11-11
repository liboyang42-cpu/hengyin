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

test('P-2 确认订单/购买弹层:不再把门票写成「通行证」;没有可买票种时不落到自玩结账', () => {
  const { checkoutView } = require('../../pages/topic/utils/checkout-view.js')
  const v = checkoutView({ info: { selfPlayPrice: 39 }, quoteState: 'ready', quote: { payAmount: 39 } })
  assert.equal(v.single.title, '自玩门票')
  assert.deepEqual(v.bill.map((r) => r.k), ['自玩门票 × 1'])
  const m = require('../../pages/topic/utils/topic-motion.js')
  assert.equal(m.buyOptions({ selfPlay: 1, selfPlayPrice: 9 })[0].name, '自玩门票')
  assert.deepEqual(m.buyOptions({ selfPlay: 0, activityList: [] }), [], '城市定向无场次:没有票种')
  const js = read('pages/topic/index/index.js')
  assert.match(js, /if \(!opt\) return; \/\/ x-fix2 P-2/)
  const visible = noComments(read('pages/topic/index/index.wxml'))
  assert.doesNotMatch(visible, /自玩通行证|主题通行证/)
  assert.doesNotMatch(read('pages/topic/components/cy/pass-reveal/index.js'), /value: '自玩通行证'/)
})

test('L-1 收集册(勋章/邮票/藏品)玩家侧一律深色:不再挂 theme-light 逃生口,状态栏白字', () => {
  for (const p of ['badge-wall', 'stamp-album', 'object-cards']) {
    const base = 'subpackageP3/pages/' + p + '/index/index'
    assert.doesNotMatch(noComments(read(base + '.wxml')), /theme-light/, p)
    assert.doesNotMatch(read(base + '.wxss'), /^\.theme-light /m, p)
    const json = JSON.parse(read(base + '.json'))
    assert.equal(json.navigationBarTextStyle, 'white', p)
    assert.notEqual(String(json.backgroundColor).toLowerCase(), '#ffffff', p)
  }
  assert.doesNotMatch(read('subpackageP3/components/album-aura/index.wxss'), /#FAFBFD/i)
  assert.doesNotMatch(read('subpackageP3/components/album-intro/index.wxss'), /#FAFBFD/i)
  assert.doesNotMatch(read('subpackageP3/pages/object-cards/index/index.wxss'), /#FAFBFD/i)
})

test('M-2 勋章墙:单枚图片失败只降级这一枚(徽记/星标),重新载入时清空', () => {
  const wxml = read('subpackageP3/pages/badge-wall/index/index.wxml')
  assert.match(wxml, /wx:if="\{\{item\.iconUrl && !iconBroken\[item\._idx\]\}\}"[^>]*binderror="onIconError"/)
  assert.match(wxml, /<cy-icon wx:else name="star" size="64" \/>/)
  const js = read('subpackageP3/pages/badge-wall/index/index.js')
  assert.match(js, /badges: list, iconBroken: \{\},/)
  // 行为:把 onIconError 拿出来跑一次
  const src = js.match(/  onIconError\(e\) \{[\s\S]*?\n  \},/)[0]
  const fn = new Function('return {' + src + '}')().onIconError
  const page = { data: { iconBroken: {} }, setData(d) { for (const k of Object.keys(d)) { const [, i] = k.split('.'); this.data.iconBroken[i] = d[k] } } }
  fn.call(page, { currentTarget: { dataset: { idx: 3 } } })
  assert.deepEqual(page.data.iconBroken, { 3: true })
  fn.call(page, { currentTarget: { dataset: {} } })
  assert.deepEqual(page.data.iconBroken, { 3: true })
})
