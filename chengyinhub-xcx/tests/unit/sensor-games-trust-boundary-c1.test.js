/* C1 传感器小游戏的可信边界(选项 A,2026-10-22 拍板)· 小程序侧
 *
 * 钉住四件事:
 *   ① 服务端发的挑战身份 attemptId 随成绩交上去;没有就不带(存量口径),不编一个;
 *   ② 奖励上限只搬服务端的 rewardCeiling:字段缺失时不承诺奖励、不说「已核验」;
 *   ③ 拍照兜底放行(passed && flagged)说「机会用完了，这张先算过」,不说「这张过了」;
 *   ④ 扫码说清是「任务码匹配」,不是到场核验。
 */
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const contract = require('../../pages/play/utils/sensor-contract.js')
const { pickPlayKit, serverPayload } = require('../../pages/play/utils/playkit-view.js')

const SENSOR_COMPONENTS = ['quiethold', 'shout', 'compass', 'stopwatch', 'photocheck', 'scan']

function loadComponent(rel) {
  const abs = path.join(ROOT, rel)
  const prevComponent = global.Component
  const prevBehavior = global.Behavior
  let captured = null
  global.Component = (o) => { captured = o }
  global.Behavior = (config) => config
  try {
    delete require.cache[require.resolve(abs)]
    require(abs)
  } finally {
    global.Component = prevComponent
    global.Behavior = prevBehavior
  }
  return captured
}

function instanceOf(component) {
  const inst = { data: Object.assign({}, component.data), setData(patch) { Object.assign(this.data, patch) } }
  for (const [name, fn] of Object.entries(component.methods || {})) if (typeof fn === 'function') inst[name] = fn.bind(inst)
  return inst
}

test('attemptOf 只认正的安全整数;别的一律当没有', () => {
  assert.equal(contract.attemptOf(4242001), 4242001)
  assert.equal(contract.attemptOf('4242001'), 4242001)
  for (const bad of [undefined, null, '', 0, -3, 1.5, 'abc', '12a', Number.MAX_SAFE_INTEGER + 2, {}, true]) {
    assert.equal(contract.attemptOf(bad), 0, JSON.stringify(bad))
  }
})

test('kitFields 缺字段时给 0 / 空串,不按保守默认去说', () => {
  assert.deepEqual(contract.kitFields(undefined), { attemptId: 0, rewardCeiling: '' })
  assert.deepEqual(contract.kitFields({ rewardCeiling: 'LOTS', attemptId: 'x' }), { attemptId: 0, rewardCeiling: '' })
  assert.deepEqual(contract.kitFields({ rewardCeiling: 'XP_BADGE_ONLY', attemptId: 9 }), { attemptId: 9, rewardCeiling: 'XP_BADGE_ONLY' })
})

test('rewardNote 只在服务端明说 XP_BADGE_ONLY 时说话;FULL 与缺失都不承诺', () => {
  assert.match(contract.rewardNote('XP_BADGE_ONLY'), /只记探索值与徽章/)
  assert.equal(contract.rewardNote('FULL'), '')
  assert.equal(contract.rewardNote(''), '')
  assert.equal(contract.rewardNote(undefined), '')
})

test('freshAttempt:开局时手里那个号不算,只认之后服务端新发的', () => {
  assert.equal(contract.freshAttempt(22, 11), 22)
  assert.equal(contract.freshAttempt(11, 11), 0)
  assert.equal(contract.freshAttempt(0, 11), 0)
  assert.equal(contract.freshAttempt(5, 0), 5)
})

test('四个设备自报玩法的提交带上 attemptId;没有就不带', () => {
  const cases = [
    ['quiethold', { heldSeconds: 3 }, { heldMs: 3000 }],
    ['shout', { heldMs: 2100 }, { heldMs: 2100 }],
    ['compass', { bearing: 91.6 }, { bearing: 92 }],
    ['stopwatch', { elapsedMs: 9990 }, { stoppedMs: 9990 }],
  ]
  for (const [type, detail, expected] of cases) {
    assert.deepEqual(serverPayload(type, 'submit', detail), expected, type + ' 存量口径不变')
    assert.deepEqual(serverPayload(type, 'submit', Object.assign({ attemptId: 77 }, detail)),
      Object.assign({}, expected, { attemptId: 77 }), type + ' 带上挑战身份')
    assert.equal('attemptId' in serverPayload(type, 'submit', Object.assign({ attemptId: 'forged' }, detail)), false)
  }
})

test('pickPlayKit 原样搬服务端 attemptId / rewardCeiling,缺失即空', () => {
  const now = new Date(2026, 9, 22, 12)
  const view = (seg) => ({ sessionId: 's1', version: 1, playKit: seg })
  const withFields = pickPlayKit(view({ quietHold: { seconds: 5, attemptId: 31, rewardCeiling: 'XP_BADGE_ONLY' } }), now)
  assert.equal(withFields.attemptId, 31)
  assert.equal(withFields.rewardCeiling, 'XP_BADGE_ONLY')
  for (const name of ['quietHold', 'shout', 'stopwatch', 'compass']) {
    const kit = pickPlayKit(view({ [name]: { seconds: 5 } }), now)
    assert.equal(kit.attemptId, 0, name)
    assert.equal(kit.rewardCeiling, '', name)
  }
  const photo = pickPlayKit(view({ photoCheck: { passed: true, flagged: true } }), now)
  assert.equal(photo.photoVerified, false, '缺 photoVerified 就不是已核验')
  assert.equal(photo.rewardCeiling, '')
  assert.equal(pickPlayKit(view({ photoCheck: { passed: true, photoVerified: true, rewardCeiling: 'FULL' } }), now).photoVerified, true)
})

test('分发器把 attemptId / rewardCeiling / photoVerified 绑到组件上', () => {
  const wxml = read('pages/play/components/playkit/index.wxml')
  for (const tag of ['quiethold', 'shout', 'compass', 'stopwatch']) {
    const block = wxml.split('<cy-playkit-' + tag + ' ')[1].split('/>')[0]
    assert.match(block, /attempt-id="\{\{kit\.attemptId\}\}"/, tag)
    assert.match(block, /reward-ceiling="\{\{kit\.rewardCeiling\}\}"/, tag)
  }
  const photo = wxml.split('<cy-playkit-photocheck ')[1].split('/>')[0]
  assert.match(photo, /photo-verified="\{\{kit\.photoVerified\}\}"/)
  assert.match(photo, /reward-ceiling="\{\{kit\.rewardCeiling\}\}"/)
})

function photoVerdict(fields) {
  const component = loadComponent('pages/play/components/playkit-photocheck/index.js')
  const inst = instanceOf(component)
  component.observers['show, tries, maxTries, passed, flagged, degraded, fallback, lastReason'].call(inst, true,
    fields.tries || 1, fields.maxTries || 1, !!fields.passed, !!fields.flagged, !!fields.degraded,
    fields.fallback || 'pass', fields.lastReason || '')
  component.observers['photoVerified, rewardCeiling, passed'].call(inst,
    fields.photoVerified, fields.rewardCeiling || '', !!fields.passed)
  return inst.data
}

test('拍照兜底放行(passed && flagged)说「先算过」,不说「这张过了」,也不说已核验', () => {
  const data = photoVerdict({ passed: true, flagged: true, rewardCeiling: 'XP_BADGE_ONLY' })
  assert.equal(data.verdict, '机会用完了，这张先算过')
  assert.notEqual(data.verdict, '这张过了')
  assert.equal(data.verifiedNote, '')
  assert.match(data.rewardNote, /只记探索值与徽章/)
})

test('视觉模型真判 PASS 才显示已核验;字段缺失时什么都不说', () => {
  const verified = photoVerdict({ passed: true, photoVerified: true, rewardCeiling: 'FULL' })
  assert.equal(verified.verdict, '这张过了')
  assert.equal(verified.verifiedNote, '照片已核验')
  assert.equal(verified.rewardNote, '')
  const missing = photoVerdict({ passed: true })
  assert.equal(missing.verifiedNote, '')
  assert.equal(missing.rewardNote, '')
  const notYet = photoVerdict({ passed: false, photoVerified: true, rewardCeiling: 'FULL' })
  assert.equal(notYet.verifiedNote, '', '没过之前不说已核验')
})

test('扫码这一屏有一行说清是任务码匹配,不是到场核验', () => {
  const wxml = read('pages/play/components/playkit-scan/index.wxml')
  assert.match(wxml, /\{\{matchNote\}\}/)
  const component = loadComponent('pages/play/components/playkit-scan/index.js')
  assert.equal(component.data.matchNote, contract.SCAN_MATCH_NOTE)
  assert.match(contract.SCAN_MATCH_NOTE, /任务码/)
  assert.match(contract.SCAN_MATCH_NOTE, /不代表到场/)
})

test('六个组件里「已核验」只走服务端 photoVerified 那一条,不许写死承诺奖励的话', () => {
  for (const name of SENSOR_COMPONENTS) {
    for (const ext of ['js', 'wxml']) {
      const src = read('pages/play/components/playkit-' + name + '/index.' + ext)
      assert.doesNotMatch(src, /已核验/, name + '.' + ext + ' 不许直接写「已核验」')
      assert.doesNotMatch(src, /(?:可|能|将)(?:获得|领取)(?:积分|券|优惠券|奖励)/, name + '.' + ext + ' 不许承诺奖励')
    }
  }
})

test('安静挑战显示校准状态与相对阈值,不是分贝', () => {
  const wxml = read('pages/play/components/playkit-quiethold/index.wxml')
  assert.match(wxml, /calibration === 'calibrating'/)
  assert.match(wxml, /calibration === 'calibrated'/)
  assert.match(contract.relativeThresholdText(0.27), /^本机阈值 27%（相对满幅振幅，不是分贝）$/)
  assert.equal(contract.relativeThresholdText(0), '')
  assert.equal(contract.relativeThresholdText(NaN), '')
})
