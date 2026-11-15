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
