const { test } = require('node:test')
const assert = require('node:assert/strict')

// BL-154：subpackageB/pages/im/chat/index.js 的 _confirmUnblock 页面处理器单测
// 依赖 #104（BL-61），须在其后合并；不改被测代码。
// 要求：回读确认之前不得显示「已解除拉黑」（danger-actions 'im.unblock'.done.title）；失败如实提示。
// 暴露的现有缺陷以 todo 标注，并报 #104。
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
    done: t => dcCalls.push(['done', t]), failed: m => dcCalls.push(['failed', m]),
  }
  page.selectComponent = () => dc
  return { page, requests, tips, dcCalls }
}

function enterBlockedByMe(ctx) {
  ctx.page.loadMessages()
  ctx.requests.shift().success({ code: 200, data: { list: [{ id: 9, senderId: 2 }], blocked: true, blockedByMe: true } })
}

function confirmUnblock(ctx) {
  ctx.page.doUnblock()
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  return ctx.requests.shift()
}

const doneCalls = ctx => ctx.dcCalls.filter(c => c[0] === 'done')

test('_confirmUnblock: 没有待解除对象时不发请求、不置忙', () => {
  const ctx = loadChat()
  ctx.page._pendingUnblockId = 0
  ctx.page._confirmUnblock()
  assert.equal(ctx.requests.length, 0)
  assert.deepEqual(ctx.dcCalls, [])
})

test('_confirmUnblock: 发请求前先置忙，请求在途时不显示完成', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  const req = confirmUnblock(ctx)
  assert.equal(req.url, '/api/im/unblock')
  assert.deepEqual(ctx.dcCalls.map(c => c[0]), ['open', 'busy'])
  assert.equal(doneCalls(ctx).length, 0)
})

test('_confirmUnblock: 业务失败如实展示后端文案，不显示完成、不回读、保留待解除对象以便重试', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  confirmUnblock(ctx).success({ code: 500, msg: '操作失败，请稍后重试' })
  assert.deepEqual(ctx.dcCalls[ctx.dcCalls.length - 1], ['failed', '操作失败，请稍后重试'])
  assert.equal(doneCalls(ctx).length, 0)
  assert.equal(ctx.requests.length, 0)
  assert.equal(ctx.page._pendingUnblockId, 2)
  assert.equal(ctx.page.data.blockedByMe, true)
})

test('_confirmUnblock: 业务失败且无 msg 时用兜底文案', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  confirmUnblock(ctx).success({ code: 403 })
  assert.deepEqual(ctx.dcCalls[ctx.dcCalls.length - 1], ['failed', '解除拉黑失败，请重试'])
  assert.equal(doneCalls(ctx).length, 0)
})

test('_confirmUnblock: 网络失败不显示完成，状态不变', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  confirmUnblock(ctx).fail({ errMsg: 'request:fail timeout' })
  assert.equal(ctx.dcCalls[ctx.dcCalls.length - 1][0], 'failed')
  assert.equal(doneCalls(ctx).length, 0)
  assert.equal(ctx.page.data.blockedByMe, true)
  assert.equal(ctx.page.data.disabled, true)
})

test('_confirmUnblock: 成功后发 /api/im/messages 回读，以回读值恢复可发送', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  confirmUnblock(ctx).success({ code: 200 })
  assert.equal(ctx.page._pendingUnblockId, 0)
  const reread = ctx.requests.shift()
  assert.equal(reread.url, '/api/im/messages')
  reread.success({ code: 200, data: { list: [{ id: 9, senderId: 2 }], blocked: false, blockedByMe: false } })
  assert.equal(ctx.page.data.disabled, false)
  assert.equal(ctx.page.data.blockedByMe, false)
})

test('_confirmUnblock: 视图已切换（会话变了）时成功回包不回读到新会话', () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  const req = confirmUnblock(ctx)
  ctx.page.data.conversationId = 200
  req.success({ code: 200 })
  assert.equal(ctx.requests.length, 0)
})

test('_confirmUnblock: 回读确认之前不得显示「已解除拉黑」', {
  todo: '#104 现状：/api/im/unblock 回 200 即调 dc.done()（标题取 danger-actions im.unblock.done「已解除拉黑」），早于 /api/im/messages 回读；已报 #104',
}, () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  confirmUnblock(ctx).success({ code: 200 })
  assert.equal(doneCalls(ctx).length, 0, 'unblock 回包后、回读前不应显示完成')
  ctx.requests.shift().success({ code: 200, data: { list: [], blocked: false, blockedByMe: false } })
  assert.equal(doneCalls(ctx).length, 1, '回读确认 blockedByMe=false 后才显示完成')
})

test('_confirmUnblock: 回读仍为 blockedByMe=true 时不得显示「已解除拉黑」', {
  todo: '#104 现状：done 已在回读前显示，回读不一致时无法撤回；已报 #104',
}, () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  confirmUnblock(ctx).success({ code: 200 })
  ctx.requests.shift().success({ code: 200, data: { list: [], blocked: true, blockedByMe: true } })
  assert.equal(doneCalls(ctx).length, 0)
})

test('_confirmUnblock: 网络失败（结果未知）文案不应断言「失败」', {
  todo: '#104 现状：fail() 提示「解除拉黑失败，请重试」，但请求可能已到达服务端；应提示结果未确认并回读；已报 #104',
}, () => {
  const ctx = loadChat()
  enterBlockedByMe(ctx)
  confirmUnblock(ctx).fail({ errMsg: 'request:fail timeout' })
  const last = ctx.dcCalls[ctx.dcCalls.length - 1]
  assert.doesNotMatch(last[1], /失败/)
})
