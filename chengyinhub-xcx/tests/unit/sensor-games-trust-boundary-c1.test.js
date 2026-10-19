'use strict';

/* C1 · 传感器小游戏的可信边界与降级(F0334–F0339 · M0103–M0106 · F0308/F0309/F0399/F0329)
 *
 * 命门:
 *   1. 设备自报成绩只算娱乐成绩 —— 奖励上限以服务端 rewardCeiling 为准,没回时按保守默认说;
 *   2. 提交带服务端挑战身份(attemptId),中断重开后旧 attempt 不能结算;
 *   3. 切后台按玩法规则中断,不白赚时间;传感器/麦克风不可用时明说,不给模拟成绩;
 *   4. 拍照旧兜底不许标成「过了」,静态码只标「任务码匹配」;
 *   5. 3D 徽章资源超时/静态图失败都有静态兜底,名称与描述可读屏。
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const contract = require('../../pages/play/utils/sensor-contract.js');
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');

function loadComponent(rel, wx, timers) {
  let definition = null;
  global.Component = (config) => { definition = config; };
  global.Behavior = (config) => config;
  global.wx = Object.assign({ vibrateShort() {}, showToast() {}, getStorageSync: () => '', getSystemInfoSync: () => ({}) }, wx || {});
  const t = timers || {};
  global.setInterval = t.setInterval || (() => ({ fake: true }));
  global.clearInterval = () => {};
  global.setTimeout = t.setTimeout || (() => ({ fake: true }));
  global.clearTimeout = () => {};
  const abs = path.join(ROOT, rel);
  const m = new Module(abs, null);
  m.filename = abs;
  m.paths = Module._nodeModulePaths(path.dirname(abs));
  m._compile(fs.readFileSync(abs, 'utf8'), abs);
  assert.ok(definition, rel + ' 必须注册 Component');
  const events = [];
  const stage = { calls: [], begin() { this.calls.push('begin'); }, win(t) { this.calls.push('win:' + t); },
    fail() { this.calls.push('fail'); }, _stopAll() { this.calls.push('stopAll'); }, miss() { return false; }, data: {} };
  const vm = Object.assign({}, definition.methods, { data: JSON.parse(JSON.stringify(definition.data || {})) });
  for (const [k, v] of Object.entries(definition.properties || {})) if (!(k in vm.data)) vm.data[k] = v.value;
  vm.setData = (patch) => Object.assign(vm.data, patch);
  vm.triggerEvent = (name, detail) => events.push({ name, detail });
  vm.selectComponent = () => stage;
  for (const [name, fn] of Object.entries(definition.methods || {})) if (typeof fn === 'function') vm[name] = fn.bind(vm);
  return { definition, vm, events, stage };
}

const at = new Date(2026, 9, 12, 12);
const kitOf = (section, seg) => pickPlayKit({ sessionId: 's1', version: 1, playKit: { [section]: Object.assign({ title: 't' }, seg) } }, at);

test('M0103/M0104/M0105/M0106:六个传感器玩法逐条声明单位、证据、四种降级与前后台规则', () => {
  for (const game of ['reaction', 'ballShake', 'quietHold', 'shout', 'stopwatch', 'compass']) {
    const r = contract.rule(game);
    assert.ok(r && r.unit && r.measures, game + ' 缺单位/测量对象');
    assert.equal(r.evidence, 'SELF_REPORTED', game + ' 是设备自报成绩');
    assert.ok(['abort-round', 'abort-attempt', 'continue'].includes(r.background), game + ' 前后台规则不在册');
    assert.ok(r.backgroundText, game + ' 缺前后台规则文案');
    for (const key of ['noSensor', 'permissionDenied', 'resourceFailed', 'lowMotion']) {
      assert.ok(r.capability[key], game + ' 设备能力矩阵缺 ' + key);
    }
  }
  for (const game of ['ballShake', 'quietHold', 'shout', 'compass']) {
    const c = contract.rule(game).calibration;
    assert.ok(c && c.range && c.unit && c.invalidWhen, game + ' 校准必须写清范围、单位与失效条件');
    assert.ok(contract.calibrationNote(game).includes(c.unit));
  }
  assert.equal(contract.capabilityMatrix().length, 6);
});

test('★奖励口径以服务端 rewardCeiling 为准;没回字段时按保守默认(自报 = 娱乐成绩)', () => {
  assert.equal(contract.evidenceNote('reaction', { rewardCeiling: 'XP_BADGE_ONLY' }), contract.ENTERTAINMENT_NOTE);
  assert.equal(contract.evidenceNote('reaction', { rewardCeiling: 'FULL' }), '');
  assert.equal(contract.evidenceNote('reaction', {}), contract.ENTERTAINMENT_NOTE);
  assert.equal(contract.evidenceNote('countdown', {}), '');
  assert.ok(!/券[^或]|积分到账|可领/.test(contract.ENTERTAINMENT_NOTE.replace('不发券或积分', '')), '娱乐成绩文案不能承诺资金权益');
});

test('★pickPlayKit 把证据说明、前后台规则、服务端挑战身份带给传感器 kit', () => {
  const rx = kitOf('reaction', { rounds: 3, goalMs: 300, attemptId: 4, rewardCeiling: 'XP_BADGE_ONLY' });
  assert.equal(rx.attemptId, 4);
  assert.equal(rx.evidenceNote, contract.ENTERTAINMENT_NOTE);
  assert.equal(rx.bgRule, contract.backgroundNote('reaction'));
  for (const section of ['ballShake', 'quietHold', 'shout', 'stopwatch', 'compass']) {
    const kit = kitOf(section, {});
    assert.equal(kit.attemptId, 0, section + ' 没开表时挑战身份为 0');
    assert.ok(kit.bgRule, section + ' 缺前后台规则');
  }
});

test('★页面提交时带上 attemptId —— 旧 attempt 的成绩由服务端拒收', () => {
  const src = read('pages/play/index.js');
  assert.match(src, /if \(action === 'submit' && kit\.attemptId > 0\) payload\.attemptId = kit\.attemptId;/);
  assert.match(src, /data\.rewardCeiling === 'XP_BADGE_ONLY'/, '服务端说只发探索值时要照实说');
});

test('F0334:反应速度切后台整次作废 —— 已测的几轮清空,下次开始重新开表', () => {
  const { vm, events } = loadComponent('pages/play/components/playkit-reaction/index.js');
  vm._times = [260, 270];
  vm._armed = true;
  vm.data.phase = 'idle';
  vm._abortRound();
  assert.deepEqual(vm._times, []);
  assert.equal(vm._armed, false);
  assert.match(vm.data.sub, /本次作废/);
  vm._arm();
  assert.equal(events.filter((e) => e.name === 'start').length, 1, '重新开始必须重新向服务器开表');
  assert.match(read('pages/play/components/playkit-reaction/index.wxml'), /\{\{unit\}\}/, '成绩要带测量单位');
});

test('F0335:读不到重力感应就中断并明说,不提交模拟分数', () => {
  const { vm, events } = loadComponent('pages/play/components/playkit-ballshake/index.js', {
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 700 }),
    startAccelerometer(o) { if (o && o.fail) o.fail({ errMsg: 'no sensor' }); },
    onAccelerometerChange() {}, offAccelerometerChange() {}, stopAccelerometer() {},
  });
  vm.onRun();
  assert.equal(vm.data.running, false);
  assert.match(vm.data.notice, /读不到重力感应/);
  assert.equal(events.filter((e) => e.name === 'submit').length, 0, '传感器不可用时不许交成绩');
});

test('F0335:中途切后台本局中断,不交成绩', () => {
  const { definition, vm, events } = loadComponent('pages/play/components/playkit-ballshake/index.js', {
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 700 }),
    startAccelerometer() {}, onAccelerometerChange() {}, offAccelerometerChange() {}, stopAccelerometer() {},
  });
  vm.onRun();
  assert.equal(vm.data.running, true);
  definition.pageLifetimes.hide.call(vm);
  assert.equal(vm.data.running, false);
  assert.match(vm.data.notice, /中断/);
  vm._finish(true);
  assert.equal(events.filter((e) => e.name === 'submit').length, 0, '中断后的这一局不再提交');
});

test('F0337/F0338:拒绝麦克风给授权替代(去设置 / 跳过不记成绩),不假装在听', () => {
  for (const rel of ['pages/play/components/playkit-quiethold/index.js', 'pages/play/components/playkit-shout/index.js']) {
    let errCb = null;
    const { vm, events } = loadComponent(rel, {
      getRecorderManager: () => ({ onFrameRecorded() {}, onError(cb) { errCb = cb; }, start() {}, stop() {} }),
    });
    vm._samples = [];
    vm._startMic();
    errCb({ errMsg: 'auth deny' });
    assert.equal(vm.data.micDenied, true, rel);
    assert.ok(events.some((e) => e.name === 'micdenied'));
    const wxml = read(rel.replace('index.js', 'index.wxml'));
    assert.match(wxml, /bindtap="onOpenMicSetting"/);
    assert.match(wxml, /bindtap="onSkipMic"/);
    assert.match(wxml, /不记成绩/);
  }
});

test('F0337:校准后显示本机相对阈值(不是分贝),本机时长不冒充可信完成', () => {
  const { vm } = loadComponent('pages/play/components/playkit-quiethold/index.js');
  vm._samples = [0.02, 0.03, 0.02];
  vm.onCalibrated();
  assert.equal(vm.data.calibrated, true);
  assert.match(vm.data.thresholdText, /本机阈值 \d+%（相对满幅，不是分贝）/);
  vm._held = 15;
  vm.data.seconds = 15;
  vm._finish(true);
  assert.match(vm.data.heldText, /以最终复核为准/);
});

test('F0338:相对音量按本机过线阈值折成 0–100,并有舒适提示', () => {
  const { vm } = loadComponent('pages/play/components/playkit-shout/index.js');
  assert.equal(vm._relativePct(0.15, 0.3), 50);
  assert.equal(vm._relativePct(0.9, 0.3), 100);
  assert.equal(vm._relativePct(0.1, 0), 0);
  assert.match(vm.data.comfort, /不用喊到嗓子疼/);
});

test('F0339:罗盘起不来明示来源不足;精度只在设备给了时才说', () => {
  const { vm } = loadComponent('pages/play/components/playkit-compass/index.js', {
    startCompass(o) { if (o && o.fail) o.fail({ errMsg: 'unsupported' }); },
    onCompassChange() {}, offCompassChange() {}, stopCompass() {},
  });
  vm.onRun();
  assert.equal(vm.data.unavailable, true);
  assert.equal(vm.data.phase, 'idle');
  assert.equal(vm._accuracyText('unreliable'), '读数不可靠：可能有磁干扰');
  assert.equal(vm._accuracyText(12), '', 'iOS 数字偏角不表精度,不编');
  assert.match(read('pages/play/components/playkit-compass/index.wxml'), /来源不足/);
});

test('F0308:拍照旧兜底放行不许标成「过了」,只有真判 PASS 才算照片验收', () => {
  const { definition, vm } = loadComponent('pages/play/components/playkit-photocheck/index.js');
  vm.replayMotion = () => {};   // behavior 方法,单测里不播动效
  const observer = definition.observers['show, tries, maxTries, passed, flagged, degraded, fallback, lastReason'];
  vm.data.photoVerified = false;
  observer.call(vm, true, 1, 1, true, true, false, 'pass', '');
  assert.notEqual(vm.data.verdict, '这张过了');
  assert.notEqual(vm.data.verdictKind, 'ok');
  assert.match(vm.data.reason, /不算照片验收通过/);
  vm.data.photoVerified = true;
  observer.call(vm, true, 1, 1, true, false, false, 'retake', '');
  assert.equal(vm.data.verdict, '这张过了');

  assert.equal(kitOf('photoCheck', { passed: true, flagged: true, fallback: 'pass' }).photoVerified, false);
  assert.equal(kitOf('photoCheck', { passed: true }).photoVerified, true);
  assert.equal(kitOf('photoCheck', { passed: true, photoVerified: false }).photoVerified, false);
});

test('F0309:静态节点码只标「任务码匹配」', () => {
  assert.equal(kitOf('scan', {}).evidence, 'TASK_CODE_MATCH');
  assert.match(read('pages/play/components/playkit-scan/index.wxml'), /任务码匹配 · 不作现场资格核验/);
});

test('F0329:暂停入口按现规则说清 —— 只停本机用时,限时任务按服务器时间继续', () => {
  assert.match(read('pages/play/index.wxml'), /暂停只停本机用时，已开始的限时任务照常计时/);
});

test('F0399:glow 资源超时也退静态图;静态图失败退正式素材;名称与描述可读屏', () => {
  let definition;
  const timers = [];
  global.Page = (config) => { definition = config; };
  global.wx = { getMenuButtonBoundingClientRect: () => ({ top: 52 }) };
  global.setTimeout = (fn) => { timers.push(fn); return timers.length; };
  global.clearTimeout = () => {};
  const PAGE = path.join(ROOT, 'subpackageP3/pages/badge-3d/index.js');
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); },
  });
  page.onLoad({ img: encodeURIComponent('https://cdn.example.com/b.png'), name: encodeURIComponent('城市提案'),
    sub: encodeURIComponent('走完十站'), style: 'glow' });
  assert.equal(page.data.a11yLabel, '城市提案，走完十站');
  timers.length = 0;
  page.onShow();
  assert.equal(timers.length, 1, 'glow 也要挂资源超时');
  timers[0]();
  assert.equal(page.data.renderState, 'fallback');
  assert.equal(page.data.fallbackSrc, 'https://cdn.example.com/b.png', '超时保留真实徽章图');
  page.onFallbackError();
  assert.equal(page.data.fallbackSrc, '/subpackageP3/assets/badge-demo.png');
  const wxml = read('subpackageP3/pages/badge-3d/index.wxml');
  assert.match(wxml, /aria-label="\{\{a11yLabel\}\}"/);
  assert.match(wxml, /binderror="onFallbackError"/);
});
