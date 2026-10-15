const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const ct = require('../../utils/code-text.js')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('pickCode / usable:有图或有文本码即可用', () => {
  assert.equal(ct.pickCode({ code: ' ab12 ' }), 'ab12')
  assert.equal(ct.pickCode({ code: 123 }), '123')
  assert.equal(ct.pickCode({}), '')
  assert.equal(ct.usable({ qrcodeUrl: '', code: 'x' }), true)
  assert.equal(ct.usable({ qrcodeUrl: ' https://a/b.png ' }), true)
  assert.equal(ct.usable({ qrcodeUrl: '', code: '' }), false)
  assert.equal(ct.usable(null), false)
})

test('copy 走 setClipboardData,空码不复制', () => {
  const calls = []
  const env = { setClipboardData: (o) => calls.push(o) }
  assert.equal(ct.copy(' abc ', env), true)
  assert.deepEqual(calls[0], { data: 'abc' })
  assert.equal(ct.copy('', env), false)
})

test('据点打卡码/商家站点打卡码接入文本兜底；团码刻意不接入(CU-C-78 签名令牌只扫图)', () => {
  assert.match(read('pages/merchant/citynode/index.js'), /codeTextUtil\.pickCode/)
  assert.match(read('pages/merchant/citynode/index.wxml'), /cy-code-text/)
  const mi = read('pages/topic/merchantinfo/merchantinfo.js')
  assert.match(mi, /codeTextUtil\.usable\(data\)/)
  assert.match(read('pages/topic/merchantinfo/merchantinfo.wxml'), /bindlongpress="copyCheckinCode"/)
  assert.doesNotMatch(read('pages/club/group-code/index.js'), /code-text/)
})
