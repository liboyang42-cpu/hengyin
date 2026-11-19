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
  const def = loadComponent('pages/play/components/playkit-qa/index.js')
  const vm = { data: { imgFailed: false, imageUrl: 'https://x/a.png' }, setData(p) { Object.assign(this.data, p) } }
  def.methods.onQuestionImgError.call(vm)
  assert.equal(vm.data.imgFailed, true)
  def.methods.onRetryQuestionImg.call(vm)
  assert.equal(vm.data.imgFailed, false, '重试重挂 image')
  vm.data.imgFailed = true
  def.observers.imageUrl.call(vm)
  assert.equal(vm.data.imgFailed, false, '换题复位')
  const wxml = read('pages/play/components/playkit-qa/index.wxml')
  assert.match(wxml, /<image wx:if="\{\{!imgFailed\}\}" class="qa__img"[^>]*binderror="onQuestionImgError"/)
  assert.match(wxml, /\{\{imageAlt \|\| '图片没加载出来'\}\}/)
  assert.match(wxml, /bindtap="onRetryQuestionImg"[^>]*>重试</)
  assert.match(read('pages/play/components/playkit/index.wxml'), /image-alt="\{\{kit\.imageAlt\}\}"/)
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
  const def = loadComponent('pages/play/components/playkit-hangman/index.js')
  assert.match(def.data.DEFAULT_HOWTO, /ü 用 V 代替/)
  const vm = { data: { howtoOpen: false }, setData(p) { Object.assign(this.data, p) } }
  def.methods.onToggleHowto.call(vm)
  assert.equal(vm.data.howtoOpen, true)
  def.methods.onToggleHowto.call(vm)
  assert.equal(vm.data.howtoOpen, false, '可反复开合')
  const wxml = read('pages/play/components/playkit-hangman/index.wxml')
  assert.match(wxml, /<view class="hm__how pk-in" wx:if="\{\{!solved\}\}">/)
  assert.match(wxml, /bindtap="onToggleHowto"[^>]*>怎么填</)
  assert.match(wxml, /\{\{howto \|\| DEFAULT_HOWTO\}\}/)
  assert.match(read('pages/play/components/playkit/index.wxml'), /howto="\{\{kit\.howto\}\}"/)
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

test('CG-ALL-12 只记录拍照:photoCheck.mode=RECORD 才生效,提交走 SUBMIT_PHOTO_RECORD,文字确认 = {textConfirmed:true};缺省同今天', () => {
  const { serverAction, serverPayload } = require('../../pages/play/utils/playkit-view.js')
  const now = new Date('2026-11-19T10:00:00+08:00')
  const kit = pickPlayKit({ sessionId: 's1', version: 1, playKit: { photoCheck: { title: '留念', mode: 'RECORD', textConfirmed: true } } }, now)
  assert.equal(kit.type, 'photocheck')
  assert.equal(kit.recordOnly, true)
  assert.equal(kit.allowSkip, true)
  assert.equal(kit.textConfirmed, true)
  const bare = pickPlayKit({ sessionId: 's1', version: 1, playKit: { photoCheck: { title: '拍', mode: 'CARD' } } }, now)
  assert.equal(bare.recordOnly, false)
  assert.equal(bare.allowSkip, false)
  assert.equal(serverAction('photocheck', 'skip'), 'SUBMIT_PHOTO_RECORD')
  assert.deepEqual(serverPayload('photocheck', 'skip', {}), { textConfirmed: true })
  assert.match(read('pages/play/index.js'), /name: kit\.recordOnly \? 'SUBMIT_PHOTO_RECORD' : 'SUBMIT_PHOTO_CHECK'/)

  const def = loadComponent('pages/play/components/playkit-photocheck/index.js')
  const obs = def.observers['show, tries, maxTries, passed, flagged, degraded, fallback, lastReason']
  const vm = { data: Object.assign({}, def.data, { recordOnly: true }), setData(p) { Object.assign(this.data, p) }, replayMotion() {} }
  obs.call(vm, true, 1, 3, true, false, false, 'retake', '')
  assert.equal(vm.data.verdict, '已记录')
  assert.equal(vm.data.triesLabel, '')
  const judge = { data: Object.assign({}, def.data), setData(p) { Object.assign(this.data, p) }, replayMotion() {} }
  obs.call(judge, true, 1, 3, true, false, false, 'retake', '')
  assert.equal(judge.data.verdict, '这张过了', '审核模式不变')
  const wxml = read('pages/play/components/playkit-photocheck/index.wxml')
  assert.match(wxml, /judging \? \(recordOnly \? '保存中…' : '审核中…'\)/)
  assert.match(read('pages/play/components/playkit/index.wxml'), /record-only="\{\{kit\.recordOnly\}\}" allow-skip="\{\{kit\.allowSkip\}\}"\n  bind:shoot="onShoot" bind:resubmit="onResubmit" bind:skip="onSkip"/)
})

test('CG-ALL-12 / CG-ALL-29 相机被拒且允许跳过:亮出「不拍照，继续」,文字确认后才发跳过;拍完先预览,可重拍', () => {
  const shown = []
  const modalPath = require.resolve(path.join(ROOT, 'utils/modal.js'))
  const saved = require.cache[modalPath]
  require.cache[modalPath] = { id: modalPath, filename: modalPath, loaded: true, exports: { show: (o) => shown.push(o) } }
  const def = loadComponent('pages/play/components/playkit-photocheck/index.js')
  const inst = { data: Object.assign({}, def.data, { allowSkip: true, recordOnly: true }), events: [] }
  inst.setData = (p) => Object.assign(inst.data, p)
  inst.triggerEvent = (n, d) => inst.events.push([n, d])
  for (const [k, fn] of Object.entries(def.methods)) inst[k] = fn.bind(inst)
  const prevWx = global.wx
  global.wx = { chooseMedia: (o) => o.fail({ errMsg: 'chooseMedia:fail auth deny' }), vibrateShort() {} }
  try { inst.onShoot() } finally { global.wx = prevWx }
  assert.equal(inst.data.skipOffer, true)
  inst.onSkipPhoto()
  assert.equal(shown.length, 1)
  assert.deepEqual(inst.events, [], '没确认不发')
  shown[0].success({ confirm: true })
  assert.deepEqual(inst.events, [['skip', undefined]])

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
  if (saved) require.cache[modalPath] = saved; else delete require.cache[modalPath]
})

test('CG-09-P2-5 / CG-10-D-3 题面下常驻「看不清？查看资料图」:内容配了才出,点开记一条', () => {
  const now = new Date('2026-11-19T10:00:00+08:00')
  const qa = pickPlayKit({ sessionId: 's1', version: 1, playKit: { qa: { mode: 'TYPE', title: '题', referenceImage: 'https://x/ref.png' } } }, now)
  assert.equal(qa.referenceUrl, 'https://x/ref.png')
  const { hangmanKit } = require('../../pages/play/utils/playkit-questo.js')
  assert.equal(hangmanKit({ slots: [], referenceImage: 'https://x/r.png' }).referenceUrl, 'https://x/r.png')
  assert.equal(hangmanKit({ slots: [] }).referenceUrl, '')
  for (const rel of ['pages/play/components/playkit-qa/index.js', 'pages/play/components/playkit-hangman/index.js']) {
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
  assert.match(read('pages/play/components/playkit-qa/index.wxml'), /wx:if="\{\{referenceUrl\}\}" bindtap="onOpenReference"[^>]*>看不清？查看资料图</)
  assert.match(read('pages/play/components/playkit-hangman/index.wxml'), /wx:if="\{\{referenceUrl\}\}" bindtap="onOpenReference"[^>]*>看不清？查看资料图</)
  assert.match(read('pages/play/index.js'), /case 'reference': this\._stageEvent\('material_view', \(this\._kit\(\) \|\| \{\}\)\.nodeId\); return;/)
})

test('CG-ALL-11 本局开局方式(runStartMode DESK / ALT)标在结算结果后面;没下发时同今天', () => {
  const v = require('../../subpackageJourney/utils/journey-view.js')
  assert.equal(v.reportView({ runState: 'COMPLETED', completionKind: 'LEGITIMATE_COMPLETION', runStartMode: 'DESK' }).startModeText, '桌面完成')
  assert.equal(v.reportView({ runState: 'COMPLETED', completionKind: 'LEGITIMATE_COMPLETION', runStartMode: 'ALT' }).startModeText, '替代站开局')
  assert.equal(v.reportView({ runState: 'COMPLETED', completionKind: 'LEGITIMATE_COMPLETION' }).startModeText, '')
  assert.match(read('subpackageJourney/pages/settle/index.wxml'), /\{\{report\.result\}\}\{\{report\.startModeText \? ' · ' \+ report\.startModeText : ''\}\}/)
})

test('PG-041 通行证订单缺单号:落缺参态只给返回,load 不再以空单号请求', () => {
  const js = read('subpackageMember/market/pass-order/index.js')
  assert.match(js, /if \(!this\._orderNo\) \{ this\.setData\(\{ state: 'missing' \}\); return \}/)
  const load = js.slice(js.indexOf('  load() {'), js.indexOf('app.sendRequest', js.indexOf('  load() {')))
  assert.match(load, /if \(!this\._orderNo\) \{ this\.setData\(\{ state: 'missing' \}\); return \}/)
  assert.match(read('subpackageMember/market/pass-order/index.wxml'), /wx:elif="\{\{state === 'missing'\}\}"[^>]*kind="missing-param" cta="返回" bind:cta="goBack"/)
})
