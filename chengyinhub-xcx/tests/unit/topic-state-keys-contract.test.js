'use strict'
// C7 创作者主题状态键:规则与后端 ThemeStateKey.KEY_NAME 一致;删除走 DELETE + 危险确认
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const keys = require('../../pages/publish/components/topic-state-keys/rules.js')

test('C7: 状态键规则与后端一致', () => {
  assert.ok(keys.KEY_NAME.test('clue.ledger_page'))
  assert.ok(!keys.KEY_NAME.test('Clue.x'))
  assert.ok(!keys.KEY_NAME.test('clue.1abc'))
  assert.ok(!keys.KEY_NAME.test('foo.bar'))
  const ok = keys.buildKey(1, 'visits', '到访', '3')
  assert.deepEqual(ok.key, { stateKey: 'counter.visits', keyType: 'counter', scope: 'run', label: '到访', initialValue: 3 })
  assert.equal(keys.buildKey(0, 'Bad', '', '').ok, false)
  assert.equal(keys.buildKey(1, 'n', '', '1.5').ok, false)
  assert.equal(keys.buildKey(0, 'a', '', '').key.initialValue, undefined)
  assert.equal(keys.normalizeKeys([{ stateKey: 'bad' }]), null)
  assert.deepEqual(keys.normalizeKeys([{ stateKey: 'counter.n', keyType: 'counter', initialValue: 2 }])[0].initialText, '初始值 2')
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
  const page = JSON.parse(read('pages/publish/fabu/index.json'))
  assert.equal(page.usingComponents['topic-state-keys'], '/pages/publish/components/topic-state-keys/index')
})
