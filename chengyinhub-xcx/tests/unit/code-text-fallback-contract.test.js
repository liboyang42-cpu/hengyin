const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
// 主包余量只有 ~1.7 KB:code-text 只被 pages/merchant 与 pages/topic 两个分包用,各放一份逐字节相同的副本,主包零字节
const MERCHANT_COPY = 'pages/merchant/utils/code-text.js'
const TOPIC_COPY = 'pages/topic/utils/code-text.js'
const ct = require('../../' + MERCHANT_COPY)
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('code-text 两个分包副本逐字节一致,且主包里没有 utils/code-text.js / components/cy/code-text', () => {
  assert.equal(read(MERCHANT_COPY), read(TOPIC_COPY))
  assert.equal(fs.existsSync(path.join(__dirname, '../..', 'utils/code-text.js')), false)
  assert.equal(fs.existsSync(path.join(__dirname, '../..', 'components/cy/code-text')), false)
})

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
  assert.match(read('pages/merchant/citynode/index.js'), /require\('\.\.\/utils\/code-text\.js'\)/)
  assert.match(read('pages/merchant/citynode/index.js'), /codeTextUtil\.pickCode/)
  assert.match(read('pages/merchant/citynode/index.wxml'), /cy-code-text/)
  assert.match(read('pages/merchant/citynode/index.json'), /"\/pages\/merchant\/components\/cy\/code-text\/index"/)
  assert.match(read('pages/merchant/components/cy/code-text/index.js'), /require\('\.\.\/\.\.\/\.\.\/utils\/code-text\.js'\)/)
  const mi = read('pages/topic/merchantinfo/merchantinfo.js')
  assert.match(mi, /require\('\.\.\/utils\/code-text\.js'\)/)
  assert.match(mi, /codeTextUtil\.usable\(data\)/)
  assert.match(read('pages/topic/merchantinfo/merchantinfo.wxml'), /bindlongpress="copyCheckinCode"/)
  assert.doesNotMatch(read('pages/club/group-code/index.js'), /code-text/)
})
