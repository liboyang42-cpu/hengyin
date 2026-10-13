const test = require('node:test')
const assert = require('node:assert/strict')

function harness() {
  let definition; let memberId = 8
  const requests = []; const tips = []; const opens = []
  global.getApp = () => ({ getUserID: () => memberId, globalData: {},
    sendRequest: request => requests.push(request), tips: text => tips.push(text) })
  global.Page = page => { definition = page }
  global.wx = {}
  const file = require.resolve('../../subpackageB/pages/im/chat/index.js')
  delete require.cache[file]; require(file)
  const page = Object.assign({}, definition)
  page.data = Object.assign(JSON.parse(JSON.stringify(definition.data)), {
    conversationId: 7, myId: 8, loadState: 'ready', msgs: [],
  })
  page.setData = patch => Object.assign(page.data, patch)
  page.selectComponent = () => ({ open: key => opens.push(key), busyOn() {}, done() {}, failed() {} })
  page.startPoll = () => {}; page.stopPoll = () => {}; page.markRead = () => {}
  return { page, requests, tips, opens, account: id => { memberId = id } }
}
const response = (id = 42, type = 1, conversationId = 7) => ({
  code: 200, data: [{ conversationId, type, counterparty: { id } }],
})
const confirm = h => h.page.onDangerConfirm({ detail: { key: 'im.block' } })

for (const messages of [[], [{ id: 3, senderId: 8 }], [{ id: 4, senderId: 99 }], [null]]) {
  test(`拉黑对象来自本人的会话接口，不依赖消息 ${JSON.stringify(messages)}`, () => {
    const h = harness(); h.page.data.msgs = messages; h.page.doBlock()
    assert.equal(h.requests.length, 1)
    assert.equal(h.requests[0].url, '/api/im/conversations')
    assert.deepEqual(h.opens, [])
    confirm(h); assert.equal(h.requests.length, 1)
    h.requests[0].success(response())
    assert.deepEqual(h.opens, ['im.block'])
    assert.equal(h.page._pendingBlock.targetId, 42)
    confirm(h)
    assert.equal(h.requests[1].url, '/api/im/block')
    assert.deepEqual(h.requests[1].data, { target_member_id: 42 })
  })
}

for (const value of [
  { code: 500, msg: '读取失败' }, { code: 200, data: null }, { code: 200, data: {} },
  { code: 200, data: [] }, response(42, 1, 77), response(0), response(8),
  response(-1), response('bad'), response(true), response(' 42'), response('42.5'), response({}),
  response(42, 2), response(42, 3), response(42, 4),
  { code: 200, data: [null] }, { code: 200, data: [{ conversationId: 7, type: 1 }] },
]) {
  test(`会话读取无有效单聊对象时不从消息猜测 ${JSON.stringify(value)}`, () => {
    const h = harness(); h.page.data.msgs = [{ id: 4, senderId: 99 }]
    h.page.doBlock(); assert.equal(h.requests[0].url, '/api/im/conversations')
    h.requests[0].success(value); confirm(h)
    assert.equal(h.requests.length, 1); assert.deepEqual(h.opens, [])
    assert.equal(h.page._pendingBlock, null); assert.equal(h.tips.length, 1)
    assert.equal(h.page.data.disabled, false)
  })
}

for (const change of ['account', 'conversation', 'unload', 'cancel', 'clear']) {
  for (const outcome of ['success', 'business', 'network']) {
    test(`拉黑对象读取 ${change} 后 ${outcome} 不打开旧确认或提示`, () => {
      const h = harness(); h.page.data.msgs = [{ id: 4, senderId: 99 }]
      h.page.doBlock(); const read = h.requests[0]
      assert.equal(read.url, '/api/im/conversations')
      if (change === 'account') h.account(10)
      if (change === 'conversation') h.page.data.conversationId = 9
      if (change === 'unload') h.page.onUnload()
      if (change === 'cancel') h.page.onDangerCancel({ detail: { key: 'im.block' } })
      if (change === 'clear') h.page.clearConversation()
      const before = [...h.opens]
      if (outcome === 'network') read.fail()
      else read.success(outcome === 'success' ? response() : { code: 500, msg: '旧失败' })
      assert.deepEqual(h.opens, before); assert.deepEqual(h.tips, [])
      assert.equal(h.requests.length, 1); assert.equal(h.page.data.disabled, false)
    })
  }
}

test('对象读取单飞，当前失败反馈并允许重试', () => {
  const h = harness(); h.page.doBlock(); h.page.doBlock()
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].silentError, true)
  h.requests[0].fail(); assert.equal(h.tips.length, 1); assert.equal(h.page._pendingBlock, null)
  h.page.doBlock(); assert.equal(h.requests.length, 2)
  h.requests[1].success(response()); assert.deepEqual(h.opens, ['im.block'])
})

for (const outcome of ['success', 'network']) {
  test(`对象读取隐藏期间 ${outcome} 等原页面可见才反馈`, () => {
    const h = harness(); h.page.doBlock(); assert.equal(h.requests[0].url, '/api/im/conversations')
    h.page.onHide()
    if (outcome === 'success') h.requests[0].success(response())
    else h.requests[0].fail()
    assert.deepEqual(h.opens, []); assert.deepEqual(h.tips, [])
    confirm(h); assert.equal(h.requests.length, 1)
    h.page.onShow()
    assert.equal(h.opens.length, outcome === 'success' ? 1 : 0)
    assert.equal(h.tips.length, outcome === 'network' ? 1 : 0)
    h.page.onShow()
    assert.equal(h.opens.length, outcome === 'success' ? 1 : 0)
    assert.equal(h.tips.length, outcome === 'network' ? 1 : 0)
  })
}

test('切换会话后新对象请求不被旧对象回包完成', () => {
  const h = harness(); h.page.data.msgs = [{ id: 4, senderId: 99 }]
  h.page.doBlock(); assert.equal(h.requests[0].url, '/api/im/conversations')
  h.page.data.conversationId = 9; h.page.doBlock()
  h.requests[0].success(response()); assert.deepEqual(h.opens, [])
  h.requests[1].success(response(43, 1, 9)); assert.equal(h.page._pendingBlock.targetId, 43)
  confirm(h); assert.equal(h.requests[2].data.target_member_id, 43)
})
