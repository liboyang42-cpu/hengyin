const test = require('node:test');
const assert = require('node:assert/strict');
const { applyPreviewAction } = require('../../utils/game-preview-action.js');

test('本地建档与D20试玩保留当前配置，不修改原始kit', () => {
  const kit = { type: 'profile', questions: [{ key: 'name', kind: 'text', maxLength: 12, required: true }], answers: {}, done: false };
  const state = applyPreviewAction(kit, { action: 'submit', detail: { answers: { name: '小林' } } });
  assert.equal(state.kit.answers.name, '小林');
  assert.equal(state.ready, true);
  assert.equal(kit.done, false);
  const dice = applyPreviewAction({ type: 'diceroll', mode: 'd20', dc: 14, modifier: 2,
    rollMode: 'advantage', successText: '暗门打开', failText: '暗门没有打开' }, { action: 'roll' });
  assert.deepEqual(dice.kit.result.values, [12, 7]);
  assert.equal(dice.kit.result.total, 14);
  assert.equal(dice.kit.result.text, '暗门打开');
  assert.equal(dice.ready, true);
});


test('输入试玩计时和尝试上限生效，照片只留本地路径', () => {
  const kit = { type: 'typein', target: 'HELLO', seconds: 3, caseSensitive: false, tries: 1, attempts: 0 };
  assert.equal(applyPreviewAction(kit, { action: 'submit', detail: { text: 'hello' } }, 0, 3000), null);
  const start = applyPreviewAction(kit, { action: 'start' }, 0, 1000);
  assert.equal(start.kit.startConfirmed, true);
  const late = applyPreviewAction(start.kit, { action: 'submit', detail: { text: 'hello' } }, start.startedAt, 4001);
  assert.equal(late.kit.startConfirmed, false);
  assert.equal(late.kit.passed, false);
  assert.equal(late.ready, true);
  assert.equal(applyPreviewAction(late.kit, { action: 'start' }, 0, 5000), null);
  const photo = applyPreviewAction({ type: 'photocheck', passed: false }, { action: 'shoot', detail: { tempFilePath: 'wxfile://photo' } });
  assert.equal(photo.kit.lastUrl, 'wxfile://photo');
  assert.equal(photo.kit.passed, false);
  assert.equal(photo.ready, false);
});


test('资料预览全题必答，非法选项/超长输入/配置必填头像均不能完成', () => {
  const kit = { type: 'profile', questions: [
    { key: 'name', kind: 'text', maxLength: 3, required: false },
    { key: 'job', kind: 'pick', options: [{ key: 'clerk', label: '店员' }] }
  ], answers: {}, done: false, avatar: { enabled: true, required: false } };
  const submit = answers => applyPreviewAction(kit, { action: 'submit', detail: { answers } });
  assert.equal(submit({ name: '   ', job: 'clerk' }), null);
  assert.equal(submit({ name: '小林', job: 'fake' }), null);
  assert.equal(submit({ name: '名字太长了', job: 'clerk' }), null);
  assert.equal(submit({ name: '小林' }), null);
  assert.equal(kit.done, false);
  assert.equal(submit({ name: '小林', job: 'clerk' }).ready, true);
  kit.avatar.required = true;
  assert.equal(submit({ name: '小林', job: 'clerk' }), null);
  kit.avatarUrl = 'wxfile://avatar';
  assert.equal(submit({ name: '小林', job: 'clerk' }).kit.done, true);
});

test('D20 作者预览标为沙盒，加值来源为模板固定值；正式玩法不自造加值来源', () => {
  const fs = require('node:fs'); const path = require('node:path');
  const { previewD20Result } = require('../../utils/game-preview-action.js');
  const result = previewD20Result({ rollMode: 'normal', modifier: 3, dc: 10, successText: '成', failText: '败' });
  assert.equal(result.sandbox, true);
  assert.equal(result.modifierSource, 'TEMPLATE_FIXED');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit-diceroll/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{preview\}\}"[^>]*>\s*<text>沙盒预览 · 不计入正式判定<\/text>\s*<text>加值为作者在模板中设定的固定值<\/text>/);
  const source = fs.readFileSync(path.join(__dirname, '../../pages/play/utils/playkit-view.js'), 'utf8');
  assert.match(source, /modifier: seg\.modifier, rollMode: seg\.rollMode, preview: false,/);
  assert.doesNotMatch(source, /modifierSource/);
});


const preview = require('../../pages/publish/utils/publish/advanced-game-preview.js');
function r5Typing(duration, timeoutResult = 'FAILED') {
  return preview.buildPreviewKit({ schemaVersion: 1,
    typeIn: { enabled: true, title: '输入', target: 'HELLO', seconds: duration === 20 ? 60 : 20, tries: 0 },
    outcomeRules: { version: 1, pass: {}, fail: {} },
    timer: { enabled: duration > 0, durationSeconds: duration || 60, timeoutResult } });
}

test('R5 timer 关闭时，旧 seconds 不限制试玩，也不能凭空到期', () => {
  const kit = r5Typing(0);
  assert.equal(applyPreviewAction(kit, { action: 'refresh' }, 0, 90000), null);
  const start = applyPreviewAction(kit, { action: 'start' }, 0, 100000);
  assert.equal(start.kit.deadlineAt, 0);
  const submitted = applyPreviewAction(start.kit, { action: 'submit', detail: { text: 'hello', elapsedMs: 0 } }, start.startedAt, 300000);
  assert.equal(submitted.kit.passed, true);
  assert.equal(submitted.ready, true);
});

for (const duration of [20, 60]) {
  test('R5 ' + duration + ' 秒从真实预览 START 开表，到期后显式重试拿新 deadline', () => {
    const kit = r5Typing(duration);
    const snapshot = JSON.stringify(kit), now = 100000;
    assert.equal(kit.deadlineAt, 0);
    assert.equal(applyPreviewAction(kit, { action: 'refresh' }, 0, now - 1), null);
    assert.equal(applyPreviewAction(kit, { action: 'submit', detail: { text: 'HELLO' } }, 0, now), null);
    const start = applyPreviewAction(kit, { action: 'start' }, 0, now);
    assert.equal(start.startedAt, now);
    assert.equal(start.kit.deadlineAt, now + duration * 1000);
    assert.equal(JSON.stringify(kit), snapshot, '真实配置投影不能被试玩动作原地改写');
    assert.equal(applyPreviewAction(start.kit, { action: 'start' }, start.startedAt, now + 1000), null, '重复开始不能续表');
    assert.equal(applyPreviewAction(start.kit, { action: 'refresh' }, start.startedAt, start.kit.deadlineAt - 1), null);
    const passed = applyPreviewAction(start.kit, { action: 'submit', detail: { text: 'hello' } }, start.startedAt, start.kit.deadlineAt - 1);
    assert.equal(passed.kit.passed, true);
    const expired = applyPreviewAction(start.kit, { action: 'refresh' }, start.startedAt, start.kit.deadlineAt);
    assert.equal(expired.kit.passed, false);
    assert.equal(expired.kit.startConfirmed, false);
    assert.equal(expired.kit.deadlineAt, 0);
    assert.equal(expired.ready, true, '到期走既有试玩失败反馈与重试入口');
    assert.equal(expired.startedAt, 0);
    assert.equal(expired.kit.attempts, 1);
    assert.equal(applyPreviewAction(expired.kit, { action: 'refresh' }, expired.startedAt, now + 90000), null);
    assert.equal(applyPreviewAction(expired.kit, { action: 'submit', detail: { text: 'HELLO' } }, expired.startedAt, now + 90000), null);
    const retry = applyPreviewAction(expired.kit, { action: 'start' }, 0, now + 90000);
    assert.equal(retry.kit.deadlineAt, now + 90000 + duration * 1000);
    const recovered = applyPreviewAction(retry.kit, { action: 'submit', detail: { text: 'HELLO' } }, retry.startedAt, now + 90001);
    assert.equal(recovered.kit.passed, true);
  });
}

test('R5 提交不用客户端 elapsedMs 冲掉真实预览 deadline，保留超时结果及错误反馈', () => {
  const started = applyPreviewAction(r5Typing(20), { action: 'start' }, 0, 1000);
  const late = applyPreviewAction(started.kit, { action: 'submit', detail: { text: 'HELLO', elapsedMs: 1 } }, started.startedAt, 22000);
  assert.equal(late.kit.passed, false);
  const wrong = applyPreviewAction(started.kit, { action: 'submit', detail: { text: 'WRONG' } }, started.startedAt, 1001);
  assert.equal(wrong.kit.passed, false); assert.equal(wrong.ready, true);
  const completion = applyPreviewAction(r5Typing(60, 'COMPLETED'), { action: 'start' }, 0, 1000);
  const expired = applyPreviewAction(completion.kit, { action: 'refresh' }, completion.startedAt, 61000);
  assert.equal(expired.kit.passed, true); assert.equal(expired.ready, true);
});

test('legacy 20/60 秒仍按原上限，R5改动不借全局timer放宽旧玩法', () => {
  for (const seconds of [20, 60]) {
    const kit = preview.buildPreviewKit({ typeIn: { enabled: true, target: 'HELLO', seconds, tries: 0 },
      timer: { enabled: true, durationSeconds: 120, timeoutResult: 'COMPLETED' } });
    const start = applyPreviewAction(kit, { action: 'start' }, 0, 1000);
    const late = applyPreviewAction(start.kit, { action: 'submit', detail: { text: 'HELLO' } }, start.startedAt, 1001 + seconds * 1000);
    assert.equal(late.kit.passed, false); assert.equal(late.ready, false);
    assert.equal(applyPreviewAction(start.kit, { action: 'refresh' }, start.startedAt, 1001 + seconds * 1000), null);
  }
});


test('真实作者页：打开→等待→START→到期反馈→重试→提交，不发正式请求或改配置', t => {
  let definition, now = 100000;
  const requests = [];
  const app = { globalData: {}, tips() {}, getUserID: () => 9, getAuthorization: () => '',
    sendRequest(request) { requests.push(request); } };
  global.getApp = () => app;
  global.wx = { ...require('../helpers/memory-storage.js')(), getBackgroundAudioManager: () => ({ stop() {} }), showToast() {} };
  global.Page = value => { definition = value; };
  const file = require.resolve('../../pages/publish/temp/index.js');
  delete require.cache[file]; require(file);
  const p = { ...definition, data: JSON.parse(JSON.stringify(definition.data)) };
  p.setData = function (patch, done) {
    for (const [path, value] of Object.entries(patch)) {
      const keys = path.split('.'); let at = this.data;
      for (const key of keys.slice(0, -1)) at = at[key];
      at[keys.at(-1)] = value;
    }
    if (done) done();
  };
  p.syncNativeTheme = () => {}; // 微信导航配色边界；开表、判定、反馈与重试都用真实页面方法。
  p.data.advanced = { schemaVersion: 1,
    typeIn: { enabled: true, title: '作者输入', target: 'HELLO', seconds: 60, tries: 0 },
    timer: { enabled: true, durationSeconds: 20, timeoutResult: 'FAILED' },
    outcomeRules: { version: 1, pass: { reply: '输入完成' }, fail: { reply: '可以重来' } } };
  const source = JSON.stringify(p.data.advanced);
  t.mock.method(Date, 'now', () => now);
  p.tryCurrentGame();
  assert.equal(p.data.pvKit.r5, true);
  assert.equal(p.data.pvKit.deadlineAt, 0);
  now += 90000;
  p.onPvKitAction({ detail: { type: 'typein', action: 'start' } });
  assert.equal(p.data.pvKit.deadlineAt, now + 20000);
  now += 20000;
  p.onPvKitAction({ detail: { type: 'typein', action: 'refresh' } });
  assert.equal(p.data.pvVerdict.show, true);
  assert.equal(p.data.pvVerdict.kind, 'fail');
  assert.equal(p.data.pvVerdict.text, '可以重来');
  p.closePvVerdict();
  assert.equal(p.data.pvKit.deadlineAt, 0);
  assert.equal(p.data.pvKit.startConfirmed, false);
  now += 30000;
  p.onPvKitAction({ detail: { type: 'typein', action: 'start' } });
  assert.equal(p.data.pvKit.deadlineAt, now + 20000);
  now++;
  p.onPvKitAction({ detail: { type: 'typein', action: 'submit', detail: { text: 'HELLO' } } });
  assert.equal(p.data.pvVerdict.kind, 'win');
  assert.equal(p.data.pvVerdict.text, '输入完成');
  assert.equal(JSON.stringify(p.data.advanced), source);
  assert.equal(requests.length, 0);
});

function previewOwnerPage() {
  const fs = require('node:fs'), path = require('node:path');
  let definition, verdictDefinition, member = '9', authorization = 'preview-session-a', deferred = false;
  const callbacks = [], requests = [];
  const app = { globalData: {}, getUserID: () => member, getAuthorization: () => authorization,
    tips() {}, sendRequest: request => { requests.push(request); assert.fail('作者试玩不得发送正式请求'); } };
  global.getApp = () => app; global.Behavior = value => value;
  global.wx = { ...require('../helpers/memory-storage.js')(), getBackgroundAudioManager: () => ({ stop() {} }),
    showToast() {}, setNavigationBarColor() {}, setBackgroundColor() {} };
  global.Page = value => { definition = value; };
  const pageFile = require.resolve('../../pages/publish/temp/index.js'); delete require.cache[pageFile]; require(pageFile);
  global.Component = value => { verdictDefinition = value; };
  const verdictFile = require.resolve('../../pages/play/components/play-verdict/index.js'); delete require.cache[verdictFile]; require(verdictFile);
  const p = { ...definition, data: structuredClone(definition.data) };
  p.setData = function (patch, done) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let at = this.data;
      for (const part of parts.slice(0, -1)) at = at[part];
      at[parts.at(-1)] = value;
    }
    if (done) { if (deferred) callbacks.push(done); else done(); }
  };
  p.data.advanced = { qa: { enabled: true, mode: 'TYPE', title: '作者原题', answerText: '排队', maxTries: 0 },
    answerPolicy: { version: 1, normalizationPolicy: 'LEGACY_V1', acceptedAliases: ['待发送'] },
    outcomeRules: { version: 1, pass: {}, fail: {} } };
  p.data.from = 'fabu'; p.tryCurrentGame();
  const host = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8').match(/<cy-play-verdict\b[^>]*>/)[0];
  const closeHandler = host.match(/bind:close="([^"]+)"/)[1];
  const cta = fs.readFileSync(path.join(__dirname, '../../pages/play/components/play-verdict/index.wxml'), 'utf8').match(/<view class="pv__cta"[^>]*>/)[0];
  const tap = cta.match(/bindtap="([^"]+)"/)[1];
  const verdict = { ...verdictDefinition.methods, triggerEvent: name => { assert.equal(name, 'close'); p[closeHandler](); } };
  return { p, callbacks, requests, close: () => verdict[tap](), defer: () => { deferred = true; }, immediate: () => { deferred = false; },
    flush: () => { while (callbacks.length) callbacks.shift()(); },
    submit: input => p.onPvKitAction({ detail: { type: 'qa', action: 'submit', detail: { mode: 'type', input } } }),
    account: () => { member = '10'; }, session: () => { authorization = 'preview-session-b'; } };
}

for (const kind of ['win', 'fail']) test('PC-OWNER 同一' + kind + '结果重复close只消费当前反馈一次', () => {
  const f = previewOwnerPage(), p = f.p;
  p.setData({ pvSteps: p._buildPvSteps() });
  f.submit(kind === 'win' ? '待发送' : '错误输入'); assert.equal(p.data.pvVerdict.kind, kind);
  if (kind === 'fail') f.defer();
  f.close(); f.close(); f.flush();
  assert.equal(p.data.previewVisible, true);
  assert.equal(p.data.pvStep, kind === 'win' ? 1 : 0);
  assert.equal(p.data.pvVerdict.show, false); assert.ok(p.data.pvKit);
});

for (const change of ['close', 'new-type', 'new-same-type', 'account', 'session', 'hide', 'hide-show', 'unload', 'next-step']) {
  test('PC-OWNER 失败重试ack跨' + change + '遵守当前试玩owner', () => {
    const f = previewOwnerPage(), p = f.p;
    if (change === 'next-step') p.setData({ pvSteps: p._buildPvSteps() });
    f.submit('错误输入'); assert.equal(p.data.pvVerdict.kind, 'fail');
    f.defer(); f.close(); assert.equal(p.data.pvKit, null); assert.equal(f.callbacks.length, 1);
    if (change === 'close') p.closePreview();
    if (change.startsWith('new-')) {
      p.closePreview(); f.immediate();
      if (change === 'new-type') p.previewGameFromSheet({ currentTarget: { dataset: { key: 'typeIn' } } });
      else p.tryCurrentGame();
    }
    if (change === 'account') f.account();
    if (change === 'session') f.session();
    if (change === 'hide' || change === 'hide-show') p.onHide();
    if (change === 'hide-show') p.onShow();
    if (change === 'unload') p.onUnload();
    if (change === 'next-step') p.pvNext();
    const current = p.data.pvKit;
    f.flush();
    if (change === 'hide' || change === 'hide-show') {
      assert.equal(p.data.pvKit.type, 'qa', '隐藏不关闭同一次试玩，合法重试仍能恢复');
      assert.equal(p.data.previewVisible, true);
      if (change === 'hide') p.onShow();
      assert.equal(p.data.pvKit.type, 'qa');
    } else assert.equal(p.data.pvKit, current);
    assert.equal(p.data.pvVerdict.show, false); assert.equal(f.requests.length, 0);
  });
}

test('PC-OWNER 隐藏页迟到close不消费结果，回前台后原试玩可正常关闭', () => {
  const f = previewOwnerPage(), p = f.p;
  f.submit('待发送'); p.onHide(); f.close();
  assert.equal(p.data.pvVerdict.show, true); assert.equal(p.data.previewVisible, true);
  p.onShow(); f.close(); assert.equal(p.data.previewVisible, false);
});

test('PC-OWNER 正常成功末步关闭、失败ack重试保留canonical别名和原配置', () => {
  for (const input of ['排队', '待发送']) {
    const f = previewOwnerPage(), p = f.p, original = JSON.stringify(p.data.advanced);
    f.submit(input); assert.equal(p.data.pvVerdict.kind, 'win'); f.close();
    assert.equal(p.data.previewVisible, false); assert.equal(p.data.pvKit, null);
    assert.equal(JSON.stringify(p.data.advanced), original);
  }
  const f = previewOwnerPage(), p = f.p, original = JSON.stringify(p.data.advanced);
  f.submit('错误输入'); f.defer(); f.close(); assert.equal(p.data.pvKit, null);
  f.flush(); assert.equal(p.data.pvKit.type, 'qa'); assert.equal(p.data.pvKit.startConfirmed, false);
  assert.equal(p.data.previewVisible, true); assert.equal(p.data.pvStep, 0);
  f.submit('待发送'); assert.equal(p.data.pvVerdict.kind, 'win');
  assert.equal(JSON.stringify(p.data.advanced), original); assert.equal(f.requests.length, 0);
});


test('全屏成功完成态：末步完成退出本地预览，有奖励只进入奖励页，重复及退役点击无效', () => {
  const { p, requests } = previewOwnerPage();
  const original = JSON.stringify(p.data.advanced);
  p.setData({ previewVisible: true, pvSteps: ['complete'], pvStep: 0, pvStepKey: 'complete', pvKit: null, rwAny: false });
  p.completePreview();
  assert.equal(p.data.previewVisible, false, '末步完成必须退出，不能停在完成屏');
  p.completePreview();
  assert.equal(p.data.previewVisible, false);
  p.setData({ previewVisible: true, pvSteps: ['complete', 'reward'], pvStep: 0, pvStepKey: 'complete', rwAny: true });
  for (const flag of ['_hidden', '_disposed', '_unloaded']) {
    p[flag] = true; p.completePreview(); assert.equal(p.data.pvStep, 0); p[flag] = false;
  }
  p.completePreview();
  assert.equal(p.data.pvStepKey, 'reward');
  assert.equal(p.data.previewVisible, true, '奖励仍使用原有预览页');
  p.completePreview();
  assert.equal(p.data.pvStep, 1, '旧完成事件不能跳过奖励页');
  assert.equal(JSON.stringify(p.data.advanced), original);
  assert.equal(requests.length, 0, '预览不发正式游玩或领奖请求');
  p.setData({ rwText: true, rwCoupon: false, rwMedal: false, rwAny: true, 'formData.feedbackText': '', storyBeats: [] });
  const steps = p._buildPvSteps();
  assert.equal(steps.at(-1), 'complete', '空奖励不生成奖励步骤');
  p.setData({ previewVisible: true, pvSteps: steps, pvStep: steps.length - 1, pvStepKey: 'complete' });
  p.completePreview();
  assert.equal(p.data.previewVisible, false, '奖励开关开启但没有奖励步骤时也必须能完成退出');
  assert.equal(requests.length, 0);
});

test('作者QA判定成功复用现成全屏绿动画，失败与无判定或拍照不冒充成功', () => {
  const fs = require('node:fs'), path = require('node:path');
  const host = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8').match(/<cy-play-verdict\b[^>]*>/)[0];
  const expression = host.match(/full-color="{{(.*?)}}"/);
  assert.ok(expression, 'challenge结果必须接入现成full-color，而非仅给complete演示屏');
  const fullColor = (pvVerdict, pvKit) => Function('pvVerdict', 'pvKit', 'return (' + expression[1] + ')')(pvVerdict, pvKit);
  const f = previewOwnerPage();
  f.submit('待发送');
  assert.equal(fullColor(f.p.data.pvVerdict, f.p.data.pvKit), true);
  f.close(); f.p.tryCurrentGame(); f.submit('错误');
  assert.equal(fullColor(f.p.data.pvVerdict, f.p.data.pvKit), false);
  for (const kit of [{ type: 'qa', mode: 'pick', judge: true }, { type: 'qa', mode: 'type', judge: true }]) {
    assert.equal(fullColor({ kind: 'win' }, kit), true);
    assert.equal(fullColor({ kind: 'fail' }, kit), false);
    assert.equal(fullColor({ kind: 'win' }, { ...kit, judge: false }), false);
  }
  for (const kit of [{ type: 'qa', mode: 'shot', judge: true }, { type: 'random' }, { type: 'profile' }]) {
    assert.equal(fullColor({ kind: 'win' }, kit), false);
  }
  assert.equal(f.requests.length, 0);
});

function currentQARecovery(mode, judgment) {
  const f = previewOwnerPage(), p = f.p;
  p.closePreview();
  p.data.advanced.qa.mode = mode;
  p.data.advanced.qa.options = [
    { id: 'actual-wrong', label: 'Wrong', correct: false },
    { id: 'actual-right', label: 'Right', correct: true }
  ];
  p.data.advanced.qa.imageUrl = '/profile/fixture-evidence.jpg';
  if (judgment !== undefined) p.data.advanced.qa.judge = judgment;
  p.data.advanced.outcomeRules.pass = { reply: 'Configured pass', images: ['/profile/fixture-pass.jpg'] };
  p.data.advanced.outcomeRules.fail = { reply: 'Configured fail', images: ['/profile/fixture-fail.jpg'] };
  const original = JSON.stringify(p.data.advanced);
  p.tryCurrentGame();
  const fs = require('node:fs'), path = require('node:path');
  const host = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8').match(/<cy-play-verdict\b[^>]*>/)[0];
  const expression = host.match(/full-color="{{(.*?)}}"/)[1];
  const fullColor = () => Function('pvVerdict', 'pvKit', 'return (' + expression + ')')(p.data.pvVerdict, p.data.pvKit);
  const submit = correct => p.onPvKitAction({ detail: { type: 'qa', action: mode === 'SHOT' ? 'shoot' : 'submit',
    detail: mode === 'PICK' ? { mode: 'pick', optionId: correct ? 'actual-right' : 'actual-wrong' } :
      { mode: mode.toLowerCase(), input: correct ? '待发送' : 'wrong' } } });
  return { ...f, original, fullColor, submit };
}

for (const mode of ['TYPE', 'PICK']) for (const judgment of [false, true, undefined]) {
  test('SUCCESS-R1 actual builder→Page ' + mode + ' judge=' + judgment + '保留原判定与配图，只有已判定成功变绿', () => {
    for (const correct of [true, false]) {
      const f = currentQARecovery(mode, judgment), p = f.p;
      const built = preview.buildPreviewKit(p.data.advanced);
      f.submit(correct);
      assert.equal(f.fullColor(), correct && judgment !== false, '无判定不进入全屏绿色答对动画');
      assert.equal(built.judge, judgment, '纯builder必须保留原judge字段');
      assert.equal(p.data.pvKit.judge, judgment, 'tryCurrentGame必须实际使用builder透传值');
      assert.equal(p.data.pvKit.imageUrl, '/profile/fixture-evidence.jpg');
      assert.equal(p.data.pvVerdict.kind, correct ? 'win' : 'fail', '本修复不改原本地判定规则');
      assert.equal(p.data.pvVerdict.show, true);
      assert.equal(f.fullColor(), correct && judgment !== false, '无判定不进入全屏绿色答对动画');
      assert.equal(p.data.pvVerdict.text, correct ? 'Configured pass' : 'Configured fail');
      assert.deepEqual(p.data.pvVerdict.images, [correct ? '/profile/fixture-pass.jpg' : '/profile/fixture-fail.jpg']);
      assert.equal(JSON.stringify(p.data.advanced), f.original, '作者原配置零写');
      assert.equal(f.requests.length, 0);
    }
  });
}

test('SUCCESS-R1 actual SHOT builder→Page保留显式题图与原收尾，不使用QA答对绿屏', () => {
  const f = currentQARecovery('SHOT', true);
  assert.equal(f.p.data.pvKit.mode, 'shot');
  assert.equal(f.p.data.pvKit.imageUrl, '/profile/fixture-evidence.jpg');
  f.submit(true);
  assert.equal(f.p.data.pvVerdict.kind, 'win');
  assert.equal(f.fullColor(), false);
  assert.equal(JSON.stringify(f.p.data.advanced), f.original);
  assert.equal(f.requests.length, 0);
});
