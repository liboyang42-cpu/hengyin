'use strict'
// 第九轮 m2 第 2 项:同场队伍路线(客户端)。轮询节奏、表态门、只上报新点、结束即删、作者开关。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const kit = require('../../pages/play/utils/team-trail.js')
const { TEAM_ROUTE_COLORS } = require('../../utils/play-visual-tokens.js')

const ok = (data) => ({ code: '200', data })

test('轮询约 12 秒一次,失败退避到 60 秒封顶', () => {
  assert.ok(kit.INTERVAL_MS >= 10000 && kit.INTERVAL_MS <= 15000)
  assert.strictEqual(kit.MAX_INTERVAL_MS, 60000)
})

test('每队一色、光晕在下亮线在上,头像图钉带进度', () => {
  const colors = {}
  const teams = kit.shapeTeams([
    { key: 'a1', name: '队A', avatar: 'https://a', done: 2, pts: [[31.2, 121.4], [31.21, 121.41]] },
    { key: 'b2', name: '队B', avatar: '', done: 0, pts: [[31.3, 121.5]] },
    { key: 'bad', pts: [] },
  ], colors)
  assert.strictEqual(teams.length, 2)
  assert.notStrictEqual(teams[0].color, teams[1].color)
  assert.ok(TEAM_ROUTE_COLORS.includes(teams[0].color))
  assert.strictEqual(teams[0].tag, '第 2 站')
  assert.strictEqual(teams[1].tag, '')
  const lines = kit.teamLines(teams)
  assert.strictEqual(lines.length, 2, 'a one-point team has a pin but no line')
  assert.ok(lines[0].width > lines[1].width)
  const pins = kit.teamPins(teams)
  assert.strictEqual(pins[1].lat, 31.3)
  assert.ok(pins.every((p) => p.id >= kit.PIN_BASE))
  // 同一队下一轮颜色不变
  assert.strictEqual(kit.shapeTeams([{ key: 'b2', pts: [[1, 2]] }], colors)[0].color, teams[1].color)
})

function harness(stateData) {
  const calls = []
  let pts = []
  const tt = kit.createTeamTrail({
    activityId: '10',
    api: {
      state(data) { calls.push({ url: '/api/team/trail/state', data }); return Promise.resolve(ok(stateData)) },
      consent(data) { calls.push({ url: '/api/team/trail/consent', data }); return Promise.resolve(ok({ available: true, share: data.share, decided: true })) },
      sync(data) { calls.push({ url: '/api/team/trail/sync', data }); return Promise.resolve(ok({ available: true, share: true, teams: [{ key: 'k', pts: [[31, 121]] }] })) },
    },
    readPoints: () => pts,
    done: () => 1,
    onTeams() {},
  })
  return { tt, calls, setPts(p) { pts = p } }
}

test('只上报自上次成功以来的新点', async () => {
  const h = harness({ available: true, share: true, decided: true })
  await h.tt.load()
  h.setPts([{ lat: 31, lng: 121 }, { lat: 31.001, lng: 121 }])
  await h.tt.tick()
  h.setPts([{ lat: 31, lng: 121 }, { lat: 31.001, lng: 121 }, { lat: 31.002, lng: 121 }])
  await h.tt.tick()
  const syncs = h.calls.filter((c) => c.url === '/api/team/trail/sync')
  assert.strictEqual(syncs[0].data.pts.length, 2)
  assert.deepStrictEqual(syncs[1].data.pts, [[31.002, 121]])
})

test('不可用(别的场、未付费、作者关掉)或没开分享时不轮询', async () => {
  const off = harness({ available: false })
  await off.tt.load(); off.tt.start()
  assert.strictEqual(off.tt.isRunning(), false)
  const noShare = harness({ available: true, share: false, decided: true })
  await noShare.tt.load(); noShare.tt.start()
  assert.strictEqual(noShare.tt.isRunning(), false)
})

test('结束本局:通知服务端删点,之后不再轮询', async () => {
  const h = harness({ available: true, share: true, decided: true })
  await h.tt.load(); h.tt.start()
  assert.strictEqual(h.tt.isRunning(), true)
  await h.tt.finish()
  assert.strictEqual(h.tt.isRunning(), false)
  const last = h.calls[h.calls.length - 1]
  assert.strictEqual(last.data.finished, true)
  h.tt.start()
  assert.strictEqual(h.tt.isRunning(), false)
})

test('游玩页:开始前先表态,藏后台暂停,通关收尾;作者开关在编辑器', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8')
  assert.match(src, /if \(this\._teamConsentGate\(\(\) => this\.onRunToggle\(\)\)\) return;/)
  assert.match(src, /Number\(this\.data\.activityId\) > 0 && !this\.data\.isPreview && !this\.data\.isMock/)
  assert.match(src, /onHide\(\) \{[\s\S]{0,200}this\._teamTrail\.pause\(\)/)
  assert.match(src, /openFinish\(\) \{\s*this\._endTeamTrail\(\);/)
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8')
  assert.match(wxml, /<cy-sheet show="\{\{teamConsent\.show\}\}"/)
  assert.match(wxml, /cy-btn variant="primary" style="width:100%" bindtap="onTeamConsentStart"/)
  assert.strictEqual((wxml.match(/team-pins="\{\{fmTeamPins\}\}"/g) || []).length, 2)
  const editor = fs.readFileSync(path.join(__dirname, '../../pages/publish/fabu/theme-runtime-survival.js'), 'utf8')
  assert.match(editor, /if \(value\) delete c\.teamTrail; else c\.teamTrail = false;/)
  const fm = fs.readFileSync(path.join(__dirname, '../../components/cy/free-map/index.js'), 'utf8')
  assert.match(fm, /translateMarker\(/)
})
