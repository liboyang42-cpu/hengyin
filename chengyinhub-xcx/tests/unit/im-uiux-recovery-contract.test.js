'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const LIST_MODULE = path.join(ROOT, 'subpackageB/pages/im/list/index.js')
const CHAT_MODULE = path.join(ROOT, 'subpackageB/pages/im/chat/index.js')
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
const merchantAccess = { active: true, merchant: { id: 7 }, roleCode: 'MERCHANT_OWNER', permissions: ['merchant:project:manage'] }

function loadPage(modulePath) {
  const requests = []
  const toasts = []
  let definition
  let userId = 8
  const app = {
    globalData: {},
    getUserID: () => userId,
    getAvatar: () => '',
    tips: (text) => toasts.push(text),
    getRequestErrorMessage: (response, fallback) => (response && (response.msg || response.errMsg)) || fallback,
    sendRequest: (options) => requests.push(options),
  }
  global.getApp = () => app
  global.Page = (config) => { definition = config }
  const storage = new Map()
  global.wx = {
    getStorageSync: key => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key),
    showToast: (options) => toasts.push(options && options.title),
    // 商家视角页会切原生导航栏配色(utils/merchant-theme.js)
    setNavigationBarColor: () => {},
  }
  delete require.cache[require.resolve(modulePath)]
  require(modulePath)
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) {
      Object.keys(patch).forEach(key => {
        const match = /^(list|viewList)\[(\d+)\]\.(\w+)$/.exec(key)
        if (match) this.data[match[1]][Number(match[2])][match[3]] = patch[key]
        else this.data[key] = patch[key]
      })
      if (done) done.call(this)
    },
  })
  return { page, requests, toasts, app, setUserId: (id) => { userId = id } }
}

test('消息列表静默刷新失败保留旧会话，并给原位重试而不是整页错误', () => {
  const h = loadPage(LIST_MODULE)
  const old = [{ conversationId: 1, unread: 0, type: 1 }]
  h.page.data.loaded = true
  h.page.data.list = old
  h.page.data.viewList = old
  h.page.loadConversations(true)
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].hideLoading, true)
  assert.equal(h.requests[0].silentError, true)
  h.requests[0].fail({ msg: '刷新失败，请稍后重试' })
  assert.equal(h.page.data.error, false)
  assert.deepEqual(h.page.data.list, old)
  assert.deepEqual(h.toasts, [])
})

test('消息列表只接受最新请求，迟到失败不能覆盖新列表', () => {
  const h = loadPage(LIST_MODULE)
  h.page.loadConversations()
  h.page.loadConversations()
  assert.equal(h.requests.length, 2)
  h.requests[1].success({ code: 200, data: [] })
  h.requests[0].fail({ msg: '旧请求失败' })
  assert.equal(h.page.data.loaded, true)
  assert.equal(h.page.data.error, false)
})

for (const method of ['loadConversations', 'loadMerchantEntry']) {
  for (const invalidation of ['账号变化', '页面卸载']) {
    for (const callback of ['success', 'fail']) {
      test(`${method} ${invalidation}后的${callback}不能写页面`, () => {
        const h = loadPage(LIST_MODULE)
        if (method === 'loadMerchantEntry') h.page.data.isMerchantView = true // G-4(2026-10-27):竞猜待答入口只在商家视角查
        h.page[method]()
        if (invalidation === '账号变化') h.setUserId(9)
        else if (h.page.onUnload) h.page.onUnload()
        const before = JSON.stringify(h.page.data)
        let writes = 0
        h.page.setData = () => { writes++ }
        h.requests[0][callback]({ code: 200, data: method === 'loadConversations'
          ? [{ conversationId: 42, unread: 1, type: 1 }]
          : merchantAccess })
        assert.equal(writes, 0)
        assert.equal(JSON.stringify(h.page.data), before)
      })
    }
  }
  test(`${method} 当前账号正常回包仍可更新`, () => {
    const h = loadPage(LIST_MODULE)
    if (method === 'loadMerchantEntry') h.page.data.isMerchantView = true // G-4(2026-10-27):竞猜待答入口只在商家视角查
    h.page[method]()
    h.requests[0].success({ code: 200, data: method === 'loadConversations'
      ? [{ conversationId: 42, unread: 1, type: 1 }]
      : merchantAccess })
    if (method === 'loadConversations') assert.equal(h.page.data.list[0].conversationId, 42)
    else {
      // owner 2026-10-22:身份通过后再问待答列表,有待答才出这一行
      const inbox = h.requests.find((r) => r.url === '/api/merchant/predict/inbox')
      assert.ok(inbox, '商家身份通过后要读待答列表')
      inbox.success({ code: 200, data: [{ roundId: 1 }] })
      assert.equal(h.page.data.canSettlePredict, true)
    }
  })
}

// G-4 / P-46(owner 2026-10-27 p89):玩家视角不查商家身份,竞猜待答入口永不出现;
// 合作邀请被请求层标成 roleViewSuppressed 时按「没有邀请」处理,不出「商家身份无效」横幅。
test('玩家视角:不查商家入口,合作邀请的商家报错不出横幅', () => {
  const h = loadPage(LIST_MODULE)
  h.page.loadMerchantEntry()
  assert.equal(h.requests.length, 0)
  assert.equal(h.page.data.canSettlePredict, false)
  h.page.loadInvites()
  h.requests[0].success({ code: 403, msg: '', roleViewSuppressed: true })
  assert.equal(h.page.data.inviteError, '')
  assert.deepEqual(h.page.data.invitesIn, [])
  h.page.loadInvites()
  h.requests[1].fail({ code: 403, msg: '', roleViewSuppressed: true }, 403)
  assert.equal(h.page.data.inviteError, '')
})

// owner 2026-10-22:没有待答时「竞猜待答」整行不出;待答读失败时照样出(提醒有 48 小时期限,不能悄悄消失)
for (const [label, respond, expected] of [
  ['没有待答 → 不出', (r) => r.success({ code: 200, data: [] }), false],
  ['有待答 → 出', (r) => r.success({ code: 200, data: [{ roundId: 3 }] }), true],
  ['待答读失败 → 照样出', (r) => r.fail({ errMsg: 'request:fail' }), true],
]) {
  test(`竞猜待答入口:${label}`, () => {
    const h = loadPage(LIST_MODULE)
    h.page.data.isMerchantView = true // G-4(2026-10-27):竞猜待答入口只在商家视角查
    h.page.loadMerchantEntry()
    h.requests[0].success({ code: 200, data: merchantAccess })
    respond(h.requests.find((r) => r.url === '/api/merchant/predict/inbox'))
    assert.equal(h.page.data.canSettlePredict, expected)
  })
}

for (const callback of ['success', 'fail']) {
  test(`商家入口旧${callback}不能覆盖新读取`, () => {
    const h = loadPage(LIST_MODULE)
    h.page.data.isMerchantView = true // G-4(2026-10-27):竞猜待答入口只在商家视角查
    h.page.loadMerchantEntry()
    h.page.loadMerchantEntry()
    h.requests[1].success({ code: 200, data: merchantAccess })
    h.requests[0][callback]({ code: 200, data: { active: false } })
    h.requests.find((r) => r.url === '/api/merchant/predict/inbox').success({ code: 200, data: [{ roundId: 1 }] })
    assert.equal(h.page.data.canSettlePredict, true)
  })
}

function listActionHarness() {
  const h = loadPage(LIST_MODULE)
  h.page.data.list = [{ conversationId: 42, type: 1, unread: 2, muted: 0 }]
  h.page.refreshView()
  h.effects = { writes: 0, loads: 0, danger: [] }
  const setData = h.page.setData
  h.page.setData = function (patch) { h.effects.writes++; setData.call(this, patch) }
  h.page.loadConversations = () => { h.effects.loads++ }
  h.page.selectComponent = () => ({
    open: key => h.effects.danger.push(['open', key]),
    busyOn: () => h.effects.danger.push(['busy']),
    done: () => h.effects.danger.push(['done']),
    failed: text => h.effects.danger.push(['failed', text]),
  })
  h.event = { currentTarget: { dataset: { id: 42, index: 0 } } }
  h.confirm = { detail: { key: 'im.conversation.delete' } }
  return h
}

function startListAction(h, action) {
  if (action === 'delete') { h.page.onRowDelete(h.event); h.page.onDangerConfirm(h.confirm) }
  else h.page[action === 'read' ? 'onRowRead' : 'onRowMute'](h.event)
}

function finishListAction(request, outcome) {
  if (outcome === 'network') request.fail()
  else request.success({ code: outcome === 'ok' ? 200 : 500, msg: '业务失败' })
  if (request.complete) request.complete()
}

for (const action of ['read', 'mute', 'delete']) {
  for (const invalidation of ['account', 'unload']) {
    for (const outcome of ['ok', 'business', 'network']) {
      test(`消息列表${action} ${invalidation}后${outcome}回包不能改页或给回执`, () => {
        const h = listActionHarness()
        startListAction(h, action)
        if (invalidation === 'account') h.setUserId(9)
        else h.page.onUnload()
        const before = JSON.stringify(h.page.data)
        h.effects.writes = 0; h.effects.loads = 0; h.effects.danger = []; h.toasts.length = 0
        finishListAction(h.requests[0], outcome)
        assert.equal(h.effects.writes, 0)
        assert.equal(h.effects.loads, 0)
        assert.deepEqual(h.effects.danger, [])
        assert.deepEqual(h.toasts, [])
        assert.equal(JSON.stringify(h.page.data), before)
      })
    }
  }
  test(`消息列表${action}当前账号正常成功仍有回执`, () => {
    const h = listActionHarness()
    startListAction(h, action)
    finishListAction(h.requests[0], 'ok')
    if (action === 'read') assert.equal(h.page.data.list[0].unread, 0)
    if (action === 'mute') { assert.equal(h.page.data.list[0].muted, 1); assert.ok(h.toasts.includes('已开启免打扰')) }
    if (action === 'delete') { assert.deepEqual(h.page.data.list, []); assert.ok(h.effects.danger.some(x => x[0] === 'done')) }
  })
}

for (const action of ['read', 'mute']) {
  for (const outcome of ['ok', 'business', 'network']) {
    test(`消息列表${action}新列表已回读后忽略旧${outcome}`, () => {
      const h = listActionHarness()
      startListAction(h, action)
      h.page.data.list = [{ conversationId: 42, type: 1, unread: 5, muted: 1 }]
      h.page.refreshView()
      const before = JSON.stringify(h.page.data); h.effects.writes = 0; h.toasts.length = 0
      finishListAction(h.requests[0], outcome)
      assert.equal(h.effects.writes, 0)
      assert.deepEqual(h.toasts, [])
      assert.equal(JSON.stringify(h.page.data), before)
    })
  }
}

for (const action of ['mute', 'delete']) {
  test(`消息列表${action}执行中重复点击只发一批`, () => {
    const h = listActionHarness()
    startListAction(h, action)
    if (action === 'delete') h.page.onDangerConfirm(h.confirm)
    else startListAction(h, action)
    assert.equal(h.requests.length, 1)
  })
  test(`消息列表${action}当前失败后可重试`, () => {
    const h = listActionHarness()
    startListAction(h, action)
    finishListAction(h.requests[0], 'network')
    if (action === 'delete') h.page.onDangerConfirm(h.confirm)
    else startListAction(h, action)
    assert.equal(h.requests.length, 2)
  })
}

for (const invalidation of ['account', 'unload']) {
  test(`删除会话确认在${invalidation}后不得发出写请求`, () => {
    const h = listActionHarness()
    h.page.onRowDelete(h.event)
    if (invalidation === 'account') h.setUserId(9)
    else h.page.onUnload()
    h.page.onDangerConfirm(h.confirm)
    assert.equal(h.requests.length, 0)
  })
}

for (const outcome of ['ok', 'business', 'network']) {
  test(`旧删除${outcome}不能结束另一会话的新确认`, () => {
    const h = listActionHarness()
    startListAction(h, 'delete')
    h.page.data.list.push({ conversationId: 43, type: 1, unread: 1, muted: 0 })
    h.page.onRowDelete({ currentTarget: { dataset: { id: 43, index: 1 } } })
    const pending = h.page._pendingDelete
    h.effects.danger = []; h.effects.writes = 0
    finishListAction(h.requests[0], outcome)
    assert.equal(h.page._pendingDelete, pending)
    assert.equal(h.effects.writes, 0)
    assert.deepEqual(h.effects.danger, [])
  })
}

test('免打扰入口旧行号指向另一个会话时不发送', () => {
  const h = listActionHarness()
  h.page.data.viewList = [{ conversationId: 43, muted: 1 }]
  h.page.onRowMute(h.event)
  assert.equal(h.requests.length, 0)
})

test('单条已读回包按当前可见会话关闭操作栏', () => {
  const h = listActionHarness()
  h.page.onRowRead(h.event)
  const other = { conversationId: 43, offset: -100, _open: true }
  h.page.data.list.push(other)
  h.page.data.viewList = [other, h.page.data.list[0]]
  h.page.refreshView = () => {}
  h.requests[0].success({ code: 200 })
  assert.equal(h.page.data.viewList[0].offset, -100)
  assert.equal(h.page.data.viewList[0]._open, true)
})

for (const action of ['read', 'mute', 'delete']) {
  test(`消息列表${action}不能对上个账号尚未刷新的列表发起操作`, () => {
    const h = listActionHarness()
    h.page._conversationMemberId = 8
    h.setUserId(9)
    h.effects.writes = 0; h.effects.danger = []
    startListAction(h, action)
    assert.equal(h.requests.length, 0)
    assert.equal(h.effects.writes, 0)
    assert.deepEqual(h.effects.danger, [])
  })
}

for (const changed of [false, true]) {
  test(`消息列表返回${changed ? '新' : '同'}账号时${changed ? '清掉旧列表再读' : '保留确认列表静默读'}`, () => {
    const h = loadPage(LIST_MODULE)
    h.page.loadConversations()
    h.requests[0].success({ code: 200, data: [{ conversationId: 42, type: 1, unread: 2 }] })
    if (changed) h.setUserId(9)
    h.page.onShow()
    // onShow:会话重读 + 合作邀请重读(收件箱/发件箱),共 2 个新请求;requests[1] 仍是会话重读。
    // 2026-10-27 G-4:玩家视角不再查商家入口(/api/merchant/access/me),原来的第 3 个新请求按 owner 决定去掉。
    assert.equal(h.requests.length, 3)
    if (changed) {
      assert.deepEqual(h.page.data.list, [])
      assert.deepEqual(h.page.data.viewList, [])
      assert.deepEqual(h.page.data.invitesIn, [])
      assert.equal(h.page.data.loaded, false)
      h.requests[1].success({ code: 200, data: [{ conversationId: 43, type: 1, unread: 1 }] })
      assert.equal(h.page.data.list[0].conversationId, 43)
    } else {
      assert.equal(h.page.data.list[0].conversationId, 42)
      assert.equal(h.page.data.refreshing, true)
    }
  })
}

test('聊天记录加载更多单飞，失败保留现有消息与同一游标重试入口', () => {
  const h = loadPage(CHAT_MODULE)
  const old = [{ id: 9, content: '已确认消息' }]
  h.page.data.conversationId = 7
  h.page.data.myId = 8
  h.page.data.hasMore = true
  h.page.data.cursor = 42
  h.page.data.msgs = old
  h.page.loadMore()
  h.page.loadMore()
  assert.equal(h.requests.length, 1)
  assert.equal(h.page.data.loadingMore, true)
  assert.equal(h.requests[0].silentError, true)
  h.requests[0].fail({ msg: '更早消息暂时不可用' })
  assert.equal(h.page.data.loadingMore, false)
  assert.equal(h.page.data.loadMoreError, '更早消息暂时不可用')
  assert.deepEqual(h.page.data.msgs, old)
  assert.equal(h.page.data.cursor, 42)
  assert.deepEqual(h.toasts, [])
})

test('聊天首载在卸载后迟到不得 setData 或标已读', () => {
  const h = loadPage(CHAT_MODULE)
  Object.assign(h.page.data, { conversationId: 7, myId: 8 })
  h.page.scrollBottom = () => {}
  h.page.loadMessages(true)
  h.page.onUnload()
  const before = JSON.stringify(h.page.data)
  let setDataCalls = 0
  h.page.setData = () => { setDataCalls++ }
  h.requests[0].success({ code: 200, data: {
    list: [{ id: 70, senderId: 9, msgType: 1, content: '迟到消息' }],
    hasMore: false,
  } })
  assert.equal(setDataCalls, 0)
  assert.equal(JSON.stringify(h.page.data), before)
  assert.equal(h.requests.filter((request) => request.url === '/api/im/read').length, 0)
})

test('聊天分页响应在会话变更后不得污染新会话', () => {
  const h = loadPage(CHAT_MODULE)
  const old = [{ id: 9, content: '当前会话消息' }]
  Object.assign(h.page.data, {
    conversationId: 7,
    myId: 8,
    hasMore: true,
    cursor: 42,
    msgs: old,
  })
  h.page.loadMore()
  h.page.data.conversationId = 8
  let setDataCalls = 0
  h.page.setData = () => { setDataCalls++ }
  h.requests[0].success({ code: 200, data: {
    list: [{ id: 1, senderId: 9, msgType: 1, content: '旧会话历史' }],
    hasMore: false,
  } })
  assert.equal(setDataCalls, 0)
  assert.deepEqual(h.page.data.msgs, old)
})

test('隐藏取消旧轮询，返回可见后从原水位重新补消息并只标读一次', () => {
  const h = loadPage(CHAT_MODULE)
  Object.assign(h.page.data, { conversationId: 7, myId: 8, loadState: 'ready', msgs: [] })
  h.page._pollWatermark = 0
  h.page.pollNew()
  h.page.onHide()
  h.requests[0].success({ code: 200, data: {
    list: [{ id: 80, senderId: 9, msgType: 1, content: '隐藏期间到达' }], hasMore: false,
  } })
  assert.deepEqual(h.page.data.msgs, [])
  assert.equal(h.page._pollWatermark, 0)
  assert.equal(h.requests.filter((request) => request.url === '/api/im/read').length, 0)
  h.page.startPoll = () => {}
  h.page.onShow()
  h.page.pollNew()
  h.requests[1].success({ code: 200, data: {
    list: [{ id: 80, senderId: 9, msgType: 1, content: '隐藏期间到达' }], hasMore: false,
  } })
  assert.deepEqual(h.page.data.msgs.map(message => message.id), [80])
  assert.equal(h.requests.filter((request) => request.url === '/api/im/read').length, 1)
})

test('轮询响应在账号变更后不得 setData 或标已读', () => {
  const h = loadPage(CHAT_MODULE)
  Object.assign(h.page.data, { conversationId: 7, myId: 8, loadState: 'ready', msgs: [] })
  h.page.pollNew()
  h.setUserId(9)
  let setDataCalls = 0
  h.page.setData = () => { setDataCalls++ }
  h.requests[0].success({ code: 200, data: {
    list: [{ id: 90, senderId: 9, msgType: 1, content: '旧账号消息' }],
  } })
  assert.equal(setDataCalls, 0)
  assert.equal(h.requests.filter((request) => request.url === '/api/im/read').length, 0)
})

test('关闭的组局不是网络错误：展示终态并提供返回消息列表入口', () => {
  const h = loadPage(CHAT_MODULE)
  h.page._setMessageLoadError({ errorCode: 'HANGOUT_CLOSED', msg: '该组局已结束，聊天已关闭' })
  assert.equal(h.page.data.loadState, 'closed')
  assert.match(read('subpackageB/pages/im/chat/index.wxml'), /loadState === 'closed'/)
  assert.match(read('subpackageB/pages/im/chat/index.wxml'), /返回消息列表/)
  assert.match(read('subpackageB/pages/im/chat/index.js'), /returnToMessageList/)
  const destinations = []
  global.wx.redirectTo = ({ url }) => destinations.push(url)
  h.page.returnToMessageList()
  assert.deepEqual(destinations, ['/subpackageB/pages/im/list/index'])
  h.page._setMessageLoadError({ errorCode: 'HANGOUT_CLOSED', returnPath: '/subpackageB/pages/im/list/index?closed=1' })
  h.page.returnToMessageList()
  assert.equal(destinations[1], '/subpackageB/pages/im/list/index?closed=1')
})

test('路线选择器把畸形或失败载荷显示为错误，不冒充搜索空结果', () => {
  const malformed = chatExtraHarness()
  malformed.page.loadRoutes()
  malformed.requests[0].success({ code: 200, data: { rows: {} } })
  assert.equal(malformed.page.data.routeLoading, false)
  assert.notEqual(malformed.page.data.routeError, '')
  assert.deepEqual(malformed.page.data.routeList, [])

  const failed = chatExtraHarness()
  failed.page.loadRoutes()
  failed.requests[0].fail({ msg: '路线加载失败' })
  assert.equal(failed.page.data.routeError, '路线加载失败')
})

function chatExtraHarness() {
  const h = loadPage(CHAT_MODULE)
  Object.assign(h.page.data, { conversationId: 7, myId: 8, loadState: 'ready', routePickerOpen: true })
  h.page.scrollBottom = () => {}
  return h
}

function invalidateChatExtra(h, change) {
  if (change === 'account') h.setUserId(9)
  if (change === 'conversation') h.page.data.conversationId = 17
  if (change === 'unload') h.page.onUnload()
  if (change === 'close') h.page.closeRoutePicker()
  if (change === 'hide') h.page.onHide()
  if (change === 'closed') h.page.data.loadState = 'closed'
}

for (const change of ['account', 'conversation', 'unload', 'close', 'hide', 'closed']) {
  for (const outcome of ['rows', 'malformed', 'network']) {
    test(`聊天路线 ${change} 后 ${outcome} 旧回包不写页面`, () => {
      const h = chatExtraHarness()
      h.page.loadRoutes()
      invalidateChatExtra(h, change)
      const before = JSON.stringify(h.page.data)
      let writes = 0; h.page.setData = () => { writes++ }
      if (outcome === 'network') h.requests[0].fail({ msg: '旧失败' })
      else h.requests[0].success({ code: 200, data: { rows: outcome === 'rows' ? [{ id: 42, name: '旧路线' }] : {} } })
      assert.equal(writes, 0)
      assert.equal(JSON.stringify(h.page.data), before)
    })
  }
}

for (const action of ['onSendLocation', 'onPickImage', 'openRoutePicker']) {
  for (const change of ['account', 'unload', 'closed']) {
    test(`聊天附加入口 ${action} 在 ${change} 后不打开选择器或请求`, () => {
      const h = chatExtraHarness()
      let opened = 0
      global.wx.chooseLocation = () => { opened++ }
      h.app.chooseImage = () => { opened++ }
      invalidateChatExtra(h, change)
      let writes = 0; h.page.setData = () => { writes++ }
      h.page[action]()
      assert.equal(opened, 0)
      assert.equal(h.requests.length, 0)
      assert.equal(writes, 0)
    })
  }
}

for (const change of ['account', 'conversation', 'unload', 'closed']) {
  for (const outcome of ['success', 'fail']) {
    test(`聊天位置 ${change} 后 ${outcome} 不能发送或提示旧结果`, () => {
      const h = chatExtraHarness()
      let picker
      global.wx.chooseLocation = options => { picker = options }
      h.page.onSendLocation()
      invalidateChatExtra(h, change)
      const before = JSON.stringify(h.page.data)
      picker[outcome](outcome === 'success'
        ? { name: '位置', address: '地址', latitude: 31, longitude: 121 }
        : { errMsg: 'chooseLocation:fail auth deny' })
      assert.equal(h.requests.length, 0)
      assert.deepEqual(h.toasts, [])
      assert.equal(JSON.stringify(h.page.data), before)
    })
  }
}

test('正常位置选择隐藏返回仍发送原会话卡片，取消不提示', () => {
  const h = chatExtraHarness()
  let picker; global.wx.chooseLocation = options => { picker = options }
  h.page.onSendLocation()
  h.page.onHide()
  picker.fail({ errMsg: 'chooseLocation:fail cancel' })
  assert.deepEqual(h.toasts, [])
  picker.success({ name: '位置', address: '地址', latitude: 31, longitude: 121 })
  assert.equal(h.requests.length, 1)
  assert.equal(h.requests[0].url, '/api/im/send')
  assert.equal(h.requests[0].data.conversation_id, 7)
  assert.deepEqual(JSON.parse(h.requests[0].data.extra_json), { cardType: 'location', name: '位置', address: '地址', lat: 31, lng: 121 })
})

test('路线新搜索结果保留，当前失败可重试并选真实结果', () => {
  const h = chatExtraHarness()
  h.page.loadRoutes(); h.page.data.routeKeyword = '新'; h.page.loadRoutes()
  h.requests[1].success({ code: 200, data: { rows: [{ id: 42, name: '新路线' }] } })
  h.requests[0].fail({ msg: '旧错误' })
  assert.equal(h.page.data.routeList[0].id, 42)
  assert.equal(h.page.data.routeError, '')
  h.page.retryRoutes(); h.requests[2].fail({ msg: '当前失败' })
  assert.equal(h.page.data.routeError, '当前失败')
  h.page.retryRoutes(); h.requests[3].success({ code: 200, data: { rows: [{ id: 42 }] } })
  h.page.onRoutePick({ currentTarget: { dataset: { id: '42' } } })
  assert.equal(h.requests[4].data.conversation_id, 7)
  assert.equal(JSON.parse(h.requests[4].data.extra_json).topicId, '42')
  assert.equal(h.page.data.routePickerOpen, false)
})

test('关闭路线选择器取消防抖，重开后旧读取不能覆盖新结果', t => {
  const h = chatExtraHarness()
  let scheduled; const timer = {}; const cleared = []
  t.mock.method(global, 'setTimeout', fn => { scheduled = fn; return timer })
  t.mock.method(global, 'clearTimeout', handle => { cleared.push(handle) })
  h.page.loadRoutes()
  h.page.onRouteSearch({ detail: { value: '旧搜索' } })
  h.page.closeRoutePicker()
  assert.ok(cleared.includes(timer))
  scheduled()
  assert.equal(h.requests.length, 1)
  h.page.openRoutePicker()
  h.requests[1].success({ code: 200, data: { rows: [{ id: 43 }] } })
  h.requests[0].success({ code: 200, data: { rows: [{ id: 42 }] } })
  assert.deepEqual(h.page.data.routeList, [{ id: 43 }])
})

test('路线输入防抖等待期间旧响应和旧行点击都不能使用', t => {
  const h = chatExtraHarness()
  let scheduled; t.mock.method(global, 'setTimeout', fn => { scheduled = fn })
  h.page.loadRoutes()
  h.page.data.routeList = [{ id: 42 }]
  h.page.onRouteSearch({ detail: { value: '新关键词' } })
  h.requests[0].success({ code: 200, data: { rows: [{ id: 42 }] } })
  assert.deepEqual(h.page.data.routeList, [])
  h.page.onRoutePick({ currentTarget: { dataset: { id: 42 } } })
  assert.equal(h.requests.length, 1)
  scheduled()
  assert.equal(h.requests[1].data.keyword, '新关键词')
  h.requests[1].success({ code: 200, data: { rows: [{ id: 43 }] } })
  assert.deepEqual(h.page.data.routeList, [{ id: 43 }])
})

function chatMutationHarness() {
  const h = loadPage(CHAT_MODULE)
  const sendRequest = h.app.sendRequest
  h.app.sendRequest = request => {
    if (request.url === '/api/im/conversations') {
      request.success({ code: 200, data: [{ conversationId: h.page.data.conversationId, type: 1, counterparty: { id: 9 } }] })
    } else sendRequest(request)
  }
  Object.assign(h.page.data, { conversationId: 7, myId: 8, loadState: 'ready', msgs: [{ id: 90, senderId: 9 }] })
  h.effects = { writes: 0, danger: [], backs: [], redirects: [] }
  const setData = h.page.setData
  h.page.setData = function (patch) { h.effects.writes++; setData.call(this, patch) }
  h.page.selectComponent = () => ({
    open: key => h.effects.danger.push(['open', key]),
    busyOn: () => h.effects.danger.push(['busy']),
    done: () => h.effects.danger.push(['done']),
    failed: text => h.effects.danger.push(['failed', text]),
  })
  global.wx.navigateBack = options => h.effects.backs.push(options)
  global.wx.redirectTo = options => h.effects.redirects.push(options.url)
  return h
}

const chatMutationKey = action => action === 'clear' ? 'im.conversation.delete' : 'im.block'
function startChatMutation(h, action, confirm = true) {
  h.page[action === 'report' ? 'doReport' : action === 'clear' ? 'clearConversation' : 'doBlock']()
  if (action !== 'report' && confirm) h.page.onDangerConfirm({ detail: { key: chatMutationKey(action) } })
}
function finishChatMutation(request, outcome) {
  if (outcome === 'network') request.fail()
  else request.success({ code: outcome === 'ok' ? 200 : 500, msg: '业务失败' })
}

for (const action of ['report', 'block', 'clear']) {
  for (const change of ['account', 'conversation', 'unload']) {
    for (const outcome of ['ok', 'business', 'network']) {
      test(`聊天${action} ${change} 后 ${outcome} 回包不改页提示确认或导航`, () => {
        const h = chatMutationHarness()
        startChatMutation(h, action)
        invalidateChatExtra(h, change)
        const before = JSON.stringify(h.page.data)
        h.effects.writes = 0; h.effects.danger = []
        finishChatMutation(h.requests[0], outcome)
        assert.equal(h.effects.writes, 0)
        assert.deepEqual(h.effects.danger, [])
        assert.deepEqual(h.effects.backs, [])
        assert.deepEqual(h.effects.redirects, [])
        assert.deepEqual(h.toasts, [])
        assert.equal(JSON.stringify(h.page.data), before)
      })
    }
  }
  for (const change of ['account', 'unload']) {
    test(`聊天${action} ${change} 后不发起旧页面操作`, () => {
      const h = chatMutationHarness()
      invalidateChatExtra(h, change)
      startChatMutation(h, action)
      assert.equal(h.requests.length, 0)
      assert.deepEqual(h.effects.danger, [])
      assert.deepEqual(h.toasts, [])
    })
  }
  test(`聊天${action} 当前成功保留正常回执`, () => {
    const h = chatMutationHarness()
    startChatMutation(h, action)
    finishChatMutation(h.requests[0], 'ok')
    if (action === 'report') assert.deepEqual(h.toasts, ['举报已提交'])
    else assert.ok(h.effects.danger.some(x => x[0] === 'done'))
    if (action === 'block') assert.equal(h.page.data.disabled, true)
    if (action === 'clear') assert.equal(h.effects.backs.length, 1)
  })
  test(`聊天${action} 重复入口和确认只发一次`, () => {
    const h = chatMutationHarness()
    startChatMutation(h, action)
    startChatMutation(h, action)
    assert.equal(h.requests.length, 1)
  })
  for (const outcome of ['business', 'network']) {
    test(`聊天${action} 当前 ${outcome} 失败保留原数据且可重试`, () => {
      const h = chatMutationHarness()
      startChatMutation(h, action)
      finishChatMutation(h.requests[0], outcome)
      assert.equal(h.page.data.disabled, false)
      assert.equal(h.page.data.msgs[0].id, 90)
      assert.deepEqual(h.effects.backs, [])
      assert.ok(h.toasts.length || h.effects.danger.some(x => x[0] === 'failed'))
      if (action === 'report') h.page.doReport()
      else h.page.onDangerConfirm({ detail: { key: chatMutationKey(action) } })
      assert.equal(h.requests.length, 2)
      assert.deepEqual(h.requests[0].data, h.requests[1].data)
    })
  }
  test(`聊天${action} 只由绑定上下文的本地处理器反馈网络错误`, () => {
    const h = chatMutationHarness()
    startChatMutation(h, action)
    assert.equal(h.requests[0].silentError, true)
    assert.equal(typeof h.requests[0].fail, 'function')
  })
}

for (const action of ['block', 'clear']) {
  for (const change of ['account', 'conversation', 'unload']) {
    test(`聊天${action} 确认前 ${change} 不能发旧确认`, () => {
      const h = chatMutationHarness()
      startChatMutation(h, action, false)
      invalidateChatExtra(h, change)
      h.page.onDangerConfirm({ detail: { key: chatMutationKey(action) } })
      assert.equal(h.requests.length, 0)
    })
  }
  test(`聊天${action} 取消后不能用旧确认发送`, () => {
    const h = chatMutationHarness()
    startChatMutation(h, action, false)
    h.page.onDangerCancel({ detail: { key: chatMutationKey(action) } })
    h.page.onDangerConfirm({ detail: { key: chatMutationKey(action) } })
    assert.equal(h.requests.length, 0)
  })
  for (const outcome of ['ok', 'business', 'network']) {
    test(`聊天${action} 旧 ${outcome} 不能完成后来会话的新确认`, () => {
      const h = chatMutationHarness()
      startChatMutation(h, action)
      h.page.data.conversationId = 17
      startChatMutation(h, action, false)
      h.effects.danger = []; h.effects.writes = 0
      finishChatMutation(h.requests[0], outcome)
      assert.deepEqual(h.effects.danger, [])
      assert.equal(h.effects.writes, 0)
      assert.deepEqual(h.effects.backs, [])
      h.page.onDangerConfirm({ detail: { key: chatMutationKey(action) } })
      assert.equal(h.requests.length, 2)
      if (action === 'clear') assert.equal(h.requests[1].data.conversation_id, 17)
    })
  }
}

for (const change of ['account', 'unload']) {
  for (const method of ['onMore', 'onMoreSelect']) {
    test(`聊天更多菜单 ${method} 在 ${change} 后不写旧页面`, () => {
      const h = chatMutationHarness()
      invalidateChatExtra(h, change)
      h.effects.writes = 0
      h.page[method]({ detail: { label: '清空聊天' } })
      assert.equal(h.effects.writes, 0)
      assert.deepEqual(h.effects.danger, [])
      assert.equal(h.requests.length, 0)
    })
  }
}

for (const change of ['account', 'conversation', 'unload']) {
  test(`清空返回失败后的 ${change} 不能重定向后来页面`, () => {
    const h = chatMutationHarness()
    startChatMutation(h, 'clear')
    finishChatMutation(h.requests[0], 'ok')
    assert.equal(h.effects.backs.length, 1)
    invalidateChatExtra(h, change)
    h.effects.backs[0].fail()
    assert.deepEqual(h.effects.redirects, [])
  })
}

for (const outcome of ['ok', 'business', 'network']) {
  test(`举报旧 ${outcome} 不阻塞或完成后来会话的新举报`, () => {
    const h = chatMutationHarness()
    startChatMutation(h, 'report')
    h.page.data.conversationId = 17; h.page.data.msgs = [{ id: 91, senderId: 19 }]
    startChatMutation(h, 'report')
    assert.equal(h.requests.length, 2)
    finishChatMutation(h.requests[0], outcome)
    assert.deepEqual(h.toasts, [])
    finishChatMutation(h.requests[1], 'ok')
    assert.deepEqual(h.toasts, ['举报已提交'])
  })
}

for (const action of ['block', 'clear']) {
  test(`清空返回失败时新的 ${action} 确认不能被旧导航带走`, () => {
    const h = chatMutationHarness()
    startChatMutation(h, 'clear')
    finishChatMutation(h.requests[0], 'ok')
    startChatMutation(h, action, false)
    h.effects.backs[0].fail()
    assert.deepEqual(h.effects.redirects, [])
  })
}

test('隐藏期间清空成功延后返回，只在原页面重新可见时返回一次', () => {
  const h = chatMutationHarness()
  h.page.startPoll = () => {}
  startChatMutation(h, 'clear')
  h.page.onHide()
  finishChatMutation(h.requests[0], 'ok')
  assert.equal(h.effects.backs.length, 0)
  h.page.onShow()
  assert.equal(h.effects.backs.length, 1)
  h.page.onShow()
  assert.equal(h.effects.backs.length, 1)
})

for (const change of ['account', 'conversation']) {
  test(`隐藏期间清空成功，${change} 后恢复不能退出新页面`, () => {
    const h = chatMutationHarness()
    h.page.startPoll = () => {}
    startChatMutation(h, 'clear')
    h.page.onHide()
    finishChatMutation(h.requests[0], 'ok')
    invalidateChatExtra(h, change)
    h.page.onShow()
    assert.equal(h.effects.backs.length, 0)
  })
}

for (const outcome of ['ok', 'business', 'network']) {
  test(`隐藏期间举报 ${outcome} 只在原页面回来后反馈一次`, () => {
    const h = chatMutationHarness()
    h.page.startPoll = () => {}
    startChatMutation(h, 'report')
    h.page.onHide()
    finishChatMutation(h.requests[0], outcome)
    assert.deepEqual(h.toasts, [])
    h.page.onShow()
    assert.equal(h.toasts.length, 1)
    assert.equal(h.toasts[0], outcome === 'ok' ? '举报已提交' : outcome === 'business' ? '业务失败' : '提交失败，请重试')
    h.page.onShow()
    assert.equal(h.toasts.length, 1)
  })
  for (const change of ['account', 'conversation']) {
    test(`隐藏期间举报 ${outcome}，${change} 后回来不提示旧结果`, () => {
      const h = chatMutationHarness()
      h.page.startPoll = () => {}
      startChatMutation(h, 'report')
      h.page.onHide()
      finishChatMutation(h.requests[0], outcome)
      invalidateChatExtra(h, change)
      h.page.onShow()
      assert.deepEqual(h.toasts, [])
    })
  }
}

for (const action of ['block', 'clear']) {
  test(`聊天${action} 失败弹窗关闭的真实done事件清除旧确认`, () => {
    const h = chatMutationHarness()
    startChatMutation(h, action)
    finishChatMutation(h.requests[0], 'business')
    const markup = read('subpackageB/pages/im/chat/index.wxml')
    const binding = /<cy-danger-confirm\b[^>]*\bbind:done="([^"]+)"/.exec(markup)
    assert.ok(binding, '失败弹窗取消发done，必须接入页面取消处理')
    let definition
    global.Component = config => { definition = config }
    const modulePath = path.join(ROOT, 'components/cy/danger-confirm/index.js')
    delete require.cache[require.resolve(modulePath)]
    require(modulePath)
    const component = {
      data: { action: { key: chatMutationKey(action) }, stage: 'error' },
      setData(patch) { Object.assign(this.data, patch) },
      triggerEvent(name, detail) {
        assert.equal(name, 'done')
        h.page[binding[1]]({ detail })
      },
    }
    definition.methods.onCloseDone.call(component)
    assert.equal(component.data.stage, '')
    h.page.onDangerConfirm({ detail: { key: chatMutationKey(action) } })
    assert.equal(h.requests.length, 1)
  })
}

for (const change of ['close', 'account', 'closed', 'missing']) {
  test(`路线 ${change} 后旧行点击不能发送`, () => {
    const h = chatExtraHarness()
    h.page.data.routeList = [{ id: 42 }]
    if (change === 'missing') h.page.data.routeList = [{ id: 43 }]
    else invalidateChatExtra(h, change)
    h.page.onRoutePick({ currentTarget: { dataset: { id: 42 } } })
    assert.equal(h.requests.length, 0)
  })
}

test('消息页使用图标库与持久恢复组件，不再用文字或 CSS 伪造图标', () => {
  const listWxml = read('subpackageB/pages/im/list/index.wxml')
  const listJson = JSON.parse(read('subpackageB/pages/im/list/index.json'))
  const chatWxml = read('subpackageB/pages/im/chat/index.wxml')
  assert.equal(listJson.usingComponents['cy-inline-error'], '/components/cy/inline-error/index')
  assert.doesNotMatch(listWxml, /<cy-inline-error[^>]*staleError/)  // 刷新失败静默降级
  assert.match(listWxml, /<cy-icon name="bell"/)
  assert.match(listWxml, /<cy-icon[^>]*name="trash"/)
  assert.doesNotMatch(listWxml, /act-check|act-bell-body|class="bell"|mute-bell/)
  assert.match(chatWxml, /loadMoreError/)
  assert.match(chatWxml, /routeError/)
  assert.match(chatWxml, /<cy-icon name="plus"/)
  assert.match(chatWxml, /<cy-icon[^>]*name="pin"/)
  assert.match(chatWxml, /<cy-icon[^>]*name="warning"/)
  assert.doesNotMatch(chatWxml, /class="pin"|class="card-ico"|class="fail-bang"|<text>\+<\/text>/)
})

test('负控：把刷新失败横幅加回列表页必须判红', () => {
  const wxml = read('subpackageB/pages/im/list/index.wxml')
  assert.doesNotMatch(wxml, /<cy-inline-error[^>]*staleError/, '刷新失败静默降级')
  const broken = wxml.replace('</view>', '<cy-inline-error wx:if="{{staleError}}" /></view>')
  assert.notEqual(broken, wxml, '负控锚点失效')
  assert.match(broken, /<cy-inline-error[^>]*staleError/)
})

test('系统举报通知可回查完整结果，普通消息不能伪造结果入口', () => {
  const { page } = loadPage(CHAT_MODULE)
  Object.assign(page.data, { type: 2, myId: 8 })
  const result = { taskId: 40, bizId: 88, outcome: '举报成立，组局已关闭', reason: '扰民', followUp: '联系客服并提供任务编号' }
  page.data.msgs = [{ id: 51, senderId: 0, msgType: 3, card: { result } }]
  page.onCardTap({ currentTarget: { dataset: { msg: 51 } } })
  assert.deepEqual(page.data.reviewResult, result)
  page.closeReviewResult()
  assert.equal(page.data.reviewResult, null)
  page.data.msgs[0].senderId = 8
  page.onCardTap({ currentTarget: { dataset: { msg: 51 } } })
  assert.equal(page.data.reviewResult, null)
  assert.match(read('subpackageB/pages/im/chat/index.wxml'), /查看生成时记录/)
  assert.match(read('subpackageB/pages/im/chat/index.wxml'), /reviewResult.reason/)
})

test('消息内容给完整导航栏留位，不能把胶囊高度当导航栏高度', () => {
  const h = loadPage(LIST_MODULE)
  wx.getSystemInfoSync = () => ({ windowWidth: 390, statusBarHeight: 47 })
  wx.getMenuButtonBoundingClientRect = () => ({ height: 32, left: 280 })
  h.page.loadConversations = () => {}
  h.page.onLoad()
  assert.equal(h.page.data.navBarHeight, 44)
  assert.equal(h.page.data.statusBarHeight + h.page.data.navBarHeight, 91)
})

test('F-R2S-10 发送位置被拒绝授权时给出「去设置」恢复入口，授权后回到选址', () => {
  const LOCATION = path.join(ROOT, 'utils/location/location-manager.js')
  const manager = require(LOCATION)
  const realGuide = manager.showPermissionGuide
  const guides = []
  manager.showPermissionGuide = (env, onGranted) => guides.push(onGranted)
  try {
    const h = loadPage(CHAT_MODULE)
    const picks = []
    global.wx.chooseLocation = (options) => picks.push(options)
    h.page.data.myId = 8
    h.page._canSend = () => true
    h.page._isReadContextCurrent = () => true
    h.page.onSendLocation()
    assert.equal(picks.length, 1)
    picks[0].fail({ errMsg: 'chooseLocation:fail cancel' })
    assert.equal(guides.length, 0, '用户主动取消不打扰')
    picks[0].fail({ errMsg: 'chooseLocation:fail auth deny' })
    assert.equal(guides.length, 1, '权限被拒必须给出去设置入口')
    guides[0]()
    assert.equal(picks.length, 2, '授权后回到选址')
  } finally {
    manager.showPermissionGuide = realGuide
  }
})
