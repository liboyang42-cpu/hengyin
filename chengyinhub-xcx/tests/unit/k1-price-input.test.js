// 第十二轮 k1 第 9 项(j1 N2):39.99 不能变成 3999;发布活动票价用带小数点的键盘
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { typingPrice, isValidPrice } = require('../../utils/price-input.js')

test('逐字敲 39.99 每一步都保留小数点,最终是 39.99', () => {
  let shown = ''
  for (const ch of '39.99') shown = typingPrice(shown + ch)
  assert.equal(shown, '39.99')
  assert.equal(typingPrice('39.999'), '39.99', '最多两位小数')
  assert.equal(typingPrice('3.9.9'), '3.99', '只留一个小数点')
  assert.equal(typingPrice('¥12a'), '12')
})

test('校验只认整数或一到两位小数', () => {
  for (const ok of ['0', '39', '39.9', '39.99']) assert.ok(isValidPrice(ok), ok)
  for (const bad of ['', '39.', '.5', '39.999', '-1', 'abc']) assert.ok(!isValidPrice(bad), bad)
})

test('发布活动的票价输入是 digit 键盘,打字时不 Number()', () => {
  const root = path.join(__dirname, '../..')
  const wxml = fs.readFileSync(path.join(root, 'pages/publish/activity/index.wxml'), 'utf8')
  assert.match(wxml, /<input type="digit" placeholder="请输入价格"/)
  const js = fs.readFileSync(path.join(root, 'pages/publish/activity/index.js'), 'utf8')
  assert.match(js, /field === 'price' \? priceInput\.typingPrice\(value\)/)
  const invite = fs.readFileSync(path.join(root, 'pages/coop/invite/index.js'), 'utf8')
  assert.match(invite, /priceInput\.typingPrice\(e\.detail\.value\)/)
})
