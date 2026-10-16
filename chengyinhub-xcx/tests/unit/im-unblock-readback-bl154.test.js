const { test } = require('node:test')
const assert = require('node:assert/strict')

// BL-154:为 #104(BL-61)subpackageB/pages/im/chat/index.js 的 _confirmUnblock 补处理器单测,不改被测代码。
// 依赖 #104,须在其后合并。与 im-unblock-bl61.test.js(菜单/确认/回读主流程)不重复,只补回读时序与失败分支。
function loadChat() {
  let def
  const requests = []
  const dcCalls = []
  global.getApp = () => ({
    globalData: {}, sendRequest: o => requests.push(o), tips: () => {},
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
  return { page, requests, dcCalls }
}

function confirmUnblock(ctx) {
  ctx.page.loadMessages()
  ctx.requests.shift().success({ code: 200, data: { list: [{ id: 9, senderId: 2 }], blocked: true, blockedByMe: true } })
  ctx.page.doUnblock()
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  return ctx.requests.shift()
}

const kinds = calls => calls.map(c => c[0])

test('确认后先进入忙碌态,请求带对方 id;回包前不显示任何结果', () => {
  const ctx = loadChat()
  const req = confirmUnblock(ctx)
  assert.equal(req.url, '/api/im/unblock')
  assert.deepEqual(req.data, { target_member_id: 2 })
  assert.deepEqual(kinds(ctx.dcCalls), ['open', 'busy'])
})

test('断网:如实提示「解除拉黑失败，请重试」,不回读、不解禁,可再次确认', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).fail()
  assert.deepEqual(ctx.dcCalls[ctx.dcCalls.length - 1], ['failed', '解除拉黑失败，请重试'])
  assert.ok(!kinds(ctx.dcCalls).includes('done'))
  assert.equal(ctx.requests.length, 0)
  assert.equal(ctx.page.data.disabled, true)
  ctx.page.onDangerConfirm({ detail: { key: 'im.unblock' } })
  assert.equal(ctx.requests.shift().url, '/api/im/unblock', '失败后保留待解除对象,可重试')
})

test('业务失败且无 msg:用兜底文案,不显示成功', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).success({ code: 403 })
  assert.deepEqual(ctx.dcCalls[ctx.dcCalls.length - 1], ['failed', '解除拉黑失败，请重试'])
  assert.ok(!kinds(ctx.dcCalls).includes('done'))
})

test('成功后离开页面:不再回读(视图守卫生效)', () => {
  const ctx = loadChat()
  const req = confirmUnblock(ctx)
  ctx.page.stopPoll = () => {}
  ctx.page.onUnload()
  req.success({ code: 200 })
  assert.ok(!ctx.requests.some(r => r.url === '/api/im/messages'))
})

test('成功后回读 /api/im/messages,以回读结果决定可发送', () => {
  const ctx = loadChat()
  confirmUnblock(ctx).success({ code: 200 })
  const reread = ctx.requests.shift()
  assert.equal(reread.url, '/api/im/messages')
  assert.equal(ctx.page.data.disabled, true, '回读前仍按旧状态禁用')
  reread.success({ code: 200, data: { list: [], blocked: false, blockedByMe: false } })
  assert.equal(ctx.page.data.disabled, false)
})

// 现状:dc.done()(danger-actions「已解除拉黑」)在 /api/im/unblock 返回 200 时立即调用,早于回读。
// BL-154 要求「回读确认之前不得显示已解除拉黑」——此断言暴露该缺陷,按卡片要求用 todo 标注,报回 #104,不改被测代码。
test('回读确认之前不得显示「已解除拉黑」', { todo: '#104 在回读前调用 dc.done(),待 #104 挪到回读成功之后' }, () => {
  const ctx = loadChat()
  confirmUnblock(ctx).success({ code: 200 })
  assert.ok(!kinds(ctx.dcCalls).includes('done'), 'unblock 200 后、回读前不应 done')
  ctx.requests.shift().success({ code: 200, data: { list: [], blocked: false, blockedByMe: false } })
  assert.ok(kinds(ctx.dcCalls).includes('done'), '回读确认已解除后再 done')
})
