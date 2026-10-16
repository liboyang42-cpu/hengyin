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
  assert.equal(ctx.page.data.blockedByMe, true)
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
  assert.equal(ctx.page.data.blockedByMe, false)
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
