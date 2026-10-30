/* 契约 §1.5 呈现方式(2026-09-18 用户拍板):创作端(Worker C)——
 *   ① 玩法单子上加「这个玩法怎么出现」二选一:内嵌故事流 / 整屏;
 *   ② 默认值照契约三档表,一个段都不许挪档(默认选中态,不写进配置也生效);
 *   ③ 「只能全屏」那批的开关**置灰并写明原因**(不是藏起来),点了也不许改出服务端必拒的配置;
 *   ④ diceRoll 例外:默认整屏,但 inline 必须放行 —— 两种写法都保留。
 *
 * 为什么把表抄死在测试里:后端 validator 按同一份契约写,挪档或改名要到保存/发布才暴露;
 * 这张表必须有两处独立记录,改名就至少有一个见证人会红。
 */
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js')
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

/* 2026-10-30 业主裁决「所有玩法都内嵌」:三档表合成一档,任何玩法段默认内嵌,作者仍可显式整屏。
 * 与服务端 AdvancedGameConfigValidator.PRESENT_SECTIONS 逐字同表(PlayKitPresentConfigTest 另抄一份)。 */
const ALL_SECTIONS = ['qa', 'branch', 'predict', 'pricePair', 'random', 'profile', 'note', 'check', 'quietHold',
  'estimate', 'diyName', 'typeIn', 'dailySign', 'slowTask', 'musicCorner', 'timeWindow', 'diceRoll',
  'reaction', 'ballShake', 'countdown', 'stopwatch', 'coinFlip', 'hiddenObject', 'scan', 'steps',
  'compass', 'shout', 'blindTaste', 'silentOrder', 'sort', 'match', 'classify', 'compare', 'photoCheck', 'album']
const DICE = 'diceRoll'

test('★默认表:每个玩法段默认内嵌,没有任何段被锁在整屏', () => {
  assert.deepEqual(cfg.PRESENT_INLINE_DEFAULT, ALL_SECTIONS)
  assert.deepEqual(cfg.PRESENT_FULLSCREEN_ONLY, [])
  for (const section of ALL_SECTIONS) {
    assert.equal(cfg.defaultPresent(section), 'inline', section + ' 必须默认内嵌')
    assert.equal(cfg.presentInlineLocked(section), false, section + ' 不许再置灰内嵌档')
  }
  assert.equal(cfg.defaultPresent('timer'), 'fullscreen', '不是玩法段(timer)照旧回退整屏,与服务端一致')
})

test('★玩法目录里每个段都在内嵌表里,没有漏网的默认漂移', () => {
  for (const section of catalog.GAME_SECTIONS) {
    assert.equal(cfg.defaultPresent(section), 'inline', section + ' 不在内嵌表里')
  }
  assert.deepEqual(catalog.GAME_SECTIONS.filter((s) => cfg.presentInlineLocked(s)), [])
})

test('★原「只能全屏」段配 inline 本地校验放行,显式整屏同样合法', () => {
  const m = cfg.defaultConfig()
  m.reaction.enabled = true
  m.present = 'inline'
  assert.equal(cfg.validate(m), '')
  m.present = 'fullscreen'
  assert.equal(cfg.validate(m), '')
})

test('★diceRoll 配 inline 必须放行(用户点名两种写法都保留);不认识的 present 必须拒', () => {
  const inlineDice = cfg.defaultConfig()
  inlineDice.diceRoll.enabled = true
  inlineDice.diceRoll.faces = ['一', '二', '三', '四', '五', '六']
  inlineDice.present = 'inline'
  assert.equal(cfg.validate(inlineDice), '', 'diceRoll 的内嵌那套被误当「只能全屏」拦了')

  const weird = cfg.defaultConfig()
  weird.qa.enabled = true
  weird.qa.title = '题'
  weird.qa.answerText = '答案'
  weird.present = 'halfscreen'
  assert.match(cfg.validate(weird), /呈现方式/, '不认识的 present 不许静默落默认表')
})

test('★present 往返一字不差;没开任何玩法段时被清掉,不冒充一份空配置', () => {
  const m = cfg.defaultConfig()
  m.qa.enabled = true
  m.qa.title = '题'
  m.qa.answerText = '答案'
  m.present = 'fullscreen'          // 作者显式把默认内嵌的 qa 配成整屏:必须存得住
  const saved = JSON.parse(cfg.serialize(m))
  assert.equal(saved.present, 'fullscreen')
  const back = cfg.parse(cfg.serialize(m))
  assert.equal(back.value.present, 'fullscreen')
  assert.equal(back.error, '')

  const empty = cfg.defaultConfig()
  empty.present = 'inline'          // 一个段都没开:present 没有意义
  assert.equal(cfg.serialize(empty), '', '空配置里残留的 present 会让「没配高级玩法」判定失真')
})

test('★换玩法时保留作者的呈现选择;没有玩法段时清掉', () => {
  const base = cfg.defaultConfig()
  base.qa.enabled = true
  base.present = 'inline'
  assert.equal(catalog.applyToConfig(base, 'react').present, 'inline', '原只能全屏的段现在也能内嵌,选择要跟着走')
  assert.equal(catalog.applyToConfig(base, 'note').present, 'inline')
  const full = cfg.defaultConfig()
  full.qa.enabled = true
  full.present = 'fullscreen'
  assert.equal(catalog.applyToConfig(full, 'react').present, 'fullscreen')
  assert.equal('present' in catalog.applyToConfig(base, 'bingo'), false, '主题级九宫格没有玩法段,present 一并清掉')
})

test('★applyPresent:两种值对任何段都写得进去,不认识的值不许写', () => {
  const m = cfg.defaultConfig()
  m.reaction.enabled = true
  assert.equal(cfg.applyPresent(m, 'reaction', 'inline').present, 'inline')
  assert.equal(cfg.applyPresent(m, 'reaction', 'fullscreen').present, 'fullscreen')
  assert.equal(cfg.applyPresent(m, DICE, 'inline').present, 'inline')
  assert.equal(cfg.applyPresent(m, 'qa', 'halfscreen'), null, '不认识的值不许写进配置')
})

test('★编辑器真有一块二选一开关,置灰说明与点击 handler 都绑在页面上', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  const js = read('pages/publish/temp/index.js')
  assert.ok(wxml.includes('怎么出现') && wxml.includes('gameConfigOpen'), '编辑器没有呈现方式这一块')
  // 2026-09-22 用户定全页单选改下拉:两档从两颗芯片变成 presentOptions 里的两项
  assert.ok(js.includes("key: 'inline'") && js.includes("key: 'fullscreen'"),
    '二选一的两档没有绑定')
  assert.ok(/<cy-dropdown [^>]*range="\{\{presentOptions\}\}"[^>]*bind:change="onPickPresent"/.test(wxml), '开关没有接上 handler')
  assert.ok(js.includes('onPickPresent') && js.includes('applyPresent'),
    'handler 没走真防线(applyPresent),只靠 WXML 属性拦不住点击')
})


test('相册最多六张，与主线后端保存边界一致，七张明确拒绝', () => {
  const model = cfg.defaultConfig()
  model.album.enabled = true
  model.album.images = Array.from({ length: 6 }, (_, i) => ({ url: 'https://cdn.example.com/' + i + '.jpg', line: '' }))
  assert.equal(cfg.validate(model), '')
  assert.equal(cfg.parse(cfg.serialize(model)).value.album.images.length, 6)
  model.album.images.push({ url: 'https://cdn.example.com/extra.jpg', line: '' })
  assert.equal(cfg.validate(model), '相册须配 1 至 6 张照片')
})
