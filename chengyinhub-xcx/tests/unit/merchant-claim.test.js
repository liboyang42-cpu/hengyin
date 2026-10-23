const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const c = require('../../pages/merchant/utils/merchant-claim.js')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

const good = { merchantId: 9, requestId: 'r1', contactName: '张三', contactPhone: '13800000000', statement: '', proofUrls: ['https://x/a.png'] }

test('validate:逐项拦截并放行合法表单', () => {
  assert.equal(c.validate(good), '')
  assert.match(c.validate({ ...good, merchantId: null }), /选择/)
  assert.match(c.validate({ ...good, contactName: ' ' }), /联系人/)
  assert.match(c.validate({ ...good, contactPhone: 'abc' }), /电话/)
  assert.match(c.validate({ ...good, proofUrls: [] }), /至少/)
  assert.match(c.validate({ ...good, proofUrls: new Array(11).fill('u') }), /最多/)
  assert.match(c.validate({ ...good, statement: 'x'.repeat(1001) }), /1000/)
  assert.match(c.validate({ ...good, requestId: '' }), /操作失败/)
})

test('buildSubmitBody 对齐 MerchantClaimDTO.SubmitRequest', () => {
  const b = c.buildSubmitBody({ ...good, contactName: ' 张三 ', proofUrls: ['u1', '', 'u2'] })
  assert.deepEqual(Object.keys(b).sort(), ['contactName', 'contactPhone', 'merchantId', 'proofs', 'requestId', 'statement'])
  assert.equal(b.contactName, '张三')
  assert.deepEqual(b.proofs, [{ proofType: 'PHOTO', assetId: 'u1' }, { proofType: 'PHOTO', assetId: 'u2' }])
})

test('describe 四态:none / pending / approved / rejected(带原因)', () => {
  assert.equal(c.describe([]).state, 'none')
  assert.equal(c.describe(null).state, 'none')
  assert.equal(c.describe([{ id: 1, status: 'PENDING', merchantId: 5 }]).state, 'pending')
  assert.equal(c.describe([{ id: 1, status: 'REJECTED' }, { id: 2, status: 'PENDING' }]).state, 'pending')
  assert.equal(c.describe([{ id: 1, status: 'REJECTED' }, { id: 2, status: 'APPROVED', merchantId: 7 }]).merchantId, 7)
  const r = c.describe([{ id: 1, status: 'REJECTED', auditReason: '证明不清晰' }, { id: 3, status: 'REJECTED', auditReason: '电话打不通' }])
  assert.equal(r.state, 'rejected')
  assert.equal(r.reason, '电话打不通', '取最新一条')
  assert.match(c.describe([{ id: 1, status: 'REJECTED' }]).reason, /未填写/)
})

test('requestId 每次不同；错误文案优先用服务端 msg', () => {
  assert.notEqual(c.newRequestId(1, 0.1), c.newRequestId(1, 0.2))
  assert.equal(c.submitErrorMessage({ code: 409, msg: '该商家已有所有者' }), '该商家已有所有者')
  assert.match(c.submitErrorMessage({ code: 400 }), /不完整/)
  assert.match(c.submitErrorMessage(null), /稍后/)
})

test('申请页接入认领 sheet,未新增页面也未改 app.json', () => {
  assert.match(read('pages/merchant/apply/index.wxml'), /<cy-merchant-claim-sheet show="\{\{claimShow\}\}"/)
  assert.match(read('pages/merchant/apply/index.js'), /openClaim\(\)/)
  const sheet = read('pages/merchant/components/merchant-claim-sheet/index.js')
  assert.match(sheet, /\/api\/merchant\/claims\/status/)
  assert.match(sheet, /\/api\/merchant\/claims\/submit/)
  assert.match(read('pages/merchant/components/merchant-claim-sheet/index.wxml'), /<page-container/)
})
