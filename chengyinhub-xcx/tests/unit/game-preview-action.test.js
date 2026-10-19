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

test('F0457/F0301 本地预览一律标为沙盒，D20 样例标静态加值来源，玩家端只显示服务端回执来源', () => {
  const { SANDBOX_LABEL, previewD20Result } = require('../../utils/game-preview-action.js');
  const fs = require('node:fs'), path = require('node:path');
  assert.match(SANDBOX_LABEL, /本地沙盒/); assert.match(SANDBOX_LABEL, /不代表正式判定/);
  const roll = applyPreviewAction({ type: 'diceroll', mode: 'd20', dc: 10, modifier: 1, rollMode: 'normal' }, { action: 'roll' });
  assert.equal(roll.sandbox, true); assert.equal(roll.sandboxLabel, SANDBOX_LABEL);
  assert.equal(roll.kit.result.sandbox, true); assert.equal(roll.kit.result.modifierSource, 'STATIC');
  assert.equal(previewD20Result({ rollMode: 'normal', modifier: 0, dc: 1 }).sandbox, true);
  const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');
  const live = pickPlayKit({ playKit: { diceRoll: { enabled: true, mode: 'd20', dc: 15, modifier: 2, rollMode: 'normal', rolled: true, pips: [12], kept: 12, total: 14, success: false, action: '回执', modifierSource: 'STATIC', sandbox: true } } });
  assert.equal(live.result.modifierSource, 'STATIC'); assert.equal(live.result.sandbox, undefined, '服务端结果不得显示为沙盒');
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/components/playkit-diceroll/index.wxml'), 'utf8');
  assert.match(wxml, /本地沙盒样例，不代表正式判定/); assert.match(wxml, /作者固定加值，不取玩家能力/);
  assert.match(fs.readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8'), /玩家视角预览 · 本地沙盒/);
});
