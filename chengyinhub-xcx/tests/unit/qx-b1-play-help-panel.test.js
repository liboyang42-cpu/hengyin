// qx-b1 UG-05..09:提示阶梯面板只消费服务端事实;同号核对、跳过与明细不本地算分。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const store = {}
global.wx = { getStorageSync: k => store[k], setStorageSync: (k, v) => { store[k] = JSON.parse(JSON.stringify(v)) } }
let member = 7
global.getApp = () => ({ getUserID: () => member })
const modalCalls = []
require.cache[require.resolve('../../utils/modal.js')] = { exports: { show: opts => { modalCalls.push(opts); opts.success({ confirm: true }) } } }
const help = require('../../pages/play/utils/play-help-panel.js')

const view = (over = {}) => Object.assign({ runId: 55, nodeId: 11, enabled: true, remainingFreeUses: 2, layers: [
  { level: 1, type: 'TEXT', usable: true, unlocked: false, free: true, costPoints: 0 },
  { level: 2, type: 'LETTER', usable: true, unlocked: false, free: false, costPoints: 20 },
  { level: 3, type: 'LOCATION', usable: false, unlocked: false, free: false, costPoints: 0, reason: 'LOCATION_TOOLS_NOT_WIRED' },
  { level: 4, type: 'SKIP', usable: true, unlocked: false, free: true, costPoints: 0 },
] }, over)

function page(responses) {
  const sent = []
  const p = Object.assign({ data: Object.assign({ game: { nodeId: 11 }, canHint: true, canReveal: true }, JSON.parse(JSON.stringify(help.data))),
    _routeState: { sessionId: '55' },
    setData(patch) { for (const k of Object.keys(patch)) { if (k.includes('.')) { const [a, b] = k.split('.'); this.data[a] = Object.assign({}, this.data[a], { [b]: patch[k] }) } else this.data[k] = patch[k] } },
  }, help.methods((url, method, data) => { sent.push({ url, method, data }); const r = responses.shift(); return r instanceof Error ? Promise.reject(r) : Promise.resolve(r) }))
  return { p, sent }
}

test('层列表投影:三态 + 已解锁重看 + 需先看上一层;LOCATION/未知类型只显示不可用;跳过单列', () => {
  const v = help.project(view())
  assert.deepEqual(v.rows.map(r => [r.level, r.state, r.action]), [[1, 'free', '免费'], [2, 'gated', '需先看上一层'], [3, 'unavailable', '暂不可用']])
  assert.deepEqual(v.skip, { level: 4 })
  assert.equal(v.freeLeft, 2)
  const after = help.project(view({ layers: [{ level: 1, type: 'TEXT', usable: true, unlocked: true, free: true, costPoints: 0 },
    { level: 2, type: 'LETTER', usable: true, unlocked: false, free: false, costPoints: 20 }] }))
  assert.deepEqual(after.rows.map(r => [r.state, r.action]), [['review', '重看'], ['cost', '20 积分']])
})

test('没有阶梯或没下发计数器:不出面板 / 不显示 0', () => {
  assert.equal(help.project({ enabled: false, layers: [] }).enabled, false)
  assert.equal(help.project(view({ remainingFreeUses: null })).freeLeft, null, '未知不是 0')
  assert.equal(help.project(view({ remainingFreeUses: 0 })).freeLeft, 0, '服务端说 0 才是 0')
})

test('有阶梯时新旧入口互斥;读不到时旧入口照旧', async () => {
  let t = page([{ code: 200, data: view() }])
  assert.equal(await t.p.loadHelpLadder(), true)
  assert.deepEqual(t.sent[0], { url: '/api/play/help', method: 'GET', data: { runId: '55', nodeId: '11' } })
  assert.equal(t.p.data.canHint, false); assert.equal(t.p.data.canReveal, false)
  t = page([{ code: 500 }])
  assert.equal(await t.p.loadHelpLadder(), false)
  assert.equal(t.p.data.canHint, true, '读失败不动旧提示')
})

test('使用:确认后带 requestId;未知保留同号「继续核对」,重发同号;成功只显示服务端内容与扣减', async () => {
  for (const k of Object.keys(store)) delete store[k]
  const t = page([{ code: 200, data: view({ layers: [{ level: 1, type: 'TEXT', usable: true, unlocked: false, free: false, costPoints: 20 }] }) },
    new Error('net'),
    { code: 200, data: { runId: 55, nodeId: 11, level: 1, type: 'TEXT', content: '看门牌', applied: true, alreadyUnlocked: false, chargedPoints: 20, balanceAfter: 80 } },
    { code: 200, data: view({ remainingFreeUses: 0, layers: [{ level: 1, type: 'TEXT', usable: true, unlocked: true, free: true, costPoints: 0 }] }) }])
  await t.p.loadHelpLadder(); t.p.openHelpPanel()
  t.p.tapHelpRow({ currentTarget: { dataset: { level: 1 } } }); await new Promise(r => setImmediate(r))
  assert.equal(modalCalls.at(-1).content, '查看将消耗 20 积分')
  const firstId = t.sent[1].data.requestId
  assert.match(firstId, /^[A-Za-z0-9]{16,40}$/)
  assert.equal(t.p.data.helpPanel.rows[0].pending, true)
  assert.equal(t.p.data.helpPanel.rows[0].content, null, '未知不渲染任何内容')
  const confirmsBefore = modalCalls.length
  await t.p.tapHelpRow({ currentTarget: { dataset: { level: 1 } } })
  assert.equal(modalCalls.length, confirmsBefore, '继续核对不再弹扣费确认')
  assert.equal(t.sent[2].data.requestId, firstId, '同号核对,不换号')
  await new Promise(r => setImmediate(r))
  const row = t.p.data.helpPanel.rows[0]
  assert.equal(row.content, '看门牌'); assert.equal(row.charge, '消耗 20 积分 · 余 80'); assert.equal(row.state, 'review')
  assert.equal(t.p.data.helpPanel.freeLeft, 0, '计数器重读服务端,不本地减')
})

test('积分不足:不渲染内容,显示「积分不足，本层未解锁」并释放请求号', async () => {
  for (const k of Object.keys(store)) delete store[k]
  const t = page([{ code: 200, data: view({ layers: [{ level: 1, type: 'TEXT', usable: true, unlocked: false, free: false, costPoints: 20 }] }) },
    { code: 400, msg: '积分不足', reasonCode: 'POINTS_INSUFFICIENT' }])
  await t.p.loadHelpLadder()
  t.p.tapHelpRow({ currentTarget: { dataset: { level: 1 } } }); await new Promise(r => setImmediate(r))
  assert.equal(t.p.data.helpPanel.rows[0].error, '积分不足，本层未解锁')
  assert.equal(t.p.data.helpPanel.rows[0].content, null)
  assert.deepEqual(store['play-help-pending:v1'], {})
})

test('跳过:只在服务端给 SKIP 层时可点;确认文案固定;成功后交给页面收起,不放庆祝', async () => {
  for (const k of Object.keys(store)) delete store[k]
  const t = page([{ code: 200, data: view() },
    { code: 200, data: { skipped: true, assisted: true, score: 0, helpReceipt: { scoreState: 'SCORED', baseScore: 100, bonus: 0, helpDeduction: 0, skipDeduction: 100, netScore: 0, pointsCharged: 0, skipped: true, assisted: true, levels: [] } } }])
  let skipped = null
  t.p.onHelpSkipped = (nodeId, body) => { skipped = [nodeId, body.skipped] }
  await t.p.loadHelpLadder()
  t.p.tapHelpSkip(); await new Promise(r => setImmediate(r))
  assert.equal(modalCalls.at(-1).content, '本题记 0 分并标辅助完成')
  assert.equal(t.sent[1].url, '/api/play/help/skip')
  assert.match(t.sent[1].data.requestId, /^[A-Za-z0-9]{16,40}$/)
  assert.deepEqual(skipped, ['11', true])
  assert.equal(t.p.data.helpPanel.receipt.lines[0].v, '跳过 · 记 0 分 · 辅助完成')
  const noSkip = page([{ code: 200, data: view({ layers: [{ level: 1, type: 'TEXT', usable: true, unlocked: false, free: true, costPoints: 0 }] }) }])
  await noSkip.p.loadHelpLadder()
  assert.equal(noSkip.p.data.helpPanel.skip, null)
  noSkip.p.tapHelpSkip()
  assert.equal(noSkip.sent.length, 1, '服务端没给跳过层就不发')
})

test('分数明细:NOT_SCORED 写「本题不计分」不写 0 分;积分与解谜分分列;缺明细不补 0 行', () => {
  const ns = help.receiptView({ scoreState: 'NOT_SCORED', baseScore: null, netScore: null, pointsCharged: 20, levels: [{ level: 1, type: 'TEXT', free: false, pointsCharged: 20 }] })
  assert.deepEqual(ns.lines.map(l => l.k + '=' + l.v), ['解谜分=本题不计分', '提示消耗积分=20', '第 1 层 · 文字提示=20 积分'])
  assert.ok(!ns.lines.some(l => /^0( 分)?$/.test(l.v)))
  const scored = help.receiptView({ scoreState: 'SCORED', baseScore: 100, bonus: 0, helpDeduction: 30, skipDeduction: 0, netScore: 70, pointsCharged: 0 })
  assert.deepEqual(scored.lines.map(l => l.k + '=' + l.v), ['本题解谜分=100', '提示扣分=-30', '本题得分=70', '明细=明细待补'])
  assert.equal(help.receiptView(null), null)
})

test('页面接线:任务页进入时读阶梯;旧提示入口与新面板互斥;面板沿用 cy-sheet 半屏', () => {
  const root = path.join(__dirname, '../..')
  const js = fs.readFileSync(path.join(root, 'pages/play/index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(root, 'pages/play/index.wxml'), 'utf8')
  const sheet = fs.readFileSync(path.join(root, 'pages/play/play-help.wxml'), 'utf8')
  assert.match(js, /if \(!node\.done\) this\.loadHelpLadder\(\)/)
  assert.match(wxml, /wx:if="\{\{helpPanel\.enabled\}\}" bindtap="openHelpPanel"/)
  assert.match(wxml, /wx:elif="\{\{canHint\}\}" bindtap="useHint"/)
  assert.match(wxml, /canReveal && !helpPanel\.enabled/)
  assert.match(sheet, /<cy-sheet show="\{\{helpPanel\.show\}\}"[^>]*fixed="half"/)
  assert.ok(!/0 分<\/text>/.test(sheet))
})
