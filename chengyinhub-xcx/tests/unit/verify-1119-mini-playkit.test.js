const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.join(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')

function loadComponent(rel) {
  let def = null
  const prev = global.Component
  global.Component = (d) => { def = d }
  global.Behavior = global.Behavior || ((b) => b)
  try { delete require.cache[require.resolve(path.join(ROOT, rel))]; require(path.join(ROOT, rel)) } finally { global.Component = prev }
  return def
}

test('CG-ALL-13 题图读不出来:换成替代文字 + 重试,不留破图', () => {
  const def = loadComponent('pages/playkit/components/playkit-qa/index.js')
  const vm = { data: { imgFailed: false, imageUrl: 'https://x/a.png' }, setData(p) { Object.assign(this.data, p) } }
  def.methods.onQuestionImgError.call(vm)
  assert.equal(vm.data.imgFailed, true)
  def.methods.onRetryQuestionImg.call(vm)
  assert.equal(vm.data.imgFailed, false, '重试重挂 image')
  vm.data.imgFailed = true
  def.observers.imageUrl.call(vm)
  assert.equal(vm.data.imgFailed, false, '换题复位')
  const wxml = read('pages/playkit/components/playkit-qa/index.wxml')
  assert.match(wxml, /<image wx:if="\{\{!imgFailed\}\}" class="qa__img"[^>]*binderror="onQuestionImgError"/)
  assert.match(wxml, /\{\{imageAlt \|\| '图片没加载出来'\}\}/)
  assert.match(wxml, /bindtap="onRetryQuestionImg"[^>]*>重试</)
  assert.match(read('pages/playkit/components/playkit/index.wxml'), /image-alt="\{\{kit\.imageAlt\}\}"/)
})

test('CG-ALL-13 服务端给 imageAlt 才带,没给为空串(组件用通用一句)', () => {
  const now = new Date('2026-11-19T10:00:00+08:00')
  const kit = pickPlayKit({ sessionId: 's1', version: 1, playKit: { qa: { mode: 'TYPE', title: '题', imageUrl: 'https://x/a.png', imageAlt: ' 门楣上的字 ' } } }, now)
  assert.equal(kit.imageAlt, '门楣上的字')
  const bare = pickPlayKit({ sessionId: 's1', version: 1, playKit: { qa: { mode: 'TYPE', title: '题', imageUrl: 'https://x/a.png' } } }, now)
  assert.equal(bare.imageAlt, '')
})

test('CG-01-P2-5 逐字猜词题面下常驻「怎么填」入口(含 V＝ü),本站说明优先', () => {
  const { hangmanKit } = require('../../pages/play/utils/playkit-questo.js')
  assert.equal(hangmanKit({ slots: [], howto: ' 一格一个字母 ' }).howto, '一格一个字母')
  assert.equal(hangmanKit({ slots: [] }).howto, '')
  const def = loadComponent('pages/playkit/components/playkit-hangman/index.js')
  assert.match(def.data.DEFAULT_HOWTO, /ü 用 V 代替/)
  const vm = { data: { howtoOpen: false }, setData(p) { Object.assign(this.data, p) } }
  def.methods.onToggleHowto.call(vm)
  assert.equal(vm.data.howtoOpen, true)
  def.methods.onToggleHowto.call(vm)
  assert.equal(vm.data.howtoOpen, false, '可反复开合')
  const wxml = read('pages/playkit/components/playkit-hangman/index.wxml')
  assert.match(wxml, /<view class="hm__how pk-in" wx:if="\{\{!solved\}\}">/)
  assert.match(wxml, /bindtap="onToggleHowto"[^>]*>怎么填</)
  assert.match(wxml, /\{\{howto \|\| DEFAULT_HOWTO\}\}/)
  assert.match(read('pages/playkit/components/playkit/index.wxml'), /howto="\{\{kit\.howto\}\}"/)
})

test('CG-07-P2-2 结算总览单独数出辅助完成(跳过 / 看过答案)的节点,没有就不出格子', () => {
  const v = require('../../subpackageJourney/utils/journey-view.js')
  const r = v.reportView({ runState: 'COMPLETED', completionKind: 'ASSISTED_COMPLETION', completedNodes: 3, totalNodes: 3,
    nodes: [{ nodeId: 1, completionMode: 'SKIPPED' }, { nodeId: 2, completionMode: 'REVEALED' }, { nodeId: 3, completionMode: 'SOLVED' }] })
  assert.equal(r.result, '辅助完成')
  assert.equal(r.assistedCount, 2)
  assert.deepEqual(r.nodes.map((n) => n.mode), ['已跳过', '看过答案', ''])
  assert.equal(v.reportView({ runState: 'COMPLETED', completionKind: 'LEGITIMATE_COMPLETION' }).assistedCount, 0)
  const wxml = read('subpackageJourney/pages/settle/index.wxml')
  assert.match(wxml, /wx:if="\{\{report\.assistedCount > 0\}\}"><text class="st-two__v">\{\{report\.assistedCount\}\}<\/text><text class="st-two__l">辅助完成<\/text>/)
})

const JAVA_RUNTIME = '../chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/AdvancedGameRuntimeServiceImpl.java'
const JAVA_PROJECTION = '../chengyinhub-system/src/main/java/com/chengyinhub/business/util/AdvancedGamePublicProjection.java'

test('CG-ALL-12 只记录拍照(#866):读 photoCheck.mode=RECORD,提交走 SUBMIT_PHOTO_RECORD;判定站仍 SUBMIT_PHOTO_CHECK', () => {
  const view = require('../../pages/play/utils/playkit-view.js')
  const { serverAction, serverPayload, photoSubmitAction, PHOTO_RECORD_MODE } = view
  // 后端真名:公开投影只 copy mode(不是 photoMode);运行期 recordPhotoMode 认 mode === "RECORD";动作名 SUBMIT_PHOTO_RECORD
  const projection = read(JAVA_PROJECTION)
  assert.match(projection, /putIfText\(value, "mode", source\);/)
  assert.doesNotMatch(projection, /photoMode/)
  const runtime = read(JAVA_RUNTIME)
  assert.match(runtime, new RegExp('"' + PHOTO_RECORD_MODE + '"\\.equals\\(photoCheck\\.path\\("mode"\\)'))
  assert.match(runtime, /case "SUBMIT_PHOTO_RECORD":/)
  assert.match(runtime, /if \(recordPhotoMode\(config\.get\("photoCheck"\)\)\) throw new IllegalArgumentException\("这一站拍照只做记录"\);/)
  assert.match(runtime, /text\(payload, "imageUrl"\)/)
  assert.match(runtime, /payload\.get\("textConfirmed"\)/)
  const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js')
  assert.ok(cfg.PHOTO_CHECK_MODES.includes(PHOTO_RECORD_MODE), '与发布端 PHOTO_CHECK_MODES(后端镜像)同一个取值')

  const now = new Date('2026-11-19T10:00:00+08:00')
  const kit = pickPlayKit({ sessionId: 's1', version: 1, playKit: { photoCheck: { title: '留念', mode: 'RECORD' } } }, now)
  assert.equal(kit.type, 'photocheck')
  assert.equal(kit.recordOnly, true)
  assert.equal(kit.mode, 'RECORD', '不是 CARD,不进拍物成卡那一屏')
  assert.equal(photoSubmitAction(kit), 'SUBMIT_PHOTO_RECORD')
  const legacy = pickPlayKit({ sessionId: 's1', version: 1, playKit: { photoCheck: { title: '拍', photoMode: 'RECORD' } } }, now)
  assert.equal(legacy.recordOnly, false, '旧的自造键 photoMode 不认')
  assert.equal(photoSubmitAction(legacy), 'SUBMIT_PHOTO_CHECK')
  const card = pickPlayKit({ sessionId: 's1', version: 1, playKit: { photoCheck: { title: '卡', mode: 'CARD' } } }, now)
  assert.equal(photoSubmitAction(card), 'SUBMIT_PHOTO_CHECK')
  // 文字确认继续:SUBMIT_PHOTO_RECORD {textConfirmed:true};不再借 SKIP_SENSOR_STATION
  assert.equal(serverAction('photocheck', 'textconfirm'), 'SUBMIT_PHOTO_RECORD')
  assert.deepEqual(serverPayload('photocheck', 'textconfirm', {}), { textConfirmed: true })
  assert.equal(serverAction('photocheck', 'skip'), '')
  // 运行期视图(AdvancedGameRuntimeServiceImpl 只记录站才 put textConfirmed)同段下发
  assert.match(runtime, /if \(recordPhotoMode\(cfg\)\) value\.put\("textConfirmed", st\.path\("textConfirmed"\)\.asBoolean\(false\)\);/)
  const confirmed = pickPlayKit({ sessionId: 's1', version: 1, playKit: { photoCheck: { title: '留念', mode: 'RECORD', passed: true, textConfirmed: true } } }, now)
  assert.equal(confirmed.textConfirmed, true)
  assert.equal(kit.textConfirmed, false)

  const def = loadComponent('pages/playkit/components/playkit-photocheck/index.js')
  const obs = def.observers['show, tries, maxTries, passed, flagged, degraded, fallback, lastReason']
  const vm = { data: Object.assign({}, def.data, { recordOnly: true }), setData(p) { Object.assign(this.data, p) }, replayMotion() {} }
  obs.call(vm, true, 1, 3, true, false, false, 'retake', '')
  assert.equal(vm.data.verdict, '已记录')
  assert.equal(vm.data.triesLabel, '')
  const typed = { data: Object.assign({}, def.data, { recordOnly: true, textConfirmed: true }), setData(p) { Object.assign(this.data, p) }, replayMotion() {} }
  obs.call(typed, true, 1, 0, true, false, false, 'retake', '')
  assert.equal(typed.data.verdict, '没拍照，已继续')
  const judge = { data: Object.assign({}, def.data), setData(p) { Object.assign(this.data, p) }, replayMotion() {} }
  obs.call(judge, true, 1, 3, true, false, false, 'retake', '')
  assert.equal(judge.data.verdict, '这张过了', '审核模式不变')
  const wxml = read('pages/playkit/components/playkit-photocheck/index.wxml')
  assert.match(wxml, /judging \? \(recordOnly \? '保存中…' : '审核中…'\)/)
  assert.match(read('pages/playkit/components/playkit/index.wxml'), /record-only="\{\{kit\.recordOnly\}\}" text-confirmed="\{\{kit\.textConfirmed\}\}"\n  bind:shoot="onShoot" bind:resubmit="onResubmit" bind:textconfirm="onTextConfirm"/)
  assert.match(read('pages/playkit/components/playkit/index.js'), /onTextConfirm\(\) \{ this\._emit\('textconfirm'\); \}/)
  // 页面:拍照提交与重新提交都按站别取动作名;只记录站不走模型同意;失败同样回到那一屏
  const page = read('pages/play/index.js')
  assert.match(page, /name: photoSubmitAction\(kit\),/)
  assert.match(page, /\(kit\.recordOnly \? Promise\.resolve\(\) : this\._ensurePhotoConsent\(current\)\)\.then/)
  assert.match(page, /if \(action === 'SUBMIT_PHOTO_RECORD'\) this\._notifyPhotoFail\(/)
  assert.match(page, /actionName === 'SUBMIT_PHOTO_CHECK' \|\| actionName === 'SUBMIT_PHOTO_RECORD'\) this\._trackPhotoJudging/)
})

test('CG-ALL-12 / CG-ALL-29 只记录站相机被拒:亮出「不拍照，继续」,文字确认后才发 textconfirm;拍完先预览,可重拍', () => {
  const shown = []
  const modalPath = require.resolve(path.join(ROOT, 'utils/modal.js'))
  const saved = require.cache[modalPath]
  require.cache[modalPath] = { id: modalPath, filename: modalPath, loaded: true, exports: { show: (o) => shown.push(o) } }
  const def = loadComponent('pages/playkit/components/playkit-photocheck/index.js')
  const inst = { data: Object.assign({}, def.data, { recordOnly: true }), events: [] }
  inst.setData = (p) => Object.assign(inst.data, p)
  inst.triggerEvent = (n, d) => inst.events.push([n, d])
  for (const [k, fn] of Object.entries(def.methods)) inst[k] = fn.bind(inst)
  const prevWx = global.wx
  global.wx = { chooseMedia: (o) => o.fail({ errMsg: 'chooseMedia:fail auth deny' }), vibrateShort() {} }
  try { inst.onShoot() } finally { global.wx = prevWx }
  assert.equal(inst.data.skipOffer, true)
  inst.onTextConfirm()
  assert.equal(shown.length, 1)
  assert.deepEqual(inst.events, [], '没确认不发')
  shown[0].success({ confirm: true })
  assert.deepEqual(inst.events, [['textconfirm', undefined]])

  // 判定站(非只记录)相机被拒:不给文字确认这条路
  const judged = { data: Object.assign({}, def.data), events: [] }
  judged.setData = (p) => Object.assign(judged.data, p)
  judged.triggerEvent = (n, d) => judged.events.push([n, d])
  for (const [k, fn] of Object.entries(def.methods)) judged[k] = fn.bind(judged)
  judged.onTextConfirm()
  assert.deepEqual(judged.events, [])

  inst.events.length = 0
  global.wx = { chooseMedia: (o) => o.success({ tempFiles: [{ tempFilePath: 'wxfile://a.jpg', size: 1 }] }), vibrateShort() {} }
  try {
    inst.onShoot()
    assert.equal(inst.data.pendingPath, 'wxfile://a.jpg')
    assert.deepEqual(inst.events, [], '预览阶段不上传')
    inst.onRetake()   // 重拍:丢掉这张,再取一张
    assert.equal(inst.data.pendingPath, 'wxfile://a.jpg')
    inst.onShoot()
  } finally { global.wx = prevWx }
  assert.deepEqual(inst.events, [['shoot', { tempFilePath: 'wxfile://a.jpg', size: 1 }]])
  assert.match(read('pages/playkit/components/playkit-photocheck/index.wxml'), /wx:elif="\{\{recordOnly && skipOffer && !passed && !judging\}\}" bindtap="onTextConfirm"/)
  if (saved) require.cache[modalPath] = saved; else delete require.cache[modalPath]
})

test('CG-09-P2-5 / CG-10-D-3 题面下常驻「看不清？查看资料图」:内容配了才出,点开记一条', () => {
  const now = new Date('2026-11-19T10:00:00+08:00')
  const qa = pickPlayKit({ sessionId: 's1', version: 1, playKit: { qa: { mode: 'TYPE', title: '题', referenceImage: 'https://x/ref.png' } } }, now)
  assert.equal(qa.referenceUrl, 'https://x/ref.png')
  const { hangmanKit } = require('../../pages/play/utils/playkit-questo.js')
  assert.equal(hangmanKit({ slots: [], referenceImage: 'https://x/r.png' }).referenceUrl, 'https://x/r.png')
  assert.equal(hangmanKit({ slots: [] }).referenceUrl, '')
  for (const rel of ['pages/playkit/components/playkit-qa/index.js', 'pages/playkit/components/playkit-hangman/index.js']) {
    const def = loadComponent(rel)
    const previews = []
    const events = []
    const prev = global.wx
    global.wx = { previewImage: (o) => previews.push(o.current) }
    try {
      def.methods.onOpenReference.call({ data: { referenceUrl: '' }, triggerEvent: (n) => events.push(n) })
      def.methods.onOpenReference.call({ data: { referenceUrl: 'https://x/r.png' }, triggerEvent: (n) => events.push(n) })
    } finally { global.wx = prev }
    assert.deepEqual(previews, ['https://x/r.png'], rel)
    assert.deepEqual(events, ['reference'], rel)
  }
  assert.match(read('pages/playkit/components/playkit-qa/index.wxml'), /wx:if="\{\{referenceUrl\}\}" bindtap="onOpenReference"[^>]*>看不清？查看资料图</)
  assert.match(read('pages/playkit/components/playkit-hangman/index.wxml'), /wx:if="\{\{referenceUrl\}\}" bindtap="onOpenReference"[^>]*>看不清？查看资料图</)
  // #866 白名单里看资料图是 material_view(不是 hint_open)
  assert.match(read('pages/play/index.js'), /case 'reference': this\._stageEvent\('material_view', \(this\._kit\(\) \|\| \{\}\)\.nodeId\); return;/)
})

test('CG-ALL-11 / CG-ALL-12(#866):战报读整局 runStartMode 与逐站 photoUrl,不再自造逐站 DESK/ALT', () => {
  const vo = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/PlayerRunReportVO.java')
  assert.match(vo, /private String runStartMode;/)
  assert.match(vo, /private String photoUrl;/)
  const mode = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/RunStartMode.java')
  assert.match(mode, /DESK = "DESK"/)
  assert.match(mode, /ALT = "ALT"/)
  const v = require('../../subpackageJourney/utils/journey-view.js')
  const r = v.reportView({ runState: 'COMPLETED', completionKind: 'LEGITIMATE_COMPLETION', runStartMode: 'DESK',
    nodes: [{ nodeId: 1, photoUrl: 'https://x/1.jpg' }, { nodeId: 2, completionMode: 'DESK' }, { nodeId: 3, photoUrl: null }] })
  assert.equal(r.startModeText, '桌面完成')
  assert.deepEqual(r.nodes.map((n) => n.mode), ['', '', ''], '逐站 completionMode 没有 DESK/ALT')
  assert.deepEqual(r.nodes.map((n) => n.photoUrl), ['https://x/1.jpg', '', ''])
  assert.deepEqual(r.photoUrls, ['https://x/1.jpg'])
  assert.equal(v.reportView({ runState: 'COMPLETED', runStartMode: 'ALT' }).startModeText, '替代站开局')
  assert.equal(v.reportView({ runState: 'COMPLETED', runStartMode: null }).startModeText, '', '正常开局不出')
  assert.equal(v.reportView({ runState: 'COMPLETED' }).deskCount, undefined)
  const wxml = read('subpackageJourney/pages/settle/index.wxml')
  assert.match(wxml, /wx:if="\{\{report\.startModeText\}\}"><text class="st-two__v">\{\{report\.startModeText\}\}<\/text>/)
  assert.doesNotMatch(wxml, /deskCount|altCount/)
  const report = read('subpackageJourney/pages/report/index.wxml')
  assert.match(report, /wx:if="\{\{item\.photoUrl\}\}" class="jn-row__pic" bindtap="previewNodePhoto"/)
  const page = read('subpackageJourney/pages/report/index.js')
  assert.match(page, /previewNodePhoto\(e\) \{/)
})

test('PG-041 通行证订单缺单号:落缺参态只给返回,load 不再以空单号请求', () => {
  const js = read('subpackageMember/market/pass-order/index.js')
  assert.match(js, /if \(!this\._orderNo\) \{ this\.setData\(\{ state: 'missing' \}\); return \}/)
  const load = js.slice(js.indexOf('  load() {'), js.indexOf('app.sendRequest', js.indexOf('  load() {')))
  assert.match(load, /if \(!this\._orderNo\) \{ this\.setData\(\{ state: 'missing' \}\); return \}/)
  assert.match(read('subpackageMember/market/pass-order/index.wxml'), /wx:elif="\{\{state === 'missing'\}\}"[^>]*kind="missing-param" cta="返回" bind:cta="goBack"/)
})
