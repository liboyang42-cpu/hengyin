'use strict'
// BL-36 私信「更多」加「举报对方」(社区信任举报 MEMBER,复用 BL-32 共用举报半屏)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')

test('BL-36: 可回复私信的更多菜单有「举报对方」，系统通知只读不出现', () => {
  const js = read('subpackageB/pages/im/chat/index.js')
  // master 把「清空聊天」改为「隐藏会话」;系统通知只读时只留「隐藏会话」
  assert.match(js, /moreSheetItems: readOnly \? \['隐藏会话'\] : \['举报这条消息', '举报对方', '拉黑', '隐藏会话'\],/)
  assert.match(js, /else if \(label === '举报对方'\) this\.doReportPeer\(\);/)
  const wxml = read('subpackageB/pages/im/chat/index.wxml')
  assert.match(wxml, /<cy-report-sheet wx:if="\{\{!readOnly\}\}" show="\{\{reportShow\}\}" target-type="MEMBER" target-id="\{\{reportPeerId\}\}"/)
  assert.match(read('subpackageB/pages/im/chat/index.json'), /"cy-report-sheet": "\/subpackageMember\/components\/cy\/report-sheet\/index"/)
  // 举报半屏放在会员分包(减主包),私信分包跨包使用必须带异步占位
  assert.match(read('subpackageB/pages/im/chat/index.json'), /"componentPlaceholder": \{[^}]*"cy-report-sheet": "view"/)
})

test('BL-36: 对方 id 取最近一条对方消息，取不到就如实提示不打开', () => {
  const js = read('subpackageB/pages/im/chat/index.js')
  const body = js.slice(js.indexOf('  doReportPeer() {'), js.indexOf('  onReportClose()'))
  assert.match(body, /if \(this\.data\.readOnly\) return;/)
  assert.match(body, /senderId != this\.data\.myId/)
  assert.match(body, /if \(!otherId\) \{ app\.tips && app\.tips\('对方还没发过消息，暂不能举报'\); return; \}/)
  assert.match(body, /this\.setData\(\{ reportShow: true, reportPeerId: String\(otherId\) \}\);/)
})
