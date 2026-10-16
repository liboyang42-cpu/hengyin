const { test } = require('node:test')
const assert = require('node:assert/strict')

// BL-61:「你已拉黑对方」时,会话「更多」里的「拉黑」换成「解除拉黑」,
// 二次确认后调 POST /api/im/unblock,成功后以 /api/im/messages 回读恢复可发送。
function loadChat() {
  let def
  const requests = []
  const tips = []
  const dcCalls = []
  global.getApp = () => ({
    globalData: {}, sendRequest: o => requests.push(o), tips: t => tips.push(t),
    getUserID: () => 1, getRequestErrorMessage: (_r, f) => f,
  })
  global.wx = { getStorageSync: () => '' }
  global.Page = c => { def = c }
  const mod = require.resolve('../../subpackageB/pages/im/chat/index.js')
  delete require.cache[mod]
  require(mod)
  const page = Object.assign({}, def)
  page.data = { ...JSON.parse(JSON.stringify(def.data)), myId: 1, conversationId: 100 }
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page.scrollBottom = () => {}
  page.markRead = () => {}
  page.recoverPending = () => {}
  const dc = {
    open: k => dcCalls.push(['open', k]), busyOn: () => dcCalls.push(['busy']),
    done: () => dcCalls.push(['done']), failed: m => dcCalls.push(['failed', m]),
  }
  page.selectComponent = () => dc
  return { page, requests, tips, dcCalls }
}

function enterBlockedByMe(ctx) {
  ctx.page.loadMessages()
  ctx.requests.shift().success({ code: 200, data: { list: [{ id: 9, senderId: 2 }], blocked: true, blockedByMe: true } })
}

test('我拉黑了对方:更多菜单显示「解除拉黑」而不是「拉黑」', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  assert.equal(ctx.page._blockedByMe, true)
  assert.ok(ctx.page.data.moreSheetItems.includes('解除拉黑'))
  assert.ok(!ctx.page.data.moreSheetItems.includes('拉黑'))
})

test('对方拉黑了我:不给「解除拉黑」(解不了别人的拉黑)', () => {
  const ctx = loadChat()
  ctx.page.loadMessages()
  ctx.requests.shift().success({ code: 200, data: { list: [], blocked: true, blockedByMe: false } })
  assert.ok(!ctx.page.data.moreSheetItems.includes('解除拉黑'))
})

test('解除拉黑:先二次确认,确认后才发 /api/im/unblock,成功后回读恢复可发送', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  ctx.page.onMoreSelect({ detail: { label: '解除拉黑' } })
  assert.deepEqual(ctx.dcCalls[0], ['open', 'im.unblock'])
  assert.equal(ctx.requests.length, 0, '未确认前不发请求')
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  const req = ctx.requests.shift()
  assert.equal(req.url, '/api/im/unblock')
  assert.equal(req.method, 'POST')
  assert.deepEqual(req.data, { target_member_id: 2 })
  req.success({ code: 200, msg: '已取消' })
  assert.equal(ctx.page.data.disabled, true, '不本地乐观解禁,等回读')
  const reread = ctx.requests.shift()
  assert.equal(reread.url, '/api/im/messages')
  reread.success({ code: 200, data: { list: [{ id: 9, senderId: 2 }], blocked: false, blockedByMe: false } })
  assert.equal(ctx.page.data.disabled, false)
  assert.equal(ctx.page._blockedByMe, false)
  assert.ok(ctx.page.data.moreSheetItems.includes('拉黑'))
})

test('解除拉黑:业务失败如实提示,不解禁', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  ctx.page.doUnblock()
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  ctx.requests.shift().success({ code: 500, msg: '操作失败，请稍后重试' })
  assert.deepEqual(ctx.dcCalls[ctx.dcCalls.length - 1], ['failed', '操作失败，请稍后重试'])
  assert.equal(ctx.page.data.disabled, true)
  assert.equal(ctx.requests.length, 0, '失败不回读成功态')
})

test('解除后对方仍拉黑我:回读后仍禁用,文案换成对方不可联系', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  ctx.page.doUnblock()
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  ctx.requests.shift().success({ code: 200 })
  ctx.requests.shift().success({ code: 200, data: { list: [], blocked: true, blockedByMe: false } })
  assert.equal(ctx.page.data.disabled, true)
  assert.equal(ctx.page.data.disabledText, '对方暂不可联系，无法发送消息')
  assert.ok(ctx.page.data.moreSheetItems.includes('拉黑'))
})

test('取消确认:不发请求', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  ctx.page.doUnblock()
  ctx.page.onDangerCancel({ detail: { key: 'im.unblock' } })
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  assert.equal(ctx.requests.length, 0)
})

// #104 监督 🟡 + BL-154 发现:「已解除拉黑」只在回读确认后显示;断网 = 结果未知,不说「失败」并回读。
function confirmUnblock(ctx) {
  enterBlockedByMe(ctx)
  ctx.page.doUnblock()
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  return ctx.requests.shift()
}
const kinds = ctx => ctx.dcCalls.map(c => c[0])

test('解除拉黑:unblock 200 后、回读确认前不显示完成;回读 blockedByMe=false 才完成', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).success({ code: 200 })
  assert.ok(!kinds(ctx).includes('done'))
  ctx.requests.shift().success({ code: 200, data: { list: [], blocked: false, blockedByMe: false } })
  assert.deepEqual(ctx.dcCalls[ctx.dcCalls.length - 1], ['done'])
})

test('解除拉黑:回读仍 blockedByMe=true 时不显示完成,如实提示', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).success({ code: 200 })
  ctx.requests.shift().success({ code: 200, data: { list: [], blocked: true, blockedByMe: true } })
  assert.ok(!kinds(ctx).includes('done'))
  assert.match(ctx.dcCalls[ctx.dcCalls.length - 1][1], /仍显示你已拉黑对方/)
  assert.equal(ctx.page.data.disabled, true)
})

test('解除拉黑:回读失败 = 结果未确认,不显示完成', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).success({ code: 200 })
  ctx.requests.shift().fail({ errMsg: 'request:fail timeout' })
  assert.ok(!kinds(ctx).includes('done'))
  assert.match(ctx.dcCalls[ctx.dcCalls.length - 1][1], /结果未确认/)
})

test('解除拉黑:断网提示结果未确认(不说失败)并回读,可再次确认', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).fail({ errMsg: 'request:fail timeout' })
  const last = ctx.dcCalls[ctx.dcCalls.length - 1]
  assert.equal(last[0], 'failed')
  assert.match(last[1], /结果未确认/)
  assert.doesNotMatch(last[1], /失败/)
  assert.equal(ctx.requests.shift().url, '/api/im/messages')
  assert.equal(ctx.page.data.disabled, true)
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  assert.equal(ctx.requests.shift().url, '/api/im/unblock')
})

// BL-176:回读途中视图失效(切会话/切账号)时确认框按「结果未确认」落定,不停在进行中。
test('解除拉黑:回读返回前切走会话 → 按结果未确认落定,不显示完成', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).success({ code: 200 })
  const reread = ctx.requests.shift()
  ctx.page.data.conversationId = 101
  reread.success({ code: 200, data: { list: [], blocked: false, blockedByMe: false } })
  assert.ok(!kinds(ctx).includes('done'))
  const last = ctx.dcCalls[ctx.dcCalls.length - 1]
  assert.equal(last[0], 'failed')
  assert.match(last[1], /结果未确认/)
  assert.equal(ctx.page._loadSettle, null)
})

test('解除拉黑:回读断网且视图已失效 → 同样落定', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).success({ code: 200 })
  const reread = ctx.requests.shift()
  ctx.page._disposed = true
  reread.fail({ errMsg: 'request:fail timeout' })
  assert.equal(ctx.dcCalls[ctx.dcCalls.length - 1][0], 'failed')
})

test('解除拉黑:unblock 成功返回时视图已失效 → 不发回读,直接按结果未确认落定', () => {
  const ctx = loadChat()
  const req = confirmUnblock(ctx)
  ctx.page.data.conversationId = 101
  req.success({ code: 200 })
  assert.equal(ctx.requests.length, 0)
  assert.match(ctx.dcCalls[ctx.dcCalls.length - 1][1], /结果未确认/)
})
