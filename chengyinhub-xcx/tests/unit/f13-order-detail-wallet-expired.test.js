const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')
const DIR = 'subpackageMember/components/cy/scene-member-order-detail/'

/* F13 / OR-012=B(业主):过期票不进票夹,只在订单详情「凭证与有效期」区说一句「已过期，票夹不再显示」。
   判断用服务端 usableState —— 与票夹 usableTicket 同一口径,不在客户端另算过期。 */
test('订单详情:过期票写「已过期，票夹不再显示」,口径取服务端 usableState', () => {
  const js = read(DIR + 'index.js')
  const wxml = read(DIR + 'index.wxml')
  assert.match(js, /walletExpired: info\.usableState === 'EXPIRED'/)
  assert.match(wxml, /<view class="orow" wx:if="\{\{walletExpired\}\}">\s*<text class="orow-k">票夹<\/text>\s*<text class="orow-v">已过期，票夹不再显示<\/text>/)
  assert.match(wxml, /<block wx:if="\{\{verificationTimeText \|\| expiresAtText \|\| walletExpired \|\| partyText\}\}">/,
    '只有过期这一行时「凭证与有效期」区也要出')
  // 票夹那边同一字段
  assert.match(read('subpackageMember/signup/index.js'), /if \(item\.usableState\) return item\.usableState !== 'EXPIRED';/)
})

test('订单详情:已完成的单不再显示「可免费取消至…」', () => {
  const js = read(DIR + 'index.js')
  assert.match(js, /\['refunding', 'refunded', 'cancelled', 'expired', 'completed'\]\.includes\(info\.orderStateKey\)\) \{\s*info\.refundDeadlineDisplay = '';/)
})

test('订单详情:亲子票写「1 大 1 小（儿童 N 岁）」,非亲子票不出这一行', () => {
  const js = read(DIR + 'index.js')
  const src = js.slice(js.indexOf('function partyText'))
  const partyText = new Function(src + '\nreturn partyText;')()
  assert.equal(partyText({ adultCount: 1, childCount: 1, childAge: 7 }), '1 大 1 小（儿童 7 岁）')
  assert.equal(partyText({ adultCount: 1, childCount: 0 }), '')
  assert.equal(partyText(null), '')
  assert.match(read(DIR + 'index.wxml'), /<text class="orow-k">亲子票<\/text>\s*<text class="orow-v">\{\{partyText\}\}<\/text>/)
})
