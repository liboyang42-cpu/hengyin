// r2-join(2026-11-10 业主第二轮):落地页「怎么玩」模式卡、队伍席位条、通行证结算摘要与玩家文案去编号。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const join = require('../../subpackageJourney/utils/join-view.js')
const seats = require('../../pages/team/utils/team-seats.js')
const mv = require('../../subpackageMember/utils/market-view.js')
const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('一起玩 = SHARED_TEAM,全队一个分数只在计分全队共享时出现', () => {
  const r = join.modeTiles({ configState: 'PUBLISHED', choices: [
    { key: 'SHARED_TEAM', progressScope: 'PER_TEAM', hintScope: 'PER_TEAM', scoreScope: 'PER_TEAM', available: true },
    { key: 'INDEPENDENT', progressScope: 'PER_MEMBER', hintScope: 'PER_MEMBER', scoreScope: 'PER_MEMBER', competition: true, available: true }
  ] })
  assert.equal(r.tiles[0].name, '一起玩')
  assert.equal(r.tiles[0].sub, '全队一个分数')
  assert.deepEqual(r.tiles[0].rules.map(x => x.k + x.v), ['提示全队共享', '进度全队共享', '计分全队共享'])
  assert.equal(r.tiles[1].name, '各自比')
  assert.equal(r.tiles[1].sub, '结束后排名')
  assert.equal(r.active, 'SHARED_TEAM')
})

test('没有 competition 事实时不写「各自比 / 结束后排名」', () => {
  const r = join.modeTiles({ configState: 'PUBLISHED', choices: [
    { key: 'INDEPENDENT', progressScope: 'PER_MEMBER', hintScope: 'PER_MEMBER', scoreScope: 'PER_MEMBER', available: true }] })
  assert.equal(r.tiles[0].name, '各自玩')
  assert.equal(r.tiles[0].sub, '', '规则行已写计分各自,卡上不重复')
})

test('不可用模式带原因、默认选中第一个可用;配置不可用整块不画', () => {
  const r = join.modeTiles({ configState: 'PUBLISHED', choices: [
    { key: 'SHARED_TEAM', available: false, unavailableReason: 'TEAM_MODE_DISABLED' },
    { key: 'INDEPENDENT', scoreScope: 'PER_MEMBER', available: true }] })
  assert.equal(r.tiles[0].reason, '本主题未开启组队')
  assert.equal(r.active, 'INDEPENDENT')
  assert.deepEqual(r.tiles[0].rules, [], '读不到口径不编规则')
  assert.equal(join.modeTiles({ configState: 'UNAVAILABLE', choices: [] }), null)
  assert.equal(join.modeTiles(null), null)
})

test('落地页不画实时/随时(服务端没有该事实),模式卡点选不写服务端', () => {
  const wxml = read('subpackageJourney/pages/landing/index.wxml')
  assert.doesNotMatch(wxml, /实时|随时/)
  const js = read('subpackageJourney/pages/landing/index.js')
  const onMode = js.slice(js.indexOf('onMode(e)'), js.indexOf('onPrimary()'))
  assert.doesNotMatch(onMode, /api\.|sendRequest|navigateTo/)
})

test('席位条:空位优先 seats.available,其次 max-joined;都读不到不画', () => {
  assert.deepEqual(seats.openSeats({ maxMembers: 4, joinedCount: 2 }, { available: 1 }), [0])
  assert.deepEqual(seats.openSeats({ maxMembers: 4, joinedCount: 2 }, null), [0, 1])
  assert.deepEqual(seats.openSeats({ maxMembers: null, joinedCount: 2 }, {}), [])
  assert.deepEqual(seats.openSeats(null, null), [])
  assert.equal(seats.openSeats({ maxMembers: 40, joinedCount: 0 }, null).length, 12)
  assert.deepEqual(seats.joinedSeats({ joinedCount: 3 }), [0, 1, 2])
  assert.deepEqual(seats.joinedSeats({}), [])
})

test('席位标签不重复「还可加入」,票已失效标红', () => {
  const chips = seats.seatChips({ paidOccupied: 0, paidTicketMissing: 2, pendingApplications: 1, available: 2 })
  assert.deepEqual(chips.map(c => c.key), ['paid', 'missing', 'pending'])
  assert.equal(chips[1].bad, true)
})

test('玩家文案去掉内部编号,其余原文不改', () => {
  assert.equal(mv.playerText('商品覆盖的主题非平台自营,拒绝代收 topicId=990014'), '商品覆盖的主题非平台自营,拒绝代收')
  assert.equal(mv.playerText('积分不足'), '积分不足')
  assert.equal(mv.playerText('topicId=1'), '')
  assert.equal(mv.playerText(null), '')
})

test('结算摘要只列报价里的方式;现金关闭写原因、不出现金按钮', () => {
  const q = { availablePayModes: [2], totalCashAmount: 99, totalPointsAmount: 990, cashUnavailableReason: 'CASH_DISABLED',
    quoteSign: 's', quoteExpiresAt: '2026-11-10T10:00:00+08:00' }
  const rows = mv.checkoutRows(q, null)
  assert.deepEqual(rows.map(r => r.key), ['cash', 'points'])
  assert.equal(rows[0].off, true)
  assert.equal(rows[0].note, '现金支付暂未开通')
  assert.deepEqual(mv.purchaseView(q).payModes.map(m => m.mode), [2])
  assert.deepEqual(mv.checkoutRows({ availablePayModes: [1], totalCashAmount: 99 }, null).map(r => r.key), ['cash'])
  assert.deepEqual(mv.checkoutRows(null, { price: '¥99.00' }), [{ key: 'price', k: '价格', v: '¥99.00' }])
})

test('购买被拒时按 playerText 显示,不把 topicId 给玩家看', () => {
  const js = read('subpackageMember/market/pass/index.js')
  assert.match(js, /mv\.playerText\(res && res\.msg\)/)
  assert.doesNotMatch(js, /\(\(res && res\.msg\) \|\| mv\.reasonText/)
})

test('礼品领取页用礼物图标,不再用通用空态插画/警告三角', () => {
  const wxml = read('subpackageMember/market/gift/index.wxml')
  assert.doesNotMatch(wxml, /<cy-empty/)
  assert.match(wxml, /name="\{\{state === 'claimed' \? 'check' : 'gift'\}\}"/)
})
