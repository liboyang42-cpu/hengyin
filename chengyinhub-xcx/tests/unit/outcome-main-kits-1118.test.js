// 结果规则主玩法识别(2026-11-18 实测 P0),与服务端 GameplayOutcomeRules.mainKit 同口径:
// 逐字猜词是主玩法;拍照与另一个主玩法同开时是证据步;只开分支/扫码配结果规则给明确提示。夹具为中性数据。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const outcome = require('../../pages/publish/utils/publish/outcome-after.js')

const RULES = { version: 1, pass: { reply: '继续' }, fail: { reply: '还不对，再看看题面' } }

test('逐字猜词带结果规则可以保存', () => {
  const config = { hangman: { enabled: true, answer: 'ABC' }, outcomeRules: RULES }
  assert.deepEqual(outcome.mains(config), ['hangman'])
  assert.equal(outcome.validate(config), '')
})

test('问答 + 拍照:问答是主玩法,拍照是证据步;单开拍照仍是主玩法', () => {
  const both = { qa: { enabled: true, mode: 'TYPE' }, photoCheck: { enabled: true, fallback: 'pass' }, outcomeRules: RULES }
  assert.deepEqual(outcome.mains(both), ['qa'])
  assert.equal(outcome.validate(both), '')
  assert.deepEqual(outcome.mains({ photoCheck: { enabled: true } }), ['photoCheck'])
  const two = { qa: { enabled: true }, hangman: { enabled: true }, outcomeRules: RULES }
  assert.match(outcome.validate(two), /须且仅须一个主玩法/)
})

test('只开分支配结果规则:明确告诉作者怎么改', () => {
  const msg = outcome.validate({ branch: { enabled: true, steps: [] }, outcomeRules: RULES })
  assert.match(msg, /分支不是主玩法/)
  assert.match(msg, /问答/)
})

test('无对错选择题:没写 fail 也能保存;写了也接受;判对错的仍须 fail', () => {
  const qa = { enabled: true, mode: 'PICK', judge: false, options: [{ id: 'A' }, { id: 'B' }] }
  assert.equal(outcome.validate({ qa, outcomeRules: { version: 1, pass: { reply: '继续' } } }), '')
  assert.equal(outcome.validate({ qa, outcomeRules: RULES }), '')
  assert.match(outcome.validate({ qa: Object.assign({}, qa, { judge: true }), outcomeRules: { version: 1, pass: { reply: '继续' } } }), /没通过之后/)
})
