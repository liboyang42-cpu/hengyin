const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { readCoopListResult } = require('../../utils/coop-invite-view.js')

// x-fix3 C-1：俱乐部主理人读 /api/coop/list 遇商家身份 403 = 没有合作邀约，不能把「管理」tab 卡成「重试管理进度」。

test('商家身份 403（回包 code 或 HTTP 状态）按没有合作邀约处理', () => {
  assert.deepEqual(readCoopListResult({ code: 403, msg: '商家身份无效或权限不足' }), { received: [], sent: [] })
  assert.deepEqual(readCoopListResult({ code: 403, msg: '', roleViewSuppressed: true }, 403), { received: [], sent: [] })
  assert.deepEqual(readCoopListResult(undefined, 403), { received: [], sent: [] })
})

test('成功回包原样取出 received/sent，其他失败仍是 null', () => {
  const ok = readCoopListResult({ code: 200, data: { received: [{ id: 1 }], sent: [] } })
  assert.deepEqual(ok, { received: [{ id: 1 }], sent: [] })
  assert.equal(readCoopListResult({ code: 500, msg: '服务异常' }), null)
  assert.equal(readCoopListResult({ code: 200, data: { received: [] } }), null)
  assert.equal(readCoopListResult({ errMsg: 'request:fail timeout' }), null)
  assert.equal(readCoopListResult(undefined, 502), null)
})

test('俱乐部详情 loadMessages 的 success 与 fail 都走同一判定', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/club/detail/index.js'), 'utf8')
  const start = js.indexOf('  loadMessages() {')
  const body = js.slice(start, js.indexOf('  applySentInviteFilter() {', start))
  assert.match(body, /const list = readCoopListResult\(res\);/)
  assert.match(body, /fail\(res, statusCode\) \{[\s\S]*?readCoopListResult\(res, statusCode\)[\s\S]*?messageState: 'ready'/)
})
