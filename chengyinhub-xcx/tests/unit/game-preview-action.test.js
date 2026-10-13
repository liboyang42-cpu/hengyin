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
