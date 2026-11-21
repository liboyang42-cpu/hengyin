// F03 字母池 / 字符集输入:逐字猜词 inputMode=pool 与打字题 letterPool 共用 utils/letter-pool.js。
// 验:点字填进第一个空格、点已填格退回、清空、提交载荷形状(SUBMIT_HANGMAN {slots} / SUBMIT_QA {input}),
// 分隔符 / 预填格不可点也不占池,错答保留已填字,提示去掉干扰字后池变小。夹具中性(答案 ABC)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const pool = require('../../pages/playkit/utils/letter-pool.js')
const questo = require('../../pages/play/utils/playkit-questo.js')
const { pickPlayKit, serverAction, serverPayload } = require('../../pages/play/utils/playkit-view.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const NOW = new Date(2026, 10, 18, 12)

function loadComponent(rel) {
  let def = null
  const prevC = global.Component, prevB = global.Behavior
  global.Component = (d) => { def = d }
  global.Behavior = (b) => b
  try { delete require.cache[require.resolve(path.join(ROOT, rel))]; require(path.join(ROOT, rel)) } finally { global.Component = prevC; global.Behavior = prevB }
  return def
}

/** 最小组件宿主:data 合并 + setData 后按名单触发 observers(与小程序「setData 了哪个字段就触发哪条」同口径)。 */
function mount(def, props) {
  global.wx = Object.assign({}, global.wx, { vibrateShort() {} })
  const events = []
  const inst = Object.assign({ data: Object.assign({}, def.data, { reducedMotion: true }, props) }, def.methods, {
    triggerEvent(name, detail) { events.push({ name, detail }) },
    selectComponent() { return null },
  })
  inst.setData = (patch) => { Object.assign(inst.data, patch) }
  inst.update = (patch) => {
    Object.assign(inst.data, patch)
    Object.keys(def.observers).forEach((key) => {
      const fields = key.split(',').map((k) => k.trim())
      if (fields.some((f) => Object.prototype.hasOwnProperty.call(patch, f))) def.observers[key].apply(inst, fields.map((f) => inst.data[f]))
    })
  }
  inst.events = events
  return inst
}
const tap = (dataset) => ({ currentTarget: { dataset } })

// 「A-BC」:0 = A 空格,1 = 分隔符,2 = B 预填,3 / 4 空格 —— 可填格只有 0 / 3 / 4
const SLOTS = [{ i: 0, kind: 'BLANK', ch: '' }, { i: 1, kind: 'SEPARATOR', ch: '-' }, { i: 2, kind: 'PREFILLED', ch: 'B' },
  { i: 3, kind: 'BLANK', ch: '' }, { i: 4, kind: 'BLANK', ch: '' }]
const POOL = ['C', 'X', 'A', 'B', 'Y', 'C', 'Z', 'Q']

test('纯函数:点字填第一个空格,分隔符 / 预填格跳过;用掉的那一份置灰;再点同一份不再填', () => {
  let s = pool.sync(null, SLOTS, POOL, null)
  let r = pool.fill(s, SLOTS, POOL, 2)          // A → 格 0
  assert.equal(r.slot, 0); s = r.state
  r = pool.fill(s, SLOTS, POOL, 2)              // 同一份 A 已用
  assert.equal(r.slot, -1)
  r = pool.fill(s, SLOTS, POOL, 3); s = r.state // B → 格 3(跳过分隔符 1 与预填 2)
  assert.equal(r.slot, 3)
  const v = pool.view(s, SLOTS, POOL)
  assert.deepEqual(v.cells.map((c) => c.ch), ['A', '-', 'B', 'B', ''])
  assert.deepEqual(v.cells.map((c) => c.tap), [true, false, false, true, false], '分隔符 / 预填 / 空格不可点')
  assert.deepEqual(v.rows.map((row) => row.keys.length), [7, 1], '每行 7 个')
  assert.deepEqual(v.rows[0].keys.filter((k) => k.used).map((k) => k.ch), ['A', 'B'])
  assert.equal(pool.hangmanPayload(s, SLOTS), null, '没填满不出载荷')
})

test('纯函数:点已填格退回;清空全退回;填满后载荷只带可填格 {slots:{i:字}};qa 拼成 input', () => {
  let s = pool.sync(null, SLOTS, POOL, null)
  ;[2, 3, 0].forEach((k) => { s = pool.fill(s, SLOTS, POOL, k).state })   // A B C
  assert.deepEqual(pool.hangmanPayload(s, SLOTS), { slots: { 0: 'A', 3: 'B', 4: 'C' } })
  assert.equal(pool.joined(s, SLOTS), 'ABC')
  s = pool.giveBack(s, 3)
  assert.deepEqual(pool.view(s, SLOTS, POOL).cells.map((c) => c.ch), ['A', '-', 'B', '', 'C'])
  assert.equal(pool.giveBack(s, 2), s, '预填格点了不变')
  assert.equal(pool.giveBack(s, 3), s, '空格点了不变')
  s = pool.fill(s, SLOTS, POOL, 3).state
  assert.equal(pool.view(s, SLOTS, POOL).cells[3].ch, 'B', '退回后再点,填进最前面那个空格')
  s = pool.clear(s)
  assert.equal(pool.filledCount(s), 0)
  assert.equal(pool.view(s, SLOTS, POOL).rows[0].keys.some((k) => k.used), false)
})

test('纯函数:错答(服务端回原样 FILLED)保留已放的字;去掉干扰字后池变小,池里没了的那份退回;换题从空开始', () => {
  let s = pool.sync(null, SLOTS, POOL, null)
  ;[2, 3, 0].forEach((k) => { s = pool.fill(s, SLOTS, POOL, k).state })
  const after = SLOTS.map((x) => (x.kind === 'BLANK' ? Object.assign({}, x, { kind: 'FILLED', ch: { 0: 'A', 3: 'B', 4: 'C' }[x.i] }) : x))
  s = pool.sync(s, after, POOL, SLOTS)
  assert.deepEqual(pool.hangmanPayload(s, after), { slots: { 0: 'A', 3: 'B', 4: 'C' } }, '错答不清空')
  // 玩家退回一个字后,服务端那一格的 FILLED 没变,重读不能把它又塞回来
  s = pool.giveBack(s, 4)
  s = pool.sync(s, after, POOL, after)
  assert.equal(pool.view(s, after, POOL).cells[4].ch, '')
  // 提示去掉干扰字:新池里没有 C 了(两个 C 都被拿走只是夹具;真实只拿干扰字)
  s = pool.fill(s, after, POOL, 0).state
  const trimmed = ['A', 'B', 'Q']
  s = pool.sync(s, after, trimmed, after)
  assert.equal(pool.view(s, after, trimmed).cells[4].ch, '', '池里没了的字从格子里退掉')
  assert.deepEqual(pool.view(s, after, trimmed).rows[0].keys.map((k) => k.used), [true, true, false])
  // 换题:格子形状不同,不串字
  const other = [{ i: 0, kind: 'BLANK', ch: '' }, { i: 1, kind: 'BLANK', ch: '' }]
  s = pool.sync(s, other, trimmed, after)
  assert.equal(pool.filledCount(s), 0)
})

test('纯函数:服务端揭开的字池里已扣掉 → 只读显示,不可点、不占池、不进载荷', () => {
  const revealed = [{ i: 0, kind: 'FILLED', ch: 'A' }, { i: 1, kind: 'BLANK', ch: '' }, { i: 2, kind: 'BLANK', ch: '' }]
  let s = pool.sync(null, revealed, ['B', 'C', 'Z'], null)
  const v = pool.view(s, revealed, ['B', 'C', 'Z'])
  assert.equal(v.cells[0].ch, 'A'); assert.equal(v.cells[0].tap, false); assert.equal(v.cells[0].locked, true)
  s = pool.fill(s, revealed, ['B', 'C', 'Z'], 0).state
  s = pool.fill(s, revealed, ['B', 'C', 'Z'], 1).state
  assert.deepEqual(pool.hangmanPayload(s, revealed), { slots: { 1: 'B', 2: 'C' } })
  assert.equal(pool.joined(s, revealed), 'ABC')
  // 池里还有那一份时就是普通已填格,玩家可以退回
  const s2 = pool.sync(null, revealed, ['A', 'B', 'C'], null)
  assert.equal(pool.view(s2, revealed, ['A', 'B', 'C']).cells[0].tap, true)
})

test('会话视图:pool 模式把 inputMode / pool 带进 kit;键盘模式照旧', () => {
  const seg = { length: 5, blankCount: 3, solved: false, slots: SLOTS, inputMode: 'pool', pool: POOL, poolRemoved: 0 }
  const kit = pickPlayKit({ sessionId: 's1', version: 1, playKit: { hangman: seg } }, NOW)
  assert.equal(kit.type, 'hangman')
  assert.equal(kit.inputMode, 'pool')
  assert.deepEqual(kit.pool, POOL)
  const plain = questo.hangmanKit({ slots: SLOTS })
  assert.equal(plain.inputMode, 'keyboard')
  assert.deepEqual(plain.pool, [])
  const wxml = read('pages/playkit/components/playkit/index.wxml')
  assert.match(wxml, /input-mode="\{\{kit\.inputMode\}\}" pool="\{\{kit\.pool\}\}"/)
  assert.match(wxml, /letter-pool="\{\{kit\.letterPool\}\}"/)
})

test('逐字猜词组件 pool 模式:点字填、点格退回、清空、提交 {slots};错答后已填的字保留', () => {
  const def = loadComponent('pages/playkit/components/playkit-hangman/index.js')
  const c = mount(def, {})
  c.update({ show: true, slots: SLOTS, solved: false, inputMode: 'pool', pool: POOL })
  assert.equal(c.data.poolOn, true)
  assert.equal(c.data.canSubmit, false)
  c.onPickPool(tap({ k: 2 })); c.onPickPool(tap({ k: 3 }))
  assert.equal(c.data.poolRows[0].keys[2].used, true)
  c.onPickCell(tap({ i: 0, kind: 'BLANK' }))
  const cellsOf = () => c.data.groups.flatMap((g) => g.cells)
  assert.equal(cellsOf().find((x) => x.i === 0).ch, '', '点已填格退回')
  assert.equal(c.data.poolRows[0].keys[2].used, false)
  c.onPickCell(tap({ i: 2, kind: 'PREFILLED' }))
  assert.equal(cellsOf().find((x) => x.i === 2).ch, 'B', '预填格不响应')
  c.onClearPool()
  assert.equal(c.data.canClear, false)
  ;[2, 3, 0].forEach((k) => c.onPickPool(tap({ k })))
  assert.equal(c.data.canSubmit, true)
  c.onSubmit()
  assert.deepEqual(c.events.pop(), { name: 'submit', detail: { slots: { 0: 'A', 3: 'B', 4: 'C' } } })
  assert.equal(serverAction('hangman', 'submit'), 'SUBMIT_HANGMAN')
  assert.deepEqual(serverPayload('hangman', 'submit', { slots: { 0: 'A', 3: 'B', 4: 'C' } }), { slots: { 0: 'A', 3: 'B', 4: 'C' } })
  // 服务端判没解开(回包 FILLED = 刚交的字):已填的字留着,提示「不对」
  const after = SLOTS.map((x) => (x.kind === 'BLANK' ? Object.assign({}, x, { kind: 'FILLED', ch: { 0: 'A', 3: 'B', 4: 'C' }[x.i] }) : x))
  c.update({ slots: after })
  assert.equal(c.data.verdict, 'no')
  assert.deepEqual(cellsOf().filter((x) => x.kind === 'FILLED').map((x) => x.ch), ['A', 'B', 'C'])
  assert.equal(c.data.canSubmit, true)
  // 提示去掉干扰字:重读后池变小
  c.update({ pool: ['A', 'B', 'C', 'Q'] })
  assert.equal(c.data.poolRows[0].keys.length, 4)
  assert.equal(c.data.poolRows[0].keys.filter((k) => k.used).length, 3)
})

test('逐字猜词组件:键盘模式不受影响(没有候选字、系统键盘输入框仍在)', () => {
  const def = loadComponent('pages/playkit/components/playkit-hangman/index.js')
  const c = mount(def, {})
  c.update({ show: true, slots: SLOTS, solved: false, inputMode: 'keyboard', pool: [] })
  assert.equal(c.data.poolOn, false)
  c.onInput({ detail: { value: 'ABC' } })
  c.onSubmit()
  assert.deepEqual(c.events.pop().detail, { slots: { 0: 'A', 3: 'B', 4: 'C' } })
  const wxml = read('pages/playkit/components/playkit-hangman/index.wxml')
  assert.match(wxml, /wx:if="\{\{!solved && poolOn\}\}"/)
  assert.match(wxml, /<block wx:elif="\{\{!solved\}\}">\s*<!--[^>]*-->\s*<input class="hm__in/, '输入框只在非 pool 模式出现')
})

test('打字题字符池:answerLength 个空格 + 候选字,提交 SUBMIT_QA {input};错答保留已放的字', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const seg = { mode: 'TYPE', title: '示例站点的名字', attempts: 0, letterPool: { chars: ['C', 'A', 'Q', 'B', 'Z'], answerLength: 3, removed: 0 } }
  const kit = pickPlayKit({ sessionId: 's2', version: 1, status: 'RUNNING', playKit: { qa: seg } }, NOW)
  assert.deepEqual(kit.letterPool, { chars: ['C', 'A', 'Q', 'B', 'Z'], answerLength: 3 })
  assert.equal(pickPlayKit({ sessionId: 's2', version: 1, playKit: { qa: { mode: 'TYPE', title: 'x' } } }, NOW).letterPool, null)
  assert.equal(questo.qaLetterPool({ mode: 'PICK', letterPool: seg.letterPool }), null, '只有打字题才有字符池')

  const def = loadComponent('pages/playkit/components/playkit-qa/index.js')
  const c = mount(def, { mode: 'type', judge: true, attempts: 0, passed: false, finished: false, feedbackText: '', authoredOutcome: '', sessionId: 's2', nodeId: '7' })
  c.update({ show: true, options: [], attempts: 0, letterPool: kit.letterPool })
  assert.equal(c.data.poolOn, true)
  assert.equal(c.data.poolCells.length, 3)
  assert.equal(c.data.ctaDisabled, true)
  ;[1, 3, 0].forEach((k) => c.onPickPool(tap({ k })))
  assert.equal(c.data.ctaDisabled, false)
  c.onPoolSlot(tap({ i: 1 }))
  assert.equal(c.data.poolCells[1].ch, '')
  assert.equal(c.data.ctaDisabled, true)
  c.onPickPool(tap({ k: 3 }))
  c.onCta()
  assert.deepEqual(c.events.pop(), { name: 'submit', detail: { mode: 'type', input: 'ABC' } })
  assert.equal(serverAction('qa', 'submit'), 'SUBMIT_QA')
  assert.deepEqual(serverPayload('qa', 'submit', { mode: 'type', input: 'ABC' }), { input: 'ABC' })
  // 服务端判错:attempts 涨了 —— 描红,字留着,填满就能直接再交
  c.update({ attempts: 1 })
  assert.equal(c.data.typeWrong, true)
  assert.deepEqual(c.data.poolCells.map((x) => x.ch), ['A', 'B', 'C'])
  assert.equal(c.data.ctaDisabled, false)
  c.onClearPool()
  assert.deepEqual(c.data.poolCells.map((x) => x.ch), ['', '', ''])
  // 去掉干扰字后重读:池变小
  c.update({ letterPool: { chars: ['C', 'A', 'B'], answerLength: 3 } })
  assert.equal(c.data.poolRows[0].keys.length, 3)
  const wxml = read('pages/playkit/components/playkit-qa/index.wxml')
  assert.match(wxml, /<block wx:if="\{\{mode === 'type' && poolOn\}\}">/)
  assert.match(wxml, /aria-label="候选字 \{\{item\.ch\}\}/)
})

test('无障碍与样式:候选字 / 清空命中区 ≥ 44px;颜色只读皮肤变量,不新造色', () => {
  const css = read('pages/playkit/style/letter-pool.wxss')
  const rule = (sel) => (css.match(new RegExp('\\' + sel + ' \\{([^}]*)\\}')) || [])[1] || ''
  assert.match(rule('.lp__key'), /width: 88rpx;[\s\S]*height: 88rpx;/)
  assert.match(rule('.lp__clear'), /min-height: 88rpx;/)
  assert.doesNotMatch(css, /#[0-9a-fA-F]{3,8}\b/, '不写死颜色')
  for (const rel of ['pages/playkit/components/playkit-hangman/index.wxss', 'pages/playkit/components/playkit-qa/index.wxss']) {
    assert.match(read(rel), /@import "\/pages\/playkit\/style\/letter-pool\.wxss";/, rel)
  }
  const hm = read('pages/playkit/components/playkit-hangman/index.wxml')
  assert.match(hm, /bindtap="onClearPool"[^>]*aria-label="清空"/)
  assert.match(hm, /aria-label="候选字 \{\{item\.ch\}\}/)
})

test('提示 POOL_TRIM:面板可用,用完重读会话视图(advanced-game refreshState)', async () => {
  const panel = require('../../pages/play/utils/play-help-panel.js')
  const view = panel.project({ enabled: true, layers: [{ level: 1, type: 'POOL_TRIM', usable: true, free: true }] })
  assert.equal(view.rows[0].state, 'free')
  assert.equal(view.rows[0].label, '去掉干扰字')
  const storage = new Map()
  global.wx = Object.assign({}, global.wx, { getStorageSync: (k) => storage.get(k) || '', setStorageSync: (k, v) => storage.set(k, v) })
  global.getApp = () => ({ getUserID: () => '9' })
  let refreshed = 0
  const sent = []
  const send = (url, method, data) => { sent.push(url); return Promise.resolve(url.endsWith('/use')
    ? { code: 200, data: { level: 1, type: 'POOL_TRIM', content: '已去掉 3 个干扰字' } } : { code: 200, data: { enabled: true, layers: [{ level: 1, type: 'POOL_TRIM', usable: true, unlocked: true, free: true }] } }) }
  const host = Object.assign({ data: { helpPanel: Object.assign({}, panel.data.helpPanel, { rows: view.rows }), advancedPlay: {}, game: { nodeId: 5 } },
    _routeState: { sessionId: 11 }, _helpEpoch: 1, setData(p) { Object.assign(this.data, p) },
    _advancedGameComponent: () => ({ refreshState() { refreshed += 1; return Promise.resolve(true) } }) }, panel.methods(send))
  await host._helpUse(view.rows[0], false)
  assert.equal(refreshed, 1)
  assert.equal(host.data.helpPanel.rows[0].content, '已去掉 3 个干扰字')
  delete global.getApp
})
