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

test('UG-10 谜面变换:只有 MIRROR/REVERSE 生效,未知值按原样', () => {
  assert.equal(questo.clueMode({ clueTransform: { mode: 'MIRROR' } }), 'MIRROR')
  assert.equal(questo.clueMode({ clueTransform: { mode: 'REVERSE' } }), 'REVERSE')
  assert.equal(questo.clueMode({ clueTransform: { mode: 'CYPHER' } }), '')
  assert.equal(questo.clueMode({}), '')
  const kit = pickPlayKit({ sessionId: 's1', version: 1, clueTransform: { mode: 'MIRROR' }, playKit: { qa: { mode: 'TYPE', title: '题' } } }, NOW)
  assert.equal(kit.clueMode, 'MIRROR')
  assert.equal(kit.title, '题', 'REVERSE 由服务端倒好,客户端不二次反转;MIRROR 不改文字')
  const qaWxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-qa/index.wxml'), 'utf8')
  assert.ok(/clueMode === 'MIRROR' \? 'qa__q-mirror'/.test(qaWxml), '镜像只翻题面那一层')
  assert.ok(!/qa__opts[^>]*qa__q-mirror/.test(qaWxml), '选项不翻')
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

test('UG-03 确认指认走统一弹窗出口,文案写明「这是最终指认」', () => {
  const src = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-accusation/index.js'), 'utf8')
  assert.ok(/modal\.show\(/.test(src) && !/wx\.showModal/.test(src))
  assert.ok(/这是最终指认/.test(src))
})

test('分发器登记了两屏并把 kit 字段喂给组件', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit/index.wxml'), 'utf8')
  const json = JSON.parse(fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit/index.json'), 'utf8'))
  assert.equal(json.usingComponents['cy-playkit-hangman'], '/pages/play/components/playkit-hangman/index')
  assert.equal(json.usingComponents['cy-playkit-accusation'], '/pages/play/components/playkit-accusation/index')
  for (const key of ['slots', 'solved', 'display']) assert.ok(wxml.includes('{{kit.' + key + '}}'), key)
  for (const key of ['suspects', 'outcome', 'outcomeLabel', 'pending', 'screen']) assert.ok(wxml.includes('{{kit.' + key + '}}'), key)
})
