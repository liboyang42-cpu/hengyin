'use strict';
// 答案文本共享向量(E-A1~E-A3,E-A7)。替代原先只给单测用、生产不调用的 playkit-qa matchesAnswer。
// 服务端权威规则见 AnswerNormalizer.java(AnswerNormalizerTest 有同一批向量)。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const NBSP = String.fromCharCode(0xA0);
const U3000 = String.fromCharCode(0x3000);
const ZW = String.fromCharCode(0x200B);
const BOM = String.fromCharCode(0xFEFF);
const at = require('../../utils/answer-text.js');

test('归一向量:全角、NBSP、U+3000、零宽、首尾标点', () => {
  assert.strictEqual(at.normalize('３号线ＡＢＣ'), '3号线abc');
  assert.ok(at.equivalent('故宫博物院', `故宫${NBSP}博物院`));
  assert.ok(at.equivalent('故宫博物院', `故宫${U3000}博物院`));
  assert.ok(at.equivalent('故宫博物院', `故宫${ZW}博物院${BOM}`));
  assert.ok(at.equivalent('故宫博物院', '「故宫博物院。」'));
  assert.ok(at.equivalent('圣·约翰', '圣·约翰！'));
  assert.ok(!at.equivalent('圣约翰', '圣·约翰'));
  assert.strictEqual(at.normalize('3.5'), '3.5');
});

test('只有标点或空白不会命中;繁简不自动互转', () => {
  assert.strictEqual(at.normalize('。。。'), '');
  assert.ok(!at.equivalent('。', '。'));
  assert.ok(!at.equivalent('答案', '  '));
  assert.ok(!at.equivalent('故宫博物院', '故宮博物院'));
});

test('备选分隔符含 、 , ， ; ；', () => {
  assert.deepStrictEqual(at.split('苹果，梨、桃,李；杏;桔'), ['苹果', '梨', '桃', '李', '杏', '桔']);
  assert.ok(at.matchesAnyOf('苹果，梨', '苹果'));
  assert.ok(at.matchesAnyOf('苹果，梨', '梨。'));
  assert.ok(!at.matchesAnyOf('苹果，梨', '桃'));
});

test('共享分隔符与服务端 AnswerNormalizer.SEPARATOR_REGEX 一致', () => {
  const java = fs.readFileSync(path.join(__dirname, '../../../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/AnswerNormalizer.java'), 'utf8');
  const m = java.match(/SEPARATOR_REGEX = "\[([^\]]+)\]"/);
  assert.ok(m, '找不到服务端分隔符常量');
  assert.strictEqual(m[1], at.SEPARATOR_CHARS);
});

test('playkit-qa 不再保留与服务端漂移的本地 matchesAnswer', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/play/components/playkit-qa/index.js'), 'utf8');
  assert.ok(!/matchesAnswer/.test(src.replace(/本地 matchesAnswer/g, '')));
});

test('作者预览判定:全角与分隔符按服务端口径', () => {
  const pv = require('../../pages/publish/temp/preview-verdict.js');
  const kit = { type: 'qa' };
  const adv = (answerText) => ({ qa: { answerText } });
  assert.strictEqual(pv.judge(adv('苹果，梨'), kit, 'submit', { mode: 'type', input: '苹果' }), 'win');
  assert.strictEqual(pv.judge(adv('3号线'), kit, 'submit', { mode: 'type', input: '３号线' }), 'win');
  assert.strictEqual(pv.judge(adv('3号线'), kit, 'submit', { mode: 'type', input: '4号线' }), 'fail');
});

test('有序多段答案接受玩家分隔写法，不拼接备选或交换顺序', () => {
  for (const input of ['C02、B04', 'C02,B04', 'C02，B04', 'C02;B04', 'C02；B04', 'C02 B04', 'Ｃ０２，Ｂ０４']) {
    assert.ok(at.matchesAnyOf('C02B04', input), input);
    assert.strictEqual(require('../../pages/publish/temp/preview-verdict.js').judge({ qa: { answerText: 'C02B04' } }, { type: 'qa' }, 'submit', { mode: 'type', input }), 'win');
  }
  for (const input of ['B04、C02', 'C02', 'C02、B05']) assert.ok(!at.matchesAnyOf('C02B04', input));
  assert.ok(!at.matchesAnyOf('苹果，梨', '苹果梨'));
});
