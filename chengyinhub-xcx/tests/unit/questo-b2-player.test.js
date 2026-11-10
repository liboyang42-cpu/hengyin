// qx-b2 · Questo 新玩法玩家端(UG-01 逐字猜词 / UG-02 图片选项 / UG-03·04 指认结案 / UG-10 谜面变换)
// 只验「服务端事实 → 组件 props」与提交载荷:不判对错、不存答案、不推断提前或成功。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const questo = require('../../pages/play/utils/playkit-questo.js')
const { pickPlayKit, serverAction, serverPayload } = require('../../pages/play/utils/playkit-view.js')
const ROOT = path.resolve(__dirname, '../..')
const NOW = new Date(2026, 10, 9, 12)

/** 只取组件定义(不跑小程序运行时):拦一次 Component() 调用。 */
function loadComponent(rel) {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  global.Behavior = global.Behavior || ((b) => b)
  try { delete require.cache[require.resolve(path.join(ROOT, rel))]; require(path.join(ROOT, rel)) } finally { global.Component = prev }
  return def
}

const hangmanSeg = (over) => Object.assign({ length: 7, blankCount: 5, caseSensitive: false, solved: false, display: 'E_____S',
  slots: [{ i: 0, ch: 'E', kind: 'PREFILLED' }, { i: 1, kind: 'BLANK' }, { i: 2, kind: 'BLANK' }, { i: 3, kind: 'BLANK' },
    { i: 4, kind: 'BLANK' }, { i: 5, kind: 'BLANK' }, { i: 6, ch: 'S', kind: 'PREFILLED' }] }, over)

test('UG-01 hangman 段还出逐字猜词屏,未解开时不带任何答案片段', () => {
  const kit = pickPlayKit({ sessionId: 's1', version: 3, playKit: { hangman: hangmanSeg() } }, NOW)
  assert.equal(kit.type, 'hangman')
  assert.equal(kit.solved, false)
  assert.equal(kit.display, '', '未解开时不把 display(含预填)当答案展示')
  assert.deepEqual(kit.slots.filter((s) => s.kind === 'BLANK').map((s) => s.ch), ['', '', '', '', ''])
  assert.ok(!('answer' in kit) && !('aliases' in kit) && !('format' in kit))
})

test('UG-01 解开后只认服务端 solved 与 display', () => {
  const kit = pickPlayKit({ sessionId: 's1', version: 4, playKit: { hangman: hangmanSeg({ solved: true, display: 'ENDLESS' }) } }, NOW)
  assert.equal(kit.solved, true)
  assert.equal(kit.display, 'ENDLESS')
})

test('UG-01 提交载荷:整词优先;逐格须补满全部可编辑格,缺格不发', () => {
  const slots = questo.hangmanSlots(hangmanSeg())
  assert.deepEqual(questo.hangmanPayload(slots, {}, ' NICOLASPERNELLE '), { input: 'NICOLASPERNELLE' })
  assert.equal(questo.hangmanPayload(slots, { 1: 'N', 2: 'D' }, ''), null)
  assert.deepEqual(questo.hangmanPayload(slots, { 1: 'N', 2: 'D', 3: 'L', 4: 'E', 5: 'S' }, ''),
    { slots: { 1: 'N', 2: 'D', 3: 'L', 4: 'E', 5: 'S' } })
  // 服务端已记下的 FILLED 格可沿用,也可被本地改写
  const filled = questo.hangmanSlots(hangmanSeg({ slots: [{ i: 0, ch: 'E', kind: 'PREFILLED' }, { i: 1, ch: 'X', kind: 'FILLED' }] }))
  assert.deepEqual(questo.hangmanPayload(filled, {}, ''), { slots: { 1: 'X' } })
  assert.deepEqual(questo.hangmanPayload(filled, { 1: 'N' }, ''), { slots: { 1: 'N' } })
  assert.equal(serverAction('hangman', 'submit'), 'SUBMIT_HANGMAN')
  assert.deepEqual(serverPayload('hangman', 'submit', { slots: { 1: 'N' } }), { slots: { 1: 'N' } })
  assert.deepEqual(serverPayload('hangman', 'submit', { input: 'ENDLESS' }), { input: 'ENDLESS' })
})

test('UG-01 补充平面字符各占一格', () => {
  assert.deepEqual(questo.codePoints('a𠀀b'), ['a', '𠀀', 'b'])
})

test('UG-02 选项图只出可显示地址;media-asset/http/data 不出图也不出 alt', () => {
  const opts = questo.qaOptions([
    { id: 'A', label: '甲', img: 'https://cdn.example.com/a.png', alt: '戴帽子的人' },
    { id: 'B', label: '乙', img: 'media-asset:12', alt: '乙' },
    { id: 'C', label: '丙', img: 'http://x/c.png', alt: '丙' },
    { id: 'D', label: '丁', img: 'data:image/png;base64,AA', alt: '丁' },
  ], null)
  assert.deepEqual(opts[0], { id: 'A', t: '甲', img: 'https://cdn.example.com/a.png', alt: '戴帽子的人' })
  for (const o of opts.slice(1)) assert.deepEqual(Object.keys(o).sort(), ['id', 't'])
  const site = questo.qaOptions([{ id: 'A', label: '甲', img: '/profile/a.png', alt: 'x' }], (u) => 'https://cdn.example.com' + u)
  assert.equal(site[0].img, 'https://cdn.example.com/profile/a.png')
})

test('UG-02 选项题 kit 带上图;正确标志与反馈一个都不带', () => {
  const kit = pickPlayKit({ sessionId: 's1', version: 1, playKit: { qa: { mode: 'PICK', title: '谁?', options: [
    { id: 'A', label: '甲', img: 'https://cdn.example.com/a.png', alt: '甲的照片' }, { id: 'B', label: '乙' }] } } }, NOW)
  assert.equal(kit.type, 'qa')
  assert.equal(kit.options[0].img, 'https://cdn.example.com/a.png')
  assert.ok(kit.options.every((o) => !('correct' in o) && !('fb' in o)))
})

test('UG-10 谜面翻转已删除:存量 clueTransform 不再改题面、也不翻转', () => {
  assert.ok(!('clueMode' in questo))
  const kit = pickPlayKit({ sessionId: 's1', version: 1, clueTransform: { mode: 'MIRROR' }, playKit: { qa: { mode: 'TYPE', title: '题' } } }, NOW)
  assert.ok(!('clueMode' in kit))
  assert.equal(kit.title, '题')
  const qaWxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-qa/index.wxml'), 'utf8')
  assert.ok(!/clueMode|qa__q-mirror/.test(qaWxml))
})

const accusationView = (over) => Object.assign({ sessionId: 's9', version: 2, nodeId: 7,
  playKit: { qa: { mode: 'PICK', title: '谁拿走了钥匙?', options: [{ id: 'A', label: '老板' }, { id: 'B', label: '守夜人' }], attempts: 0, finished: false, passed: false } },
  accusation: { enabled: true, suspects: [{ id: 'A', name: '老板', identity: '古董店', avatar: 'https://cdn.example.com/a.png',
    testimony: [{ key: 'clue.a', text: '十点我在盘点' }] }, { id: 'B', name: '守夜人', avatar: 'media-asset:9' }],
  earlyAccuse: { allowed: true } } }, over)

test('UG-03 配了 accusation 的选项题出指认页;确认前没有结论、结局与正确人物', () => {
  const kit = pickPlayKit(accusationView(), NOW)
  assert.equal(kit.type, 'accusation')
  assert.equal(kit.suspects.length, 2)
  assert.equal(kit.suspects[1].avatar, '', 'media-asset 引用不当图片地址')
  assert.deepEqual(kit.suspects[0].testimony, [{ key: 'clue.a', text: '十点我在盘点' }])
  assert.equal(kit.outcome, '')
  assert.deepEqual(kit.screen, [])
  assert.equal(serverAction('accusation', 'submit'), 'SUBMIT_QA')
  assert.deepEqual(serverPayload('accusation', 'submit', { optionId: 'B' }), { optionId: 'B' })
})

test('UG-04 三态只读服务端 verdict/early:正常结案 / 提前结案 / 指认失败;WRONG 可继续', () => {
  const at = (result, screen, qa) => pickPlayKit(accusationView({
    status: result && result.terminal ? (result.correct ? 'COMPLETED' : 'FAILED') : 'RUNNING',
    playKit: { qa: Object.assign({ mode: 'PICK', options: [{ id: 'A', label: '老板' }] }, qa) },
    accusation: Object.assign({}, accusationView().accusation, { result, screen }) }), NOW)
  const solved = at({ verdict: 'CORRECT', correct: true, early: false, terminal: true, canRetry: false }, '真相大白', { finished: true, passed: true, attempts: 1 })
  assert.equal(solved.outcomeLabel, '正常结案')
  assert.deepEqual(solved.screen, [{ kind: 'text', text: '真相大白' }])
  const early = at({ verdict: 'CORRECT', correct: true, early: true, recordCode: '速破', terminal: true, canRetry: false }, { text: '你提前锁定了他' }, { finished: true, passed: true, attempts: 1 })
  assert.equal(early.outcomeLabel, '提前结案')
  assert.ok(!JSON.stringify(early).includes('速破'), 'recordAs 不给玩家看')
  const failed = at({ verdict: 'FAILED', correct: false, early: false, terminal: true, canRetry: false }, '他逃走了', { finished: true, passed: false, attempts: 3 })
  assert.equal(failed.outcomeLabel, '指认失败')
  assert.equal(failed.terminal, false, '指认页自己摆结局段,不叠通用「未通过」')
  const wrong = at({ verdict: 'WRONG', correct: false, early: false, terminal: false, canRetry: true }, null, { finished: false, passed: false, attempts: 1, lastFeedback: '他有不在场证明' })
  assert.equal(wrong.outcome, 'WRONG')
  assert.equal(wrong.outcomeLabel, '')
  assert.equal(wrong.wrongAttempt, true)
  assert.equal(wrong.retryNote, '他有不在场证明')
})

test('§27.9 readyForBase 不是成功;qa 判完但没有结论时显示结算确认中', () => {
  const kit = pickPlayKit(accusationView({ readyForBase: true,
    playKit: { qa: { mode: 'PICK', options: [{ id: 'A', label: '老板' }], finished: true, passed: false, attempts: 1 } } }), NOW)
  assert.equal(kit.pending, true)
  assert.equal(kit.outcomeLabel, '')
  const src = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-accusation/index.js'), 'utf8')
  assert.ok(!/readyForBase/.test(src.replace(/\/\/.*$/gm, '')), '指认页不读 readyForBase')
})

test('UG-03 确认指认走本屏确认层(r2:关闭在右上角),文案写明「这是最终指认」,确认后才交原 optionId', () => {
  const src = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-accusation/index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-accusation/index.wxml'), 'utf8')
  assert.ok(!/wx\.showModal|modal\.show\(/.test(src), '不用系统弹窗')
  assert.ok(/这是最终指认/.test(src))
  assert.ok(/class="ac-cf__close"[^>]*bindtap="onConfirmCancel"/.test(wxml), '确认层右上角关闭')
  assert.ok(!/再想想/.test(wxml), '不再叠第二个取消按钮')
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-accusation/index.wxss'), 'utf8')
  assert.ok(/\.ac-cf__panel \{[^}]*background: var\(--cy-comp-sheet-player-bg\)/.test(wxss), '确认层底色走玩家弹层 token')
})

test('UG-04 结论动图:结案 = 庆祝、失败 = 失败、结算确认中 = 搜查;动图在小分包,按分包异步化引用', () => {
  const Comp = loadComponent('pages/play/components/playkit-accusation/index.js')
  assert.equal(Comp.methods._animKind('SOLVED', false), 'success')
  assert.equal(Comp.methods._animKind('EARLY', false), 'success')
  assert.equal(Comp.methods._animKind('FAILED', false), 'fail')
  assert.equal(Comp.methods._animKind('', true), 'search')
  assert.equal(Comp.methods._animKind('WRONG', false), '')
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-accusation/index.json'), 'utf8'))
  assert.equal(json.usingComponents['cy-case-anim'], '/subpackageTools/components/case-anim/index')
  assert.equal(json.componentPlaceholder['cy-case-anim'], 'view')
  for (const k of ['search', 'success', 'fail']) {
    for (const ext of ['gif', 'png']) {
      const size = fs.statSync(path.join(ROOT, 'subpackageTools/components/case-anim/img/' + k + '.' + ext)).size
      assert.ok(size < 100 * 1024, k + '.' + ext + ' 小于 100KB')
    }
  }
})

test('分发器登记了两屏并把 kit 字段喂给组件', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit/index.wxml'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit/index.json'), 'utf8'))
  assert.equal(json.usingComponents['cy-playkit-hangman'], '/pages/play/components/playkit-hangman/index')
  assert.equal(json.usingComponents['cy-playkit-accusation'], '/pages/play/components/playkit-accusation/index')
  for (const key of ['slots', 'solved', 'display']) assert.ok(wxml.includes('{{kit.' + key + '}}'), key)
  for (const key of ['suspects', 'outcome', 'outcomeLabel', 'pending', 'screen']) assert.ok(wxml.includes('{{kit.' + key + '}}'), key)
})

test('UG-01 r2:格子按词分组(长短语不再横向滚走);l-kits 2026-11-10:系统键盘,输入框在格子下面', () => {
  const Comp = loadComponent('pages/play/components/playkit-hangman/index.js')
  const cells = questo.hangmanSlots({ slots: 'AB C-D'.split('').map((ch, i) => (ch === ' ' || ch === '-') ? { i, ch, kind: 'SEPARATOR' } : { i, kind: 'BLANK' }) })
  const groups = Comp.methods._groupCells(cells)
  assert.deepEqual(groups.map((g) => g.sep || g.cells.map((c) => c.i).join(',')), ['0,1', '3', '-', '5'])
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-hangman/index.wxml'), 'utf8')
  assert.ok(!/scroll-x/.test(wxml), '不再横向滚动')
  assert.ok(!/placeholder="第 \{\{active/.test(wxml), '不再出「第 N 格」输入栏')
  assert.ok(!/hm__ghost/.test(wxml), '不再用隐藏输入框:原型是看得见的输入框就在格子下面')
  assert.match(wxml, /<input class="hm__in[^>]*focus="\{\{focused\}\}" cursor="\{\{cursor\}\}" maxlength="\{\{blankCount\}\}"/, '一个输入框接系统键盘,点格子挪光标,字数封顶 = 可编辑格数')
  assert.ok(!/<view class="keys"|hm__key/.test(wxml), '不画自定义键盘')
})

test('UG-01 l-kits:输入什么格子按顺序填什么;预填 / 分隔符跳过;点格子 = 光标挪到它;填满才能交 {slots}', () => {
  const Comp = loadComponent('pages/play/components/playkit-hangman/index.js')
  const slots = questo.hangmanSlots({ slots: 'NICOLAS AND'.split('').map((ch, i) => ch === ' ' ? { i, ch, kind: 'SEPARATOR' } : ([0, 8].includes(i) ? { i, ch, kind: 'PREFILLED' } : { i, kind: 'BLANK' })) })
  const m = Comp.methods
  assert.deepEqual(m._distribute(slots, 'ic o'), { 1: 'i', 2: 'c', 3: 'o' }, '空白不占格,按可编辑格顺序落')
  assert.equal(m._cellAt(slots, 3), 4, '第 4 个可编辑格是槽位 4(跳过预填 0)')
  assert.equal(m._cellAt(slots, 99), -1)
  const cells = m._paintCells(slots, m._distribute(slots, 'ic o'))
  assert.deepEqual(cells.filter((c) => c.kind === 'BLANK').map((c) => c.n), [0, 1, 2, 3, 4, 5, 6, 7], '可编辑格带顺序号 = 输入框里第几个字')
  assert.equal(hangmanPayloadOf(slots, m._distribute(slots, 'icolas')), null, '缺格不发')
  assert.deepEqual(hangmanPayloadOf(slots, m._distribute(slots, 'icolasnd')), { slots: { 1: 'i', 2: 'c', 3: 'o', 4: 'l', 5: 'a', 6: 's', 9: 'n', 10: 'd' } })
  // 服务端已记下的 FILLED 格进输入框初值:从头连续有字的那几格
  const filled = questo.hangmanSlots({ slots: [{ i: 0, ch: 'E', kind: 'PREFILLED' }, { i: 1, ch: 'N', kind: 'FILLED' }, { i: 2, kind: 'BLANK' }, { i: 3, ch: 'X', kind: 'FILLED' }] })
  assert.equal(m._initialText(filled), 'N')
  const js = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-hangman/index.js'), 'utf8')
  assert.match(js, /hangmanPayload\(this\.data\.slots, this\._typed, ''\)/, '只交逐格 slots,不再有整词栏')
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-hangman/index.wxss'), 'utf8')
  assert.match(wxss, /\.hm__cell\.is-pop \{ transform: scale\(1\.18\); \}/, '每填一格弹一下走过渡,不加关键帧')
  assert.doesNotMatch(wxss, /@keyframes/)
  assert.match(wxss, /\.hm__cell\.is-ok \{[^}]*transition-delay: calc\(var\(--i, 0\) \* 70ms\)/, '答对格子依次变绿')
  assert.match(wxss, /\.hm--reduced \.hm__cell\.is-pop, \.hm--reduced \.hm__cell\.is-active \{ transform: none; \}/, '减少动态效果:终态')
})
function hangmanPayloadOf(slots, typed) { return questo.hangmanPayload(slots, typed, '') }
