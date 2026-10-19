/** Selector unit controls only. The unchanged 43 real browser cases remain mandatory. */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { exactButtonText, buttonByText } = require('../e2e/audit/visible-controls')

for (const label of ['查看证据', '刷新队列', '确认处理', '重试原请求']) {
  test(`button text matches exactly ${label} while ignoring only surrounding layout whitespace`, () => {
    const pattern = exactButtonText(label)
    assert.equal(pattern.test(label), true)
    assert.equal(pattern.test(` \n${label}\t `), true)
    for (const text of [`其他${label}`, `${label}其他`, `${label}（错误目标）`, `\ue000 ${label}`, '', label.slice(1)]) assert.equal(pattern.test(text), false, text)
  })
}
test('label regex metacharacters cannot expand the selected text', () => {
  const pattern = exactButtonText('Review (old)+?')
  assert.equal(pattern.test('Review (old)+?'), true)
  for (const text of ['Review old', 'Review (old)', 'Review (old)++++']) assert.equal(pattern.test(text), false)
})
for (const invalid of ['', null, undefined, /查看证据/, ' 查看证据', '查看证据 ', '查\n看证据', '查看证据\0']) {
  test(`invalid selector label ${String(invalid)} is refused`, () => assert.throws(() => exactButtonText(invalid), /label/))
}
function scope(nodes) {
  const calls = []
  return { calls, getByRole(role, options) {
    calls.push({ role, options })
    assert.equal(role, 'button'); assert.equal(options, undefined)
    const candidates = nodes.filter(node => node.role === 'button' && !node.hidden)
    return { filter({ hasText }) {
      const matches = candidates.filter(node => hasText.test(node.text))
      return { matches, click() { assert.equal(matches.length, 1, 'strict ambiguity or missing control'); return matches[0].id } }
    } }
  } }
}
test('CSS icon accessible glyphs do not hide the actual exact visible button label', () => {
  const root = scope([{ id: 'real', role: 'button', text: '查看证据', accessibleName: '\ue6ce 查看证据' },
    { id: 'wrong-text', role: 'button', text: '查看其他证据', accessibleName: '查看证据' }])
  assert.equal(buttonByText(root, '查看证据').click(), 'real')
  assert.deepEqual(root.calls, [{ role: 'button', options: undefined }])
})
test('button-role scoping is retained and hidden controls are not requested', () => {
  const root = scope([{ id: 'link', role: 'link', text: '查看证据' }, { id: 'hidden', role: 'button', text: '查看证据', hidden: true }])
  assert.equal(buttonByText(root, '查看证据').matches.length, 0)
})
test('ambiguous visible matches stay ambiguous rather than silently selecting first or forcing a click', () => {
  const root = scope([{ id: 'one', role: 'button', text: '查看证据' }, { id: 'two', role: 'button', text: '查看证据' }])
  const locator = buttonByText(root, '查看证据')
  assert.equal(locator.matches.length, 2); assert.throws(() => locator.click(), /strict ambiguity/)
})
test('missing or partial labels never fall back to another button', () => {
  const root = scope([{ id: 'other', role: 'button', text: '不要查看证据' }])
  assert.throws(() => buttonByText(root, '查看证据').click(), /missing control/)
})
test('all audit button-name selectors use exact text without hidden controls or forced clicks', () => {
  const root = path.resolve(__dirname, '../e2e/audit')
  for (const file of ['fixture.js', 'audit-console.browser.test.js', 'candidate-preview.browser.test.js']) {
    const source = fs.readFileSync(path.join(root, file), 'utf8')
    assert.doesNotMatch(source, /getByRole\('button',/)
    assert.doesNotMatch(source, /includeHidden:\s*true|force:\s*true/)
    assert.match(source, /buttonByText\(/)
  }
})
