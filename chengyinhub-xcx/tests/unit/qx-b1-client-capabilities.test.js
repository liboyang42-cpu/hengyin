// qx-b1 U04-01/U04-03 · §27.2:开局能力声明只登记本端真实渲染的能力,且与服务端推导键逐字对齐。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

global.wx = global.wx || { getAccountInfoSync: () => ({ miniProgram: { envVersion: 'release', version: '3.2.1' } }) }
const caps = require('../../pages/play/utils/client-capabilities.js')
const { TYPE_OF } = require('../../pages/play/utils/playkit-view.js')

const ROOT = path.resolve(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const JAVA = '../chengyinhub-system/src/main/java/com/chengyinhub/business/'

test('声明是非空 key@version 串,带平台与版本;格式过得了服务端 Declaration.parse', () => {
  const d = caps.declaration()
  assert.equal(d.clientPlatform, 'wechat-miniprogram')
  assert.equal(d.clientVersion, '3.2.1')
  assert.ok(d.supportedCapabilities.length > 0, '本端总是显式声明;空串会被服务端当成「零能力」')
  for (const token of d.supportedCapabilities.split(',')) assert.match(token, /^[A-Za-z0-9._-]+@1$/)
})

test('玩法段声明取自玩家端分发表 TYPE_OF,一段不多一段不少', () => {
  const games = caps.keys().filter(k => k.startsWith('game.')).sort()
  assert.deepEqual(games, Object.keys(TYPE_OF).map(s => 'game.' + s.toLowerCase()).sort())
})

test('没有渲染或提交入口的能力不声明:App 传感器、双人视角、团队共解', () => {
  const keys = caps.keys()
  for (const absent of ['answer.sensor', 'role.views', 'game.teamcooperation']) {
    assert.ok(!keys.includes(absent), absent + ' 本端没有实现,不能声明')
  }
  // 2026-11-10 staging 合流:qx-b2 的逐字猜词渲染器已进 play-kit 分发表(TYPE_OF.hangman),
  // game.hangman 按 qx-b1 的设计随 TYPE_OF 自动声明(见 qx-b1 RESULT Cross-lane 2)。
  assert.ok(keys.includes('game.hangman'), 'hangman 已有渲染器,应随 TYPE_OF 声明')
  assert.ok(!keys.some(k => k.startsWith('sensor.')))
  assert.ok(!keys.some(k => k.startsWith('answer.method.')))
})

test('故事流每一种服务端允许的块类型都有玩家端渲染分支,并在声明里', () => {
  const compiler = read(JAVA + 'service/impl/ChapterFlowCompiler.java')
  const allowed = (compiler.match(/块类型只允许 ([a-z /]+)"/) || [])[1]
  assert.ok(allowed, '没认出 ChapterFlowCompiler 的块类型白名单')
  const types = allowed.split('/').map(s => s.trim()).filter(Boolean)
  assert.deepEqual([...types].sort(), [...caps.STORY_BLOCKS].sort())
  const play = read('pages/play/index.js')
  for (const type of types) {
    assert.ok(new RegExp("b\\.type === '" + type + "'").test(play), type + ' 块在故事流里没有渲染分支')
    assert.ok(caps.keys().includes('chapter.blocks.' + type))
  }
  assert.ok(/item\.kind === 'divider'/.test(read('pages/play/index.wxml')), '分割线必须仍是一个 .chfull__para')
})

test('完成方式与服务端推导键逐字对齐(大小写敏感)', () => {
  const java = read(JAVA + 'service/support/ContentCapabilityRequirements.java')
  for (const key of caps.keys().filter(k => k.startsWith('answer.'))) {
    assert.ok(java.includes('"' + key + '"'), key + ' 不是服务端会推导出的键')
  }
})

test('能力不足的确定拒绝给出可执行提示;其他错误不冒充', () => {
  assert.ok(caps.unsupportedNotice({ code: 409, err: 'CONTENT_CAPABILITY_UNSUPPORTED' }))
  assert.equal(caps.unsupportedNotice({ code: 500 }), '')
  assert.equal(caps.unsupportedNotice(null), '')
})

test('准备/开赛与高级玩法开局都带声明;能力拒绝不当成结果未知', () => {
  const orient = read('pages/play/utils/orient-lifecycle.js')
  assert.match(orient, /action === 'prepare' \|\| action === 'start'\) Object\.assign\(payload, clientCapabilities\.declaration\(\)\)/)
  assert.match(orient, /clientCapabilities\.UNSUPPORTED\]\.includes\(body\.err\)/)
  const advanced = read('pages/play/components/advanced-game/index.js')
  assert.match(advanced, /\/api\/play\/advanced\/start', 'POST', Object\.assign\(\{\}, scope, clientCapabilities\.declaration\(\)\)\)/)
})
