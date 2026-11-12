// DEC-UI-2:D-76 / D-87 / D-112 / D-116(业主 2026-11-11)的合同。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const { passView } = require('../../subpackageMember/utils/platform-pass.js')

test('D-87 添加栏分割线用自绘 divider 图标(与其余图标同 SVG 描边),不再画短横', () => {
  assert.match(read('pages/publish/fabu/index.js'), /kind: 'divider', label: '分割线', icon: 'divider'/)
  assert.doesNotMatch(read('pages/publish/fabu/index.wxml'), /sx-add__rule/)
  assert.doesNotMatch(read('pages/publish/fabu/index.wxss'), /sx-add__rule/)
  const svg = read('../design/icons/coolicons/divider.svg')
  assert.match(svg, /viewBox="0 0 24 24"/); assert.match(svg, /stroke-width="2"/); assert.match(svg, /stroke-linecap="round"/)
  assert.match(read('components/cy/icon/icons.wxss'), /\.cyi--divider \{/)
})

test('D-76 官方路线两页有入口:漫游护照「官方路线」大卡 → official-routes → official-route', () => {
  const js = read('pages/roam/index.js')
  assert.match(js, /key: 'routes', icon: 'map', title: '官方路线'/)
  assert.match(js, /url: '\/subpackageRoam\/official-routes\/index'/)
  assert.match(read('subpackageRoam/official-routes/index.js'), /\/subpackageRoam\/official-route\/index\?id=/)
})

test('D-116 钥匙开场预下载序列帧,且 preloadRule 提前拉 subpackagePass,帧图不进主包', () => {
  const js = read('subpackagePass/reveal/index.js')
  assert.match(js, /wx\.getImageInfo\(\{ src, complete:/)
  assert.match(js, /this\._warm\(full\)/)
  const app = JSON.parse(read('app.json'))
  for (const page of ['subpackageMember/market/membership/index', 'subpackageMember/market/passes/index']) {
    assert.deepEqual(app.preloadRule[page].packages, ['subpackagePass'])
  }
  const FR = require('../../subpackagePass/reveal/frames.js')
  for (const f of FR.intro.concat(FR.idle)) assert.ok(f.src.startsWith('/subpackagePass/'))
  assert.equal(fs.existsSync(path.join(ROOT, 'images/frames')), false)
})

test('D-112 通行证卡:年卡/月卡、剩余天数进度条、同行标志、角标只画服务端给的', () => {
  const ov = { enabled: true, plans: [], channels: {}, holding: { active: true, validTo: '2027-01-01 00:00:00', planCode: 'MONTH', planLabel: '月卡', daysTotal: 30, daysRemaining: 4, teammateBenefit: false, badge: 'EXPIRING' } }
  const v = passView(ov, '', 'ios')
  assert.equal(v.card.planLabel, '月卡')
  assert.deepEqual(v.card.progress, { left: 4, total: 30, pct: 13, text: '剩余 4 天 / 共 30 天' })
  assert.equal(v.card.badge, '即将到期'); assert.equal(v.card.teammate, '')
  const t = passView({ enabled: true, plans: [], holding: { active: true, planLabel: '年卡', daysTotal: 365, daysRemaining: 999, teammateBenefit: true } }, '', 'ios')
  assert.equal(t.card.progress.left, 365); assert.equal(t.card.teammate, '同行可用')
  const none = passView({ enabled: true, plans: [], holding: { active: false } }, '', 'ios')
  assert.deepEqual(none.card, { planLabel: '', progress: null, badge: '', teammate: '' })
  const wxml = read('subpackageMember/market/membership/index.wxml')
  assert.match(wxml, /view\.card\.progress/); assert.match(wxml, /view\.card\.planLabel/)
})
