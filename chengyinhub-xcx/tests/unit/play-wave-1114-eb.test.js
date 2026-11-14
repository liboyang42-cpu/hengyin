'use strict'
// E-B4/B5/B6/B11/B12:拍照提交失败保留地址、审核中忙态、相机相册被拒引导、通行证点按跳过、票夹双击锁。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

function loadComponent(file, mocks) {
  let def
  const Module = require('module')
  const orig = Module._load
  Module._load = function (req, parent, ...r) {
    for (const k of Object.keys(mocks)) if (req.endsWith(k)) return mocks[k]
    return orig.call(this, req, parent, ...r)
  }
  global.Component = (d) => { def = d }
  global.wx = mocks.__wx || {}
  try { delete require.cache[require.resolve(file)]; require(file) } finally { Module._load = orig }
  return def
}
function instance(def, props) {
  const inst = { data: Object.assign({}, def.data, props), _ev: [] }
  inst.setData = (d) => Object.assign(inst.data, d)
  inst.triggerEvent = (n, d) => inst._ev.push([n, d])
  Object.assign(inst, def.methods)
  return inst
}

test('E-B6 media-denied:只有系统拒绝才弹去设置,取消不弹', () => {
  const shown = []
  const modal = { show: (o) => shown.push(o) }
  const Module = require('module'); const orig = Module._load
  Module._load = function (req, ...r) { return req.endsWith('utils/modal.js') ? modal : orig.call(this, req, ...r) }
  let md
  try { delete require.cache[require.resolve('../../pages/play/utils/media-denied.js')]; md = require('../../pages/play/utils/media-denied.js') } finally { Module._load = orig }
  assert.strictEqual(md.guideIfDenied({ errMsg: 'chooseMedia:fail cancel' }), false)
  assert.strictEqual(shown.length, 0)
  assert.strictEqual(md.guideIfDenied({ errMsg: 'chooseMedia:fail auth deny' }), true)
  assert.strictEqual(shown[0].confirmText, '去设置')
  let opened = 0; global.wx = { openSetting: () => { opened++ } }
  shown[0].success({ confirm: true }); assert.strictEqual(opened, 1)
  assert.match(read('pages/play/components/playkit-qa/index.js'), /fail: \(err\) => \{[^}]*mediaDenied\.guideIfDenied\(err\)/)
  assert.match(read('pages/play/components/playkit-photocheck/index.js'), /mediaDenied\.guideIfDenied\(err\)/)
})

test('E-B4/B5 photocheck:提交失败改「重新提交」不重拍;判分中忙态', () => {
  const noop = () => ({})
  const def = loadComponent('../../pages/play/components/playkit-photocheck/index.js', {
    'reduced-motion.js': {}, 'replay-motion.js': {}, 'utils/motion.js': { haptic() {} }, 'utils/toast.js': () => {},
    'keep-numerals.js': { tidyText: (x) => x }, 'sensor-contract.js': { PHOTO_VERIFIED_NOTE: '', PHOTO_FALLBACK_VERDICT: '', rewardNote: () => '' },
    'media-denied.js': { guideIfDenied: () => false },
  })
  const obs = def.observers['photoFailSeq, photoFailKind']
  const c = instance(def, { photoFailSeq: 1, photoFailKind: 'submit', lastUrl: 'u' })
  obs.call(c, 1, 'submit')
  assert.strictEqual(c.data.resubmit, true)
  assert.match(c.data.uploadFail, /提交失败/)
  c.onShoot()
  assert.deepStrictEqual(c._ev, [['resubmit', undefined]])
  const d = instance(def, { photoFailSeq: 2 })
  obs.call(d, 2, 'upload')
  assert.strictEqual(d.data.resubmit, false)
  assert.match(d.data.uploadFail, /上传失败/)
  const j = instance(def, { judging: true })
  j.onShoot(); assert.deepStrictEqual(j._ev, [])
  def.observers.judging.call(c, true); assert.strictEqual(c.data.resubmit, false)
  assert.match(read('pages/play/components/playkit-photocheck/index.wxml'), /judging \? '审核中…'/)
})

test('E-B4/B5 页面:提交失败走 submit 并保留地址,判分期间 photoJudging', () => {
  const js = read('pages/play/index.js')
  assert.match(js, /_notifyPhotoFail\(this\._kitPhotoUrl \? 'submit' : 'upload'\)/)
  assert.match(js, /onUploaded: \(url\) => \{ this\._kitPhotoUrl = url; \}/)
  assert.match(js, /case 'resubmit':\s*if \(evt\.type === 'photocheck'\) this\._resubmitKitPhoto\(\)/)
  assert.match(js, /'playKit\.kit\.photoJudging': on/)
  assert.match(js, /game\.action\('SUBMIT_PHOTO_CHECK', \{ imageUrl: url \}\)/)
  assert.match(read('pages/play/components/playkit/index.wxml'), /photo-fail-kind="\{\{kit\.photoFailKind\}\}" judging="\{\{kit\.photoJudging\}\}"/)
})

test('E-B11 pass-reveal:点按直接落终态,已落定/已飞走不动', () => {
  const def = loadComponent('../../pages/topic/components/cy/pass-reveal/index.js', {})
  const c = instance(def, { durationLine: '30 天', reducedMotion: false, btns: false, fly: false })
  c._stop = () => { c.stopped = true }
  c.onSkip()
  assert.strictEqual(c.data.btns, true); assert.match(c.data.phase, /landed/); assert.ok(c.stopped)
  const d = instance(def, { btns: true }); d.data.phase = 'x'; d._stop = () => {}
  d.onSkip(); assert.strictEqual(d.data.phase, 'x')
  assert.match(read('pages/topic/components/cy/pass-reveal/index.wxml'), /bindtap="onSkip"/)
})

test('E-B12 票夹:双击只进一次,失败态在黑场之前就提示', () => {
  const src = read('subpackageMember/signup/index.js')
  const go = src.slice(src.indexOf('  goTicket(e) {'))
  assert.ok(go.indexOf('_ticketTarget') < go.indexOf('entering: true'), '先判再黑场')
  assert.match(src, /_openTicket\(item, kind\) \{\s*if \(this\._navigating\) return;/)
  assert.match(src, /this\._navigating = true;\s*wx\.navigateTo\(\{ url: target\.url, complete/)
})
