'use strict'
// C7 创作者主题状态键:规则与后端 ThemeStateKey.KEY_NAME 一致;删除走 DELETE + 危险确认
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const keys = require('../../pages/publish/components/topic-state-keys/rules.js')

test('C7: 键名由系统生成且合后端规则;界面只显示名字', () => {
  assert.ok(keys.KEY_NAME.test('clue.ledger_page'))
  assert.ok(!keys.KEY_NAME.test('Clue.x'))
  assert.ok(!keys.KEY_NAME.test('clue.1abc'))
  assert.ok(!keys.KEY_NAME.test('foo.bar'))
  const clue = keys.newKey('clue', ' 半张货单 ', [], 1)
  assert.deepEqual(clue, { stateKey: 'clue.k1', keyType: 'flag', scope: 'run', label: '半张货单' })
  assert.ok(keys.KEY_NAME.test(keys.newKey('tag', '新来的', [], Date.now()).stateKey))
  assert.equal(keys.newKey('tag', 'x', [{ stateKey: 'tag.k1' }], 1).stateKey, 'tag.k2', '不与已有键冲突')
  assert.equal(keys.newKey('counter', 'x', []), null, '计数、关系不在线索与物品里新建')
  assert.equal(keys.nameError('clue', '半张货单', ['半张货单']), '已有同名的线索。')
  assert.equal(keys.nameError('item', '撬棍', ['撬棍']), '已有同名的物品。')
  assert.equal(keys.nameError('tag', '新来的', []), '')
  assert.deepEqual(keys.parseUsedIn('状态键 clue.a 已被引用,不能删除。引用位置:节点「旧码头仓库」、主题剧情配置'), ['节点「旧码头仓库」', '主题剧情配置'])
  assert.deepEqual(keys.parseUsedIn('fixture rejection'), [])
  assert.equal(keys.normalizeKeys([{ stateKey: 'bad' }]), null)
  const row = keys.normalizeKeys([{ stateKey: 'clue.k1', keyType: 'flag' }])[0]
  assert.equal(keys.displayName(row), '线索', '没有名字时不回落到键名')
})

test('C7: 界面不显示键名、不让手打英文', () => {
  const wxml = read('pages/publish/components/topic-state-keys/index.wxml')
  assert.doesNotMatch(wxml, /stateKey|prefix|suffix|小写字母|状态键/)
  assert.match(wxml, /线索与物品/)
  const js = read('pages/publish/components/topic-state-keys/index.js')
  assert.match(js, /method: 'PUT'/, '改名只改名字(PUT,键名不变)')
})

test('C7: 删除走 DELETE(参数在 query)且先过 topic.stateKey.delete 危险确认', () => {
  const src = read('pages/publish/components/topic-state-keys/index.js')
  assert.match(src, /method: 'DELETE'/)
  assert.match(src, /url: `\/api\/topic\/state-keys\?\$\{this\._qs\(\)\}&key=/)
  assert.match(src, /dc\.open\('topic\.stateKey\.delete'/)
})

test('C7: 只在编辑已有主题时挂载,白名单编辑为只读', () => {
  const sheet = read('pages/publish/fabu/topic-detail-sheet.wxml')
  assert.match(sheet, /<topic-state-keys wx:if="\{\{editingTopicId\}\}" topic-id="\{\{editingTopicId\}\}"/)
  assert.match(sheet, /read-only="\{\{editScope === 'WHITELIST'\}\}"/)
  assert.match(sheet, /items="\{\{keysItems\}\}"/)
  const page = JSON.parse(read('pages/publish/fabu/index.json'))
  assert.equal(page.usingComponents['topic-state-keys'], '/pages/publish/components/topic-state-keys/index')
})
