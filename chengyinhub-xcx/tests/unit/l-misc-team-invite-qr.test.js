'use strict'

// l-misc(2026-11-10 业主「可以的 需要的」):队伍小程序码 + 加入码。
// 后端 /api/team/invite-qrcode 只给本队在队成员;微信不可用时 qrState=UNAVAILABLE,只给加入码。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')
const door = require('../../utils/game-door-entry.js')
const inviteQr = require('../../pages/team/utils/team-invite-qr.js')

test('队伍码 scene:新码原样、旧十六进制码还原,别的前缀不认', () => {
  assert.equal(door.parseTeamScene('tm.AbCdEfGhIjKlMnOpQr_-12'), 'AbCdEfGhIjKlMnOpQr_-12')
  assert.equal(door.parseTeamScene('tm.AB2XYZ'), 'AB2XYZ')
  assert.equal(door.parseTeamScene('tm.AB0XYZ'), null)
  assert.equal(door.captureDoorScene({ scene: 1047, query: { scene: 'tm.AB2XYZ' } }, null), 'tm.AB2XYZ')
  assert.equal(door.parseTeamScene('th.AP8QoLHC0-T1BhcoOUpbbA'), '00ff10a0b1c2d3e4f5061728394a5b6c')
  assert.equal(door.parseTeamScene(encodeURIComponent('th.AP8QoLHC0-T1BhcoOUpbbA')), '00ff10a0b1c2d3e4f5061728394a5b6c')
  assert.equal(door.parseTeamScene('tp.12'), null)
  assert.equal(door.parseTeamScene('tm.short'), null)
  assert.equal(door.parseTeamScene(''), null)
  // 不会被门口码 / 主题码误吃
  assert.equal(door.parseDoorScene('tm.AbCdEfGhIjKlMnOpQr_-12'), null)
  assert.equal(door.parseTopicScene('tm.AbCdEfGhIjKlMnOpQr_-12'), null)
})

test('冷启动扫码(1047)把队伍码 scene 存下,别的进入场景不存', () => {
  assert.equal(door.captureDoorScene({ scene: 1047, query: { scene: 'tm.AbCdEfGhIjKlMnOpQr_-12' } }, null), 'tm.AbCdEfGhIjKlMnOpQr_-12')
  assert.equal(door.captureDoorScene({ scene: 1001, query: { scene: 'tm.AbCdEfGhIjKlMnOpQr_-12' } }, null), null)
})

test('落地页:队伍码转到加入页,码只放在 code 参数里', () => {
  const index = read('pages/index/index.js')
  assert.match(index, /const teamInvite = !activityId && !shopId && \(parseTeamScene\(options && options\.scene\) \|\| parseTeamScene\(app\.pendingDoorScene\)\);/)
  assert.match(index, /if \(activityId \|\| shopId \|\| teamInvite\) \{/)
  assert.match(index, /'\/pages\/team\/join\/index\?code=' \+ encodeURIComponent\(teamInvite\)/)
})

test('回包投影:READY 出码 + 加入码;UNAVAILABLE 只出加入码;拒绝只出原因', () => {
  const img = (u) => 'signed:' + u
  assert.deepEqual(inviteQr.viewOf({ code: 200, data: { inviteCode: 'C', qrState: 'READY', qrcodeUrl: 'u.png' } }, img),
    { state: 'ready', qr: 'signed:u.png', code: 'C', message: '' })
  assert.deepEqual(inviteQr.viewOf({ code: 200, data: { inviteCode: 'C', qrState: 'UNAVAILABLE' } }, img),
    { state: 'code', qr: '', code: 'C', message: '' })
  // READY 但没给地址:不画码
  assert.equal(inviteQr.viewOf({ code: 200, data: { inviteCode: 'C', qrState: 'READY' } }, img).state, 'code')
  const refused = inviteQr.viewOf({ code: 500, msg: '无权查看该队伍' }, img)
  assert.equal(refused.state, 'error')
  assert.equal(refused.code, '')
  assert.equal(refused.message, '无权查看该队伍')
  assert.equal(inviteQr.viewOf({ code: 200, data: { qrState: 'READY', qrcodeUrl: 'u' } }).state, 'error')
})

test('手输加入码:去空白,只认两种服务端形状', () => {
  assert.equal(inviteQr.normalizeJoinCode(' AbCdEfGhIjKl MnOpQr_-12 '), 'AbCdEfGhIjKlMnOpQr_-12')
  assert.equal(inviteQr.normalizeJoinCode(' ab2 xyz '), 'AB2XYZ')
  assert.equal(inviteQr.normalizeJoinCode('AB0XYZ'), '')
  assert.equal(inviteQr.normalizeJoinCode('AB1XYZ'), '')
  assert.equal(inviteQr.normalizeJoinCode('00FF10A0B1C2D3E4F5061728394A5B6C'), '00ff10a0b1c2d3e4f5061728394a5b6c')
  assert.equal(inviteQr.normalizeJoinCode('abc'), '')
  assert.equal(inviteQr.normalizeJoinCode(null), '')
})

test('队伍详情:二维码入口只给在队成员,弹层按回包画码 / 加入码,复制加入码', () => {
  const wxml = read('pages/team/detail/index.wxml')
  const js = read('pages/team/detail/index.js')
  assert.match(wxml, /<view wx:if="\{\{joined\}\}" class="team-action secondary" bindtap="openInviteQr"/)
  assert.match(wxml, /<cy-sheet show="\{\{qrSheet\.show\}\}" title="队伍二维码"/)
  assert.match(wxml, /<image wx:if="\{\{qrSheet\.state === 'ready'\}\}"/)
  assert.match(wxml, /wx:if="\{\{qrSheet\.code\}\}"[^>]*bindtap="copyJoinCode"/)
  assert.match(js, /inviteQr\.load\(app, requestId\(this\.teamId\)/)
  // 迟到回包:换人、关弹层、离页都丢弃
  assert.match(js, /epoch !== this\._qrEpoch \|\| this\._hidden \|\| this\._disposed/)
  assert.match(js, /!sameReader\(app, context\.reader\) \|\| !this\.data\.qrSheet\.show/)
  assert.match(read('pages/team/detail/index.json'), /"cy-sheet": "\/components\/cy\/sheet\/index"/)
})

test('加入页:?entry=code 先手输,码不对红字在框下,不弹窗', () => {
  const js = read('pages/team/join/index.js')
  const wxml = read('pages/team/join/index.wxml')
  assert.match(js, /this\.codeEntry = !this\.inviteCode && !this\.legacyTeamId && options\.entry === 'code';/)
  assert.match(js, /codeError: '加入码不对，检查一下再输'/)
  assert.match(wxml, /<cy-field label="加入码"[^>]*error-text="\{\{codeError\}\}"/)
  assert.match(read('subpackageRoam/my-teams/index.js'), /openJoinCode\(\) \{ wx\.navigateTo\(\{ url: '\/pages\/team\/join\/index\?entry=code' \}\); \}/)
})
