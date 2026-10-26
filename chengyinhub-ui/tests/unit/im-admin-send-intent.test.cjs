const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
const code = fs.readFileSync(path.resolve(__dirname, '../../src/utils/im-admin-send-intent.js'), 'utf8').replace(/export /g, '')
function fixture() {
  const box = {}; vm.runInNewContext(code, box)
  const values = new Map()
  return { ...box, storage: { getItem: k => values.get(k) || null, setItem: (k, v) => values.set(k, v), removeItem: k => values.delete(k) } }
}
test('cold recovery freezes original content and request across clients', () => {
  const f = fixture()
  const first = f.adminSendIntent(f.storage, '1', '2', 'original', () => 'request_0001')
  const cold = fixture()
  const recovered = cold.adminSendIntent(f.storage, '1', '2', 'changed', () => 'request_0002')
  assert.equal(recovered.requestId, first.requestId); assert.equal(recovered.content, 'original')
})
test('administrator and recipient scopes do not share pending content', () => {
  const f = fixture(); f.adminSendIntent(f.storage, 1, 2, 'private', () => 'request_0001')
  assert.equal(f.adminSendIntent(f.storage, 3, 2, '', () => ''), null)
  assert.equal(f.adminSendIntent(f.storage, 1, 4, '', () => ''), null)
})
test('acknowledged request clears only its own pending intent', () => {
  const f = fixture(); const old = f.adminSendIntent(f.storage, 1, 2, 'one', () => 'request_0001')
  f.clearAdminSendIntent(f.storage, old)
  const next = f.adminSendIntent(f.storage, 1, 2, 'two', () => 'request_0002')
  f.clearAdminSendIntent(f.storage, old)
  assert.equal(f.adminSendIntent(f.storage, 1, 2, '', () => '').requestId, next.requestId)
})
test('storage failure prevents creation and never replaces unknown intent', () => {
  const f = fixture(); const bad = { getItem: () => null, setItem: () => {} }
  assert.throws(() => f.adminSendIntent(bad, 1, 2, 'one', () => 'request_0001'), /未能保存/)
  assert.throws(() => f.adminSendIntent({ getItem: () => '{' }, 1, 2, 'one', () => 'request_0001'))
})
