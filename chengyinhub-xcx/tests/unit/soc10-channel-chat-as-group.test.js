const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

// SOC-10 / SOC-4:小程序把频道当群打开 —— 不按「对方」拉黑锁输入栏,举报/拉黑针对具体消息的作者,
// 公告/归档频道给出真实的只读原因,已拉黑成员的消息折叠。
function loadChat(type) {
  let def
  const requests = []
  const tips = []
  const app = {
    globalData: {}, sendRequest: o => requests.push(o), tips: t => tips.push(t),
    getUserID: () => 1, getRequestErrorMessage: (_r, f) => f,
  }
  global.getApp = () => app
  global.wx = { getStorageSync: () => '' }
  global.Page = c => { def = c }
  const mod = require.resolve('../../subpackageB/pages/im/chat/index.js')
  delete require.cache[mod]
  require(mod)
  const page = Object.assign({}, def)
  page.data = JSON.parse(JSON.stringify(def.data))
  page.setData = function (patch) { Object.assign(this.data, patch) }
  page.scrollBottom = () => {}
  page.markRead = () => {}
  const dialogs = []
  page.selectComponent = () => ({ open: k => dialogs.push(k), busyOn() {}, done() {}, failed() {} })
  Object.assign(page.data, { myId: 1, conversationId: 100, type, groupLike: type === 4 || type === 5,
    moreSheetItems: ['举报这条消息', '拉黑该用户', '隐藏会话'] })
  return { page, requests, tips, dialogs }
}

test('频道打开即是群菜单(举报/拉黑都先选具体消息)', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../subpackageB/pages/im/chat/index.js'), 'utf8')
  assert.match(src, /const GROUP_SHEET_ITEMS = \['举报这条消息', '拉黑该用户', '隐藏会话'\];/)
  assert.match(src, /const groupLike = type === 4 \|\| type === 5;/)
  assert.match(src, /if \(groupLike\) this\.setData\(\{ moreSheetItems: GROUP_SHEET_ITEMS \}\);/)
  const wxml = fs.readFileSync(path.join(__dirname, '../../subpackageB/pages/im/chat/index.wxml'), 'utf8')
  assert.match(wxml, /<cy-option-sheet show="\{\{pickSheetShow\}\}"[^>]*bind:select="onPickSelect"/)
})

test('频道入口带 type=5', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/club/channels/index.js'), 'utf8')
  assert.match(src, /\/subpackageB\/pages\/im\/chat\/index\?conversationId=' \+ id \+ '&type=5&name=/)
})

test('频道里服务端的 blocked 不锁输入栏,更多菜单是群菜单', () => {
  const { page, requests } = loadChat(5)
  page.loadMessages()
  requests[0].success({ code: 200, data: { list: [], blocked: true, blockedByMe: true } })
  assert.equal(page.data.disabled, false)
  assert.equal(page.data.disabledText, '')
  assert.deepEqual(page.data.moreSheetItems, ['举报这条消息', '拉黑该用户', '隐藏会话'])
})

test('私信仍按 blocked 锁输入栏', () => {
  const { page, requests } = loadChat(1)
  page.data.groupLike = false
  page.loadMessages()
  requests[0].success({ code: 200, data: { list: [], blocked: true, blockedByMe: true } })
  assert.equal(page.data.disabled, true)
})

test('公告频道:没有 SEND_MESSAGE 就说明只有主理人和管理员可以发言', () => {
  const { page, requests } = loadChat(5)
  page.loadChannelGate(100)
  assert.equal(requests[0].url, '/api/channels/100')
  requests[0].success({ code: 200, data: { status: 0, my_permissions_list: ['VIEW_CHANNEL', 'READ_HISTORY'] } })
  assert.equal(page.data.disabled, true)
  assert.equal(page.data.disabledText, '这个频道只有主理人和管理员可以发言')
  // 之后的消息轮询不会把这个只读原因清掉
  page.loadMessages()
  requests[1].success({ code: 200, data: { list: [] } })
  assert.equal(page.data.disabledText, '这个频道只有主理人和管理员可以发言')
})

test('归档频道:只能查看', () => {
  const { page, requests } = loadChat(5)
  page.loadChannelGate(100)
  requests[0].success({ code: 200, data: { status: 1, my_permissions_list: ['VIEW_CHANNEL', 'SEND_MESSAGE'] } })
  assert.equal(page.data.disabledText, '频道已归档，只能查看')
})

test('能发言的频道不锁输入栏', () => {
  const { page, requests } = loadChat(5)
  page.loadChannelGate(100)
  requests[0].success({ code: 200, data: { status: 0, my_permissions_list: ['VIEW_CHANNEL', 'SEND_MESSAGE'] } })
  assert.notEqual(page.data.disabled, true)
})

test('举报这条消息:先选具体消息,报的就是选中的那条', () => {
  const { page, requests } = loadChat(5)
  page.data.msgs = [{ id: 10, senderId: '2', senderName: '甲', content: '第一条' },
    { id: 11, senderId: '3', senderName: '乙', content: '第二条' }, { id: 12, senderId: 1, content: '我的' }]
  page.onMoreSelect({ detail: { label: '举报这条消息' } })
  assert.equal(page.data.pickSheetShow, true)
  assert.deepEqual(page.data.pickSheetItems, ['乙：第二条', '甲：第一条'])
  page.onPickSelect({ detail: { index: 1, label: '甲：第一条' } })
  assert.equal(requests[0].url, '/api/im/report')
  assert.equal(requests[0].data.message_id, 10)
})

test('拉黑该用户:选作者,确认后只折叠 TA 的消息,不锁频道', () => {
  const { page, requests, dialogs } = loadChat(5)
  page.data.msgs = [{ id: 10, senderId: '2', senderName: '甲' }, { id: 11, senderId: '2', senderName: '甲' },
    { id: 12, senderId: '3', senderName: '乙' }]
  page.onMoreSelect({ detail: { label: '拉黑该用户' } })
  assert.deepEqual(page.data.pickSheetItems, ['乙', '甲'])
  page.onPickSelect({ detail: { index: 1, label: '甲' } })
  assert.deepEqual(dialogs, ['im.block'])
  page.onDangerConfirm({ detail: { key: 'im.block' } })
  const block = requests.find(r => r.url === '/api/im/block')
  assert.equal(block.data.target_member_id, 2)
  block.success({ code: 200 })
  assert.notEqual(page.data.disabled, true)
  assert.ok(requests.some(r => r.url === '/api/im/messages'), 'reloads so the server flags the blocked sender')
})

test('已拉黑成员的消息折叠,点「显示」只展开这一条', () => {
  const { page } = loadChat(5)
  const list = page.decorate([{ id: 10, senderId: '2', senderBlocked: true, content: 'x' }, { id: 11, senderId: '3', content: 'y' }])
  assert.equal(list[0].blockedHidden, true)
  assert.equal(list[1].blockedHidden, false)
  page.data.msgs = list
  page.onRevealBlocked({ currentTarget: { dataset: { id: 10 } } })
  assert.equal(page.data.msgs[0].blockedHidden, false)
  const wxml = fs.readFileSync(path.join(__dirname, '../../subpackageB/pages/im/chat/index.wxml'), 'utf8')
  assert.match(wxml, /wx:elif="\{\{item.blockedHidden\}\}"[^>]*bindtap="onRevealBlocked"/)
})
