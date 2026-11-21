// F02(2026-11-18):逐字猜词 / 限时打字判错后,服务端回的那句话(错答表命中那条或通用错答)要显示出来;
// 逐字猜词答错不再清空已填的格子,玩家直接改。答案与错答表都不在客户端。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const { hangmanKit } = require('../../pages/play/utils/playkit-questo.js')
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')

function loadHangman() {
  let def = null
  const prevC = global.Component, prevB = global.Behavior
  global.Behavior = (b) => b
  global.Component = (d) => { def = d }
  const file = path.join(ROOT, 'pages/playkit/components/playkit-hangman/index.js')
  delete require.cache[require.resolve(file)]
  require(file)
  global.Component = prevC; global.Behavior = prevB
  return def
}

const SLOTS = [{ i: 0, kind: 'BLANK', ch: '' }, { i: 1, kind: 'BLANK', ch: '' }, { i: 2, kind: 'BLANK', ch: '' }]

test('hangmanKit:没解开时带服务端 lastFeedback,解开后不带', () => {
  assert.equal(hangmanKit({ slots: SLOTS, solved: false, lastFeedback: '前两个字母对了' }).wrongFeedback, '前两个字母对了')
  assert.equal(hangmanKit({ slots: SLOTS, solved: true, lastFeedback: '前两个字母对了' }).wrongFeedback, '')
  assert.equal(hangmanKit({ slots: SLOTS }).wrongFeedback, '')
})

test('逐字猜词答错:保留已填的格子与输入框,不清空重来', () => {
  const def = loadHangman()
  const inst = Object.assign({ data: Object.assign({}, def.data, { show: true, slots: SLOTS, solved: false, reducedMotion: true }),
    setData(p) { Object.assign(this.data, p) }, triggerEvent() {} }, def.methods)
  const key = Object.keys(def.observers).find(k => k.startsWith('show, slots, solved'))
  const observe = (slots, solved) => def.observers[key].call(inst, true, slots, solved, 'keyboard', [])
  observe(SLOTS, false)
  inst.onInput({ detail: { value: 'ABD' } })
  inst.onSubmit()
  // 服务端回执:没解开,槽位回读为玩家补的字
  observe(SLOTS.map((s, i) => Object.assign({}, s, { kind: 'FILLED', ch: 'ABD'[i] })), false)
  assert.equal(inst.data.verdict, 'no')
  assert.equal(inst.data.typedText, 'ABD', '输入框保留')
  const cells = inst.data.groups.flatMap(g => g.cells).map(c => c.ch).join('')
  assert.equal(cells, 'ABD', '格子保留')
  assert.equal(inst.data.canSubmit, true, '改一个字就能再交')
})

test('逐字猜词 / 限时打字:错答回复替换旧兜底句,兜底句句末不带句号', () => {
  const hangman = fs.readFileSync(path.join(ROOT, 'pages/playkit/components/playkit-hangman/index.wxml'), 'utf8')
  assert.match(hangman, /wrongFeedback \|\| '还不对，再看看题面'/)
  assert.doesNotMatch(hangman, /不对，再想想/)
  const typein = fs.readFileSync(path.join(ROOT, 'pages/playkit/components/playkit-typein/index.wxml'), 'utf8')
  assert.match(typein, /wrongFeedback \|\| '没打对，再打一次'/)
  const dispatcher = fs.readFileSync(path.join(ROOT, 'pages/playkit/components/playkit/index.wxml'), 'utf8')
  assert.equal((dispatcher.match(/wrong-feedback="\{\{kit\.wrongFeedback\}\}"/g) || []).length, 2)
})

test('限时打字:服务端 lastFeedback 映射到 kit.wrongFeedback;通过后不带', () => {
  const base = { sessionId: 1, version: 2, status: 'RUNNING' }
  const wrong = pickPlayKit(Object.assign({}, base, { playKit: { typeIn: { title: 't', target: 'ABC', seconds: 10, attempts: 1, lastFeedback: '差一个字母' } } }), new Date())
  assert.equal(wrong.wrongFeedback, '差一个字母')
  const passed = pickPlayKit(Object.assign({}, base, { playKit: { typeIn: { title: 't', target: 'ABC', seconds: 10, attempts: 1, passed: true, lastFeedback: '差一个字母' } } }), new Date())
  assert.equal(passed.wrongFeedback, '')
})

test('逐字猜词字母池(F03)答错:同样显示服务端回复(F02)', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/playkit/components/playkit-hangman/index.wxml'), 'utf8')
  assert.equal((wxml.match(/wrongFeedback \|\| '还不对，再看看题面'/g) || []).length, 2, '键盘与字母池两种输入都显示')
})
