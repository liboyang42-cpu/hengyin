// qx-b2 · Questo 新玩法作者配置(UG-01/02/03/10/11、§23.1 别名)与目录同步
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

global.getApp = global.getApp || (() => ({}))
const author = require('../../pages/publish/temp/questo-author.js')
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js')
const preview = require('../../pages/publish/utils/publish/advanced-game-preview.js')
const advancedGameConfig = require('../../pages/publish/utils/publish/advanced-game-config.js')
const { KIT_PRIORITY } = require('../../pages/play/utils/playkit-view.js')
const ROOT = path.resolve(__dirname, '../..')

test('目录:逐字猜词不单独占选择器标签,记在文字问答上;选中即建段并开启,玩家端能还出那一屏', () => {
  // r2 b2-x-cat(主人「不是这种啊 不是表单吗」):入口在文字问答的表单里切换
  assert.ok(!catalog.BASE_GAMES.some((g) => g.gameKey === 'hangman'), '选择器不再单列逐字猜词')
  assert.equal(catalog.pickerKey('hangman'), 'qaText')
  assert.equal(catalog.selection('hangman').baseKey, 'text')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.wxml'), 'utf8')
  assert.equal((wxml.match(/bindchange="qzHangmanForm"/g) || []).length, 2, '文字问答表单打开、逐字猜词表单关回')
  assert.equal(catalog.findGame('hangman').section, 'hangman')
  assert.ok(catalog.PLAYKIT_SECTIONS.includes('hangman'))
  assert.ok(KIT_PRIORITY.includes('hangman'))
  const next = catalog.applyToConfig(advancedGameConfig.defaultConfig(), 'hangman')
  assert.equal(next.hangman.enabled, true)
  assert.equal(catalog.detectGame(next), 'hangman')
  const back = catalog.applyToConfig(next, 'qaText')
  assert.equal(back.hangman.enabled, false, '换玩法时关掉逐字猜词段')
})

test('UG-01 格子:默认全部待补;点格切换预填;分隔符锁定;改答案保留仍对得上的预填', () => {
  const section = { enabled: true, answer: 'A-B C' }
  const cells = author.hangmanCells(section)
  assert.deepEqual(cells.map((c) => c.sep), [false, true, false, true, false])
  const format = author.toggleGiven(section, 0)
  assert.deepEqual(format, ['A', '-', '_', ' ', '_'])
  assert.equal(author.toggleGiven(section, 1), null, '分隔符不能点')
  assert.deepEqual(author.reshapeFormat('A-B C', ['A', '-', '_', ' ', '_']), ['A', '-', '_', ' ', '_'])
  assert.deepEqual(author.reshapeFormat('X-B C', ['A', '-', '_', ' ', '_']), [], '对不上就回到全部待补(不写 format)')
})

test('UG-01 就地校验挡服务端必拒的形状;编辑中不先报「还没填」', () => {
  assert.equal(author.hangmanError({ enabled: true }, true), '')
  assert.equal(author.view({ hangman: { enabled: true } }).qzHangmanError, '')
  assert.equal(author.view({ hangman: { enabled: true } }, true).qzHangmanError, '请填写答案')
  assert.equal(author.hangmanError({ enabled: true, answer: '' }), '请填写答案')
  assert.equal(author.hangmanError({ enabled: true, answer: '--' }), '答案至少要有一个可补的字母或数字')
  assert.equal(author.hangmanError({ enabled: true, answer: 'AB', format: ['A', 'B'] }), '至少留一格给玩家补')
  assert.equal(author.hangmanError({ enabled: true, answer: 'AB', aliases: ['ab'] }), '别名与答案或其他别名重复')
  assert.equal(author.hangmanError({ enabled: true, answer: 'AB', aliases: ['ab'], caseSensitive: true }), '')
  assert.equal(author.hangmanError({ enabled: true, answer: '城瘾' }), '')
})

test('UG-02 选项图:图片说明默认取选项文案,不另设一栏;编辑中不报红', () => {
  const qa = { enabled: true, mode: 'PICK', options: [{ id: 'a', img: 'https://x/a.png' }, { id: 'b', img: 'https://x/b.png', label: '守夜人' }, { id: 'c', img: 'https://x/c.png', alt: '好' }] }
  assert.deepEqual(author.optionMediaErrors(qa, true), {}, '编辑中不先给红字')
  assert.deepEqual(author.optionMediaErrors(qa), { 0: '配了图的选项要写文案' })
  const saved = author.beforeSave({ qa })
  assert.equal(saved.qa.options[1].alt, '守夜人', '存盘时替代文字取选项文案(服务端要求配图必带 alt)')
  assert.equal(saved.qa.options[2].alt, '好', '作者原有的说明不覆盖')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.wxml'), 'utf8')
  assert.ok(!/图片替代文字|qzOptionAlt/.test(wxml), '不再单独要作者填替代文字')
})

test('UG-03 人物与选项一一对应;姓名取选项文案;作者不填正确人物', () => {
  const acc = { suspects: [{ id: 'b', avatar: 'https://x/b.png', identity: '守夜人', testimony: [{ key: 'clue.a', text: '我睡着了' }] }] }
  const rows = author.syncSuspects(acc, [{ id: 'a', label: '老板' }, { id: 'b', label: '守夜人老王' }])
  assert.deepEqual(rows.map((r) => [r.id, r.name]), [['a', '老板'], ['b', '守夜人老王']])
  assert.equal(rows[1].avatar, 'https://x/b.png')
  const saved = author.beforeSave({ qa: { mode: 'PICK', options: [{ id: 'a', label: '老板' }] },
    accusation: { enabled: true, suspects: [], winScreen: 'w', loseScreen: 'l', earlyAccuse: { allowed: false, recordAs: '旧' } } })
  assert.ok(!('correctSuspectId' in saved.accusation))
  assert.deepEqual(saved.accusation.earlyAccuse, { allowed: false })
  assert.deepEqual(saved.accusation.suspects, [{ id: 'a', name: '老板' }])
})

test('UG-03/04 必填:头像、两个结局;允许提前指认时要写记录名(自由文本,非下拉)', () => {
  const base = { qa: { mode: 'PICK', options: [{ id: 'a', label: '老板' }] },
    accusation: { enabled: true, suspects: [{ id: 'a', avatar: 'https://x/a.png' }], winScreen: 'w', loseScreen: '', earlyAccuse: { allowed: false } } }
  assert.equal(author.accusationError(base), '请写指认错误的结局')
  base.accusation.loseScreen = 'l'
  base.accusation.earlyAccuse = { allowed: true }
  assert.equal(author.accusationError(base), '允许提前指认时要写结束记录名')
  base.accusation.earlyAccuse.recordAs = '速破'
  assert.equal(author.accusationError(base), '')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.wxml'), 'utf8')
  assert.ok(/earlyAccuse\.recordAs\}\}" placeholder/.test(wxml), 'recordAs 是输入框')
  assert.ok(!/EARLY_ACCUSATION|EARLY_CLOSURE|correctSuspectId/.test(wxml))
  assert.ok(!wxml.includes('待运行验证'), 'r2:主人「不用写多余的提示 类似待运行验证这种」')
})

test('UG-10 谜面翻转已删除:作者端不提供,存量配置的试玩也按原样题面', () => {
  // r2 b2-ug-10 主人:「这是什么 不能这样反转」「删掉这个翻转谜面」
  assert.ok(!('CLUE_MODES' in author))
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.wxml'), 'utf8')
  assert.ok(!/谜面呈现|qzClue|clueTransform/.test(wxml))
  const kit = preview.buildPreviewKit({ qa: { enabled: true, mode: 'TYPE', title: '钟楼' }, clueTransform: { mode: 'REVERSE' } })
  assert.equal(kit.title, '钟楼', '存量 REVERSE 不再倒序')
  const mirror = preview.buildPreviewKit({ qa: { enabled: true, mode: 'TYPE', title: '钟楼' }, clueTransform: { mode: 'MIRROR' } })
  assert.equal(mirror.title, '钟楼')
  assert.ok(!('clueMode' in mirror))
})

test('UG-01 作者格子按词分组:一个词不折行', () => {
  const groups = author.hangmanGroups(author.hangmanCells({ enabled: true, answer: 'AB C-D' }))
  assert.deepEqual(groups.map((g) => g.sep || g.cells.map((c) => c.ch).join('')), ['AB', 'C', '-', 'D'])
})

test('UG-11 提示阶梯:类型只有六种(无位置类),整局一个免费次数,层号连续,不写宝石/金币', () => {
  assert.deepEqual(author.HINT_TYPES.map((t) => t.value), ['TEXT', 'IMAGE', 'LETTER', 'DIGIT', 'ANSWER', 'SKIP'])
  const saved = author.beforeSave({ hintFreeUsesPerRun: '2', hintLadder: [
    { level: 3, type: 'TEXT', payload: ' 看钟面 ', cost: { points: '' } },
    { level: 9, type: 'LETTER', payload: '2', cost: { points: '20' } },
    { level: 1, type: 'SKIP', payload: 'x' }] })
  assert.equal(saved.hintFreeUsesPerRun, 2)
  assert.deepEqual(saved.hintLadder, [
    { level: 1, type: 'TEXT', payload: '看钟面' },
    { level: 2, type: 'LETTER', payload: 2, cost: { points: 20 } },
    { level: 3, type: 'SKIP' }])
  assert.ok(saved.hintLadder.every((row) => !('freeQuota' in row) && !(row.cost && ('gems' in row.cost || 'coins' in row.cost))))
  const err = author.hintErrors({ hintFreeUsesPerRun: -1, hintLadder: [{ type: 'TEXT', payload: '' }, { type: 'IMAGE' }] })
  assert.equal(err.quota, '免费次数须为 0 至 1000 的整数')
  assert.equal(err.levels[0], '请填写提示文字')
  assert.equal(err.levels[1], '请上传提示图片')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.wxml'), 'utf8')
  const fb = wxml.indexOf('id="sec-fb"'), hint = wxml.indexOf('id="sec-hint"')
  assert.ok(fb > 0 && hint > fb, '提示组在结果反馈下方')
  assert.ok(!/hintLadder|hintFreeUsesPerRun/.test(wxml.replace(/advanced\.hintFreeUsesPerRun/g, '')), '界面不露配置键名')
  const legacy = wxml.indexOf('formData.hint1')
  assert.ok(legacy > fb, '旧一级/二级提示并到结果反馈下方')
  assert.ok(/methodSupportsHint && !qzHintOn/.test(wxml), '新旧提示不同时生效')
})

test('§23.1 文字答案别名:写进私有 answerPolicy,空则不写', () => {
  const saved = author.beforeSave({ answerPolicy: { version: 1, normalizationPolicy: 'LEGACY_V1', acceptedAliases: [] } })
  assert.ok(!('answerPolicy' in saved))
  assert.equal(author.aliasError({ acceptedAliases: ['一九三七', '一九三七'] }), '写法不能重复')
})

test('编辑页接线:混入方法、存盘前收口、校验阻断', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.js'), 'utf8')
  assert.ok(/\.\.\.questoAuthor\.methods/.test(js))
  assert.ok(/questoAuthor\.beforeSave\(this\.data\.advanced\)/.test(js))
  assert.ok(/this\._qzSaveError\(\)/.test(js))
})

test('试玩:逐字猜词演作者配置的格子,指认题演人物卡', () => {
  const kit = preview.buildPreviewKit({ hangman: { enabled: true, answer: 'AB', format: ['A', '_'] } })
  assert.deepEqual(kit.slots.map((s) => s.kind), ['PREFILLED', 'BLANK'])
  const acc = preview.buildPreviewKit({ qa: { enabled: true, mode: 'PICK', title: '谁', options: [{ id: 'a', label: '老板', correct: true }] },
    accusation: { enabled: true, suspects: [{ id: 'a', avatar: 'https://x/a.png' }] } })
  assert.equal(acc.type, 'accusation')
  assert.equal(acc.suspects[0].name, '老板')
  assert.ok(!JSON.stringify(acc).includes('correct'))
})
