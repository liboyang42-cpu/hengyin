// 前端路线评审 2026-11-14 波次:pages/play 客户端修复的纯逻辑回归(提示阶梯挂站、逐字猜词题面、选项字母、
// 用时格式、续玩按局、通关页不重放、战报用时、票夹落点、存相册、开包跳过、时区无关的日期)。
process.env.TZ = 'America/Los_Angeles'   // 手机时区不是 UTC+8:日期口径必须不受影响
const assert = require('node:assert/strict')
const { test } = require('node:test')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const req = (p) => require(path.join(ROOT, p))

const store = {}
global.wx = {
  getStorageSync: (k) => store[k],
  setStorageSync: (k, v) => { store[k] = JSON.parse(JSON.stringify(v)) },
  removeStorageSync: (k) => { delete store[k] },
}
global.getApp = () => ({ getUserID: () => 7 })

test('a3 提示阶梯挂在故事里正开着的玩法站,否则是任务页的 game', () => {
  const { helpNodeId } = req('pages/play/utils/play-help-panel.js')
  assert.equal(helpNodeId({ game: { nodeId: 5 } }), '5')
  assert.equal(helpNodeId({ game: { nodeId: 5 }, advancedPlay: { show: true, node: { nodeId: 9 } }, playKit: { kit: { type: 'qa' } } }), '9')
  assert.equal(helpNodeId({ game: { nodeId: 5 }, advancedPlay: { show: true, node: { nodeId: 9 } }, playKit: { kit: null } }), '5')
  assert.equal(helpNodeId({ advancedPlay: { show: false, node: { nodeId: 9 } }, playKit: { kit: { type: 'qa' } } }), '')
})

test('b3 逐字猜词:题面取本站题干(缺时站名),解开后接本站文字反馈', () => {
  const { hangmanStationText } = req('pages/play/utils/playkit-questo.js')
  assert.deepEqual(hangmanStationText({ title: '', solved: false }, { question: ' 她是谁? ', passText: '答对了' }, '站名'), { title: '她是谁?', feedback: '' })
  assert.deepEqual(hangmanStationText({ title: '', solved: true }, { question: '', passText: '恭喜' }, '站名'), { title: '站名', feedback: '恭喜' })
  assert.equal(hangmanStationText({ title: '站点名', solved: true }, { question: ' 请读题卡上的记号。 ' }, '站名').title, '请读题卡上的记号。')
  assert.equal(hangmanStationText({ title: '服务端题面', solved: true }, { question: '' }, '站名').title, '服务端题面')
})

test('c1 选项文案自带字母时去重,只去与本行字母相同的前缀', () => {
  const { stripOptionKey } = req('pages/play/utils/playkit-questo.js')
  assert.equal(stripOptionKey('A 许禾自述', 'A'), '许禾自述')
  assert.equal(stripOptionKey('A. 许禾自述', 'A'), '许禾自述')
  assert.equal(stripOptionKey('（B）许禾自述', 'B'), '许禾自述')
  assert.equal(stripOptionKey('B、苹果', 'B'), '苹果')
  assert.equal(stripOptionKey('B 许禾自述', 'A'), 'B 许禾自述')
  assert.equal(stripOptionKey('Apple', 'A'), 'Apple', '字母后面不是分隔符不动')
  assert.equal(stripOptionKey('A', 'A'), 'A', '去完没有字就原样')
})

test('b7 用时满 1 小时写 h:mm:ss', () => {
  const { formatElapsed, formatRunElapsed } = req('utils/play-ui-contract.js')
  assert.equal(formatElapsed(118 * 60 + 41), '118:41', '玩法短计时口径不变')
  const fmt = formatRunElapsed
  assert.equal(fmt(59), '00:59')
  assert.equal(fmt(3599), '59:59')
  assert.equal(fmt(118 * 60 + 41), '1:58:41')
  assert.equal(fmt(10 * 3600 + 5), '10:00:05')
})

test('b6/b7 章节完成屏:数据没滚完被点掉时落到真值;h:mm:ss 也会滚', () => {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  delete require.cache[require.resolve(path.join(ROOT, 'pages/play/components/chapter-flip/index.js'))]
  require(path.join(ROOT, 'pages/play/components/chapter-flip/index.js'))
  global.Component = prev
  const inst = {
    data: { mode: 'gate', reducedMotion: false, endStats: [{ k: '用时', v: '1:58:41', shown: '0:00:00' }, { k: '坐标', v: '2/2', shown: '0/2' }] },
    _stage: 'end', _statTimer: 0, _gone: false,
    setData(patch) { Object.assign(this.data, patch) },
    _at() {}, _spawnFineDust() {}, _scatter() {},
  }
  inst._dissolve = def.methods._dissolve.bind(inst)
  inst._dissolve()
  assert.deepEqual(inst.data.endStats.map((s) => s.shown), ['1:58:41', '2/2'])
})

test('E-C2 续玩状态盖局号:换了新一局读不到上一局的开场 / 章节门 / finished / 草稿', () => {
  const resume = req('pages/play/utils/story-resume.js')
  const run1 = { memberId: 7, topicId: 9913101, routeSessionId: '101' }
  const run2 = { memberId: 7, topicId: 9913101, routeSessionId: '102' }
  resume.patch(run1, { intro: true, finished: true, gates: ['1'] })
  assert.deepEqual(resume.read(run1).gates, ['1'])
  assert.deepEqual(resume.read(run2), {}, '新一局当没有')
  resume.patch(run2, { intro: true })
  assert.equal(resume.read(run2).finished, undefined)
  assert.notEqual(resume.draftKey(run1, 3), resume.draftKey(run2, 3))
  // 2026-11-14 route-walk：局号未知时不借用旧主题快照。
  assert.deepEqual(resume.read({ memberId: 7, topicId: 9913101 }), {})
  resume.patch({ memberId: 7, topicId: 9913101 }, { intro: true })
  assert.deepEqual(resume.read({ memberId: 7, topicId: 9913101 }), {})
  assert.equal(resume.read(run1).intro, true, '其他局的记录不被新一局覆盖')
  resume.clear(run2)
  assert.deepEqual(resume.read(run2), {})
})

test('E-C2 暂停快照盖局号:别的局的快照不恢复', () => {
  const rs = req('utils/play-run-session.js')
  const run1 = { memberId: 7, topicId: 5, routeSessionId: '11' }
  const run2 = { memberId: 7, topicId: 5, routeSessionId: '12' }
  assert.equal(rs.writePausedRun(global.wx, run1, 120, 1000), true)
  assert.equal(rs.readPausedRun(global.wx, run1).elapsedSeconds, 120)
  assert.equal(rs.readPausedRun(global.wx, run2), null)
  assert.equal(rs.readPausedRun(global.wx, { memberId: 7, topicId: 5 }).elapsedSeconds, 120, '无局号沿用旧行为')
})

test('E-C1 同一局通关页只自动开一次,新一局再开', () => {
  const f = req('pages/play/utils/finish-shown.js')
  const a = f.finishShownKey(7, 9, 0, '11')
  assert.equal(f.shouldAutoOpenFinish('', a), true)
  assert.equal(f.shouldAutoOpenFinish(a, a), false)
  assert.equal(f.shouldAutoOpenFinish(a, f.finishShownKey(7, 9, 0, '12')), true)
})

test('E-C3 用时与完成日取服务端战报,不随手机时区', () => {
  const fr = req('pages/play/utils/finish-result.js')
  const t = fr.reportTiming({ startedAt: '2026-11-14 10:00:00', endedAt: '2026-11-14 10:52:10' })
  assert.equal(t.elapsedSeconds, 52 * 60 + 10)
  assert.equal(fr.finishDateLabelAt(t.completedAt), '11月14日 周六')
  assert.equal(fr.reportTiming({ startedAt: '2026-11-14 10:00:00', completedAt: '2026-11-14 09:00:00' }).elapsedSeconds, 0, '倒挂不信')
  assert.equal(fr.reportTiming({}).elapsedSeconds, 0)
  assert.equal(fr.reportTiming(null), null)
  assert.equal(fr.finishDateLabelAt('2026-11-14 00:30:00'), '11月14日 周六', '中国凌晨在美西是前一天,仍写中国日期')
})

test('b5/D-25 去票夹带 topicId,落在这条路线的票上', () => {
  const { ticketWalletUrl } = req('utils/ticket-wallet-url.js')
  assert.equal(ticketWalletUrl(9913101), '/subpackageMember/signup/index?topicId=9913101')
  assert.equal(ticketWalletUrl(''), '/subpackageMember/signup/index')
  assert.equal(ticketWalletUrl('1&x=2'), '/subpackageMember/signup/index')
})

test('E-E2 存相册被拒:分清取消 / 无权限 / 其它,设置里开了才重存', () => {
  const a = req('pages/play/utils/album-save.js')
  assert.equal(a.saveFailKind({ errMsg: 'saveImageToPhotosAlbum:fail cancel' }), 'cancel')
  assert.equal(a.saveFailKind({ errMsg: 'saveImageToPhotosAlbum:fail auth deny' }), 'denied')
  assert.equal(a.saveFailKind({ errMsg: 'saveImageToPhotosAlbum:fail system error' }), 'fail')
  assert.equal(a.albumGranted({ authSetting: { 'scope.writePhotosAlbum': true } }), true)
  assert.equal(a.albumGranted({ authSetting: { 'scope.writePhotosAlbum': false } }), false)
  assert.equal(a.albumGranted(null), false)
})

test('D-26 开包播放中再点落定(拖拽中不接管,刚点开的连点不算)', () => {
  const p = req('pages/play/utils/pack-skip.js')
  assert.equal(p.isPackPlaying({ packShake: true }), true)
  assert.equal(p.isPackPlaying({ packSink: true, packOpen: true }), false)
  assert.equal(p.isPackPlaying({ tearPct: 30 }), false)
  assert.equal(p.canSkipPack({ packShake: true }, 1000, 1100), false)
  assert.equal(p.canSkipPack({ packShake: true }, 1000, 1400), true)
  const patch = p.packSettledPatch()
  assert.equal(patch.packOpen, true)
  assert.equal(patch.packBurst, false)
})

test('E-D1 票到期按中国自然日,手机在美西也一样', () => {
  const { passDaysLeft, passExpiryText } = req('utils/ticket-expiry.js')
  const now = Date.parse('2026-11-14T10:00:00+08:00')
  assert.equal(passDaysLeft('2026-11-14 23:59:59', now), 0)
  assert.equal(passExpiryText('2026-11-14 23:59:59', now), '今天过期')
  assert.equal(passDaysLeft('2026-11-17 00:00:00', now), 2, '零点到期 = 最后能玩的是前一天')
  assert.equal(passDaysLeft('2026-11-13 12:00:00', now), null)
  assert.equal(passDaysLeft('', now), null)
})

test('E-D5 可玩期限优先服务端 expiresAt,否则 90 天与主题结束日取早', () => {
  const { passWindow } = req('pages/topic/utils/topic-motion.js')
  const now = '2026-11-14 10:00:00'
  assert.equal(passWindow({ now, expiresAt: '2026-12-01 23:59:59', topicEnd: '2027-06-01' }).line, '可玩至 12 月 1 日 · 还剩 17 天')
  assert.equal(passWindow({ now, topicEnd: '2026-11-20' }).line, '可玩至 11 月 20 日（主题结束） · 还剩 6 天')
  assert.equal(passWindow({ now, topicEnd: '2027-06-01' }).untilText, '02/12')
})

test('E-D3 分支路线完成顺序:裸时间串在 iOS 也能解析且按中国时间', () => {
  const { applyRouteStateToNodes } = req('pages/play/utils/play-route-state.js')
  const nodes = applyRouteStateToNodes([{ nodeId: 1 }], {
    routeMode: 'BRANCH_GRAPH', nodeStates: { 1: 'COMPLETED' },
    decisionLog: [{ completedNodeId: 1, at: '2026-11-14 10:00:00' }],
  })
  assert.equal(nodes.length ? nodes[0].doneAt : 0, Date.parse('2026-11-14T10:00:00+08:00'))
})

test('E-D2 本局成绩用时:裸时间串按中国时间相减', () => {
  const { scoreScreen, deadline } = req('pages/play/utils/play-hud-overlays.js')
  const screen = scoreScreen({ sessionId: 1, totalCenti: 100, status: 1, startedAt: '2026-11-14 10:00:00', endedAt: '2026-11-14 10:30:05' }, null, null)
  assert.equal(screen.elapsed, '30:05')
  assert.equal(deadline('2026-11-14 09:05:00'), '11月14日 09:05')
})

test('E-D5 开放时刻倒计时按中国时钟', () => {
  const { secondsUntilOpen } = req('pages/play/utils/playkit-view.js')
  const now = new Date('2026-11-14T10:00:00+08:00')   // 美西设备上本地钟是前一天 18:00
  assert.equal(secondsUntilOpen('11:00', now), 3600)
})
