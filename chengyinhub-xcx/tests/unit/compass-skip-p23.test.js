// P-23(2026-11-02 业主批准):罗盘读不出方向时的出路。
// 作者可勾「允许跳过」(默认关);读取失败两次后出现「跳过这一站」;服务端 0 分完成并记下跳过。
// 次数、作者开关、队长放行全在服务端判,客户端只报失败、只发跳过请求、只照服务端的 canSkip 显示按钮。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = (p) => fs.readFileSync(path.join(__dirname, '../../', p), 'utf8');
const { pickPlayKit, serverAction, serverPayload } = require('../../pages/play/utils/playkit-view.js');
const config = require('../../pages/publish/utils/publish/advanced-game-config.js');

function loadCompass() {
  let def = null;
  const prevC = global.Component;
  global.Component = (d) => { def = d; };
  try {
    delete require.cache[require.resolve('../../pages/play/components/playkit-compass/index.js')];
    require('../../pages/play/components/playkit-compass/index.js');
  } finally { global.Component = prevC; }
  return def;
}

test('读取失败与跳过各对应一个服务端动作,只带 game=compass', () => {
  assert.equal(serverAction('compass', 'readfail'), 'REPORT_SENSOR_FAILURE');
  assert.equal(serverAction('compass', 'skip'), 'SKIP_SENSOR_STATION');
  assert.deepEqual(serverPayload('compass', 'readfail', { anything: 1 }), { game: 'compass' });
  assert.deepEqual(serverPayload('compass', 'skip', {}), { game: 'compass' });
  // 别的玩法没有跳过动作:不在册就不发
  assert.equal(serverAction('qa', 'skip'), '');
  assert.equal(serverAction('shout', 'skip'), '');
});

test('跳过按钮只听服务端的 canSkip;跳过过的站显示「已跳过」', () => {
  const view = (compass) => ({ sessionId: 5, version: 2, playKit: { compass } });
  const base = { bearing: 135, tolerance: 15, holdSeconds: 3, done: false };
  assert.equal(pickPlayKit(view(Object.assign({}, base, { failedReads: 2, allowSkip: true }))).canSkip, false,
    '服务端没说能跳就不能跳(不在客户端拿次数和开关自己推)');
  assert.equal(pickPlayKit(view(Object.assign({}, base, { canSkip: true }))).canSkip, true);
  const skipped = pickPlayKit(view(Object.assign({}, base, { done: true, skipped: true })));
  assert.equal(skipped.skipped, true);
  const wxml = read('pages/play/components/playkit-compass/index.wxml');
  assert.match(wxml, /wx:if="\{\{phase === 'idle' && !done && canSkip\}\}" class="cp__skip" bindtap="onSkip"/);
  assert.match(wxml, />跳过这一站</);
  assert.match(wxml, /wx:elif="\{\{done && skipped\}\}" class="g-sub cp__s">这一站已跳过</);
  assert.match(wxml, /wx:elif="\{\{done && !skipped\}\}" class="cp__done"/);
  // 跳过的站不亮绿:绿色是「对准了」,跳过不是对准
  assert.match(wxml, /class="cp \{\{aligned \|\| \(done && !skipped\) \? 'cp--on' : ''\}\}/);
  assert.match(read('pages/play/components/playkit/index.wxml'),
    /can-skip="\{\{kit\.canSkip\}\}" skipped="\{\{kit\.skipped\}\}"[\s\S]*?bind:readfail="onReadFail" bind:skip="onSkip"/);
  // 跳过按钮是可点的大小
  assert.match(read('pages/play/components/playkit-compass/index.wxss'), /\.cp__skip \{[^}]*min-height: 88rpx;/);
});

test('读取失败时组件报一次 readfail;没有 canSkip 时点跳过不发', () => {
  const def = loadCompass();
  const events = [];
  const ctx = {
    data: { show: true, canSkip: false, done: false, stageKeys: [0] },
    setData(p) { Object.assign(this.data, p); },
    triggerEvent(name) { events.push(name); },
    selectComponent() { return null; },
  };
  Object.keys(def.methods).forEach((k) => { ctx[k] = def.methods[k]; });
  const attempt = { phase: 'run' };
  ctx._attempt = attempt;
  const prevWx = global.wx;
  global.wx = { showToast() {} };
  try {
    ctx._failed(attempt);
  } finally { global.wx = prevWx; }
  assert.deepEqual(events, ['readfail']);
  assert.equal(ctx.data.noData, true);

  ctx.onSkip();
  assert.deepEqual(events, ['readfail'], 'canSkip 为假时不发跳过');
  ctx.data.canSkip = true;
  ctx.onSkip();
  assert.deepEqual(events, ['readfail', 'skip']);
});

test('编辑页:「允许跳过」开关默认关,存成布尔', () => {
  const model = config.defaultConfig();
  assert.equal(model.compass.allowSkip, false);
  const on = config.normalize(Object.assign({}, model, {
    compass: Object.assign({}, model.compass, { enabled: true, bearing: 90, allowSkip: true }),
  }));
  assert.equal(on.compass.allowSkip, true);
  const junk = config.normalize(Object.assign({}, model, {
    compass: Object.assign({}, model.compass, { enabled: true, bearing: 90, allowSkip: 'yes' }),
  }));
  assert.equal(junk.compass.allowSkip, false, '只有真的勾上才算');
  assert.match(read('pages/publish/temp/index.wxml'),
    /<view class="cg-rw-name">允许跳过<\/view><\/view><cy-switch checked="\{\{advanced\.compass\.allowSkip\}\}" data-key="compass\.allowSkip" bindchange="onAdvancedSubToggle"/);
});
