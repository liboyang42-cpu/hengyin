'use strict'
// BL-34 俱乐部详情举报入口(复用 BL-32 共用举报半屏)。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')

test('BL-34: 非主理人在资料区文字链看到「举报」，退出俱乐部仍只给已加入成员', () => {
  const wxml = read('pages/club/detail/index.wxml')
  assert.match(wxml, /<view class="profile-links" wx:if="\{\{club && !club\.isOwner\}\}">/)
  assert.match(wxml, /<text class="profile-link" wx:if="\{\{club\.isJoined\}\}" bindtap="onQuit"/)
  assert.match(wxml, /bindtap="onReportClub" aria-role="button" aria-label="举报俱乐部"[^>]*>举报<\/text>/)
  assert.match(wxml, /<cy-report-sheet wx:if="\{\{club && !club\.isOwner\}\}" show="\{\{reportShow\}\}" target-type="CLUB" target-id="\{\{reportClubId\}\}"/)
  assert.match(read('pages/club/detail/index.json'), /"cy-report-sheet": "\/components\/cy\/report-sheet\/index"/)
})

test('BL-34: 打开举报用俱乐部真实 id，缺 id 不打开', () => {
  const js = read('pages/club/detail/index.js')
  assert.match(js, /onReportClub\(\) \{\n    const club = this\.data\.club \|\| \{\};\n    const clubId = club\.id \|\| this\.data\.clubId;\n    if \(clubId == null \|\| clubId === ''\) return;/)
  assert.match(js, /reportShow: false,\n    reportClubId: '',/)
})
