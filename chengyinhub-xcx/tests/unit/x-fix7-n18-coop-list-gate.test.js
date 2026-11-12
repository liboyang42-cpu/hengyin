const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

// x-fix7 N-18：没有商家身份的俱乐部主理人不请求商家专用的 /api/coop/list（否则控制台 403）；
// 有商家身份时仍请求，且保留 x-fix3 对 403 的「没有合作邀约」处理。
const js = fs.readFileSync(path.join(__dirname, '../../pages/club/detail/index.js'), 'utf8')
const start = js.indexOf('  loadMessages() {')
const body = js.slice(start, js.indexOf('  applySentInviteFilter() {', start))

test('loadMessages 在发请求前用 roleGuard 商家身份闸，无商家身份直接按无邀约落 ready', () => {
  assert.match(js, /const \{ isClubLeader, isMerchantView \} = require\('\.\.\/\.\.\/\.\.\/utils\/roleGuard\.js'\)/)
  const gate = body.indexOf('isMerchantView()')
  const req = body.indexOf("url: '/api/coop/list'")
  assert.ok(gate > 0 && gate < req, '商家身份闸必须在请求之前')
  const gated = body.slice(gate, req)
  assert.match(gated, /_sentInviteRows = \[\]/)
  assert.match(gated, /messageState: 'ready'/)
  assert.match(gated, /return;/)
})

test('x-fix3 的 403 当无合作邀约处理仍在', () => {
  assert.match(body, /fail\(res, statusCode\) \{[\s\S]*?readCoopListResult\(res, statusCode\)/)
})
