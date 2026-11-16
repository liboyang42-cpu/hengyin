'use strict';
// x-life-games:商家偏好题汇总只读展示。不满 3 份只显示总数;不出现玩家身份。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const { view } = require('../../pages/publish/utils/preference-tally-view.js');

test('under three answers only the total is shown', () => {
  const v = view({ total: 2, minResponses: 3, enoughResponses: false, results: [] });
  assert.strictEqual(v.total, 2);
  assert.strictEqual(v.enough, false);
  assert.deepStrictEqual(v.rows, []);
});

test('enough answers lists every result in author order with counts and percent', () => {
  const v = view({ total: 4, minResponses: 3, enoughResponses: true, results: [
    { resultCode: 'aroma', title: '香气派体验官', responses: 3, percent: 75 },
    { resultCode: 'texture', title: '口感派体验官', responses: 1, percent: 25 },
    { resultCode: 'look', title: '颜值派体验官', responses: 0, percent: 0 }] });
  assert.deepStrictEqual(v.rows.map((r) => [r.title, r.responses, r.percent]),
    [['香气派体验官', 3, 75], ['口感派体验官', 1, 25], ['颜值派体验官', 0, 0]]);
});

test('component is read-only and wired only for my own preference templates', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/components/preference-tally/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /<input|<textarea|bindinput|cy-btn/);
  const page = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');
  assert.match(page, /<cy-preference-tally wx:if="\{\{ isMyScope && tallyTemplateId && formData\.validationMethod === 6 \}\}"/);
  const js = fs.readFileSync(path.join(__dirname, '../../pages/publish/components/preference-tally/index.js'), 'utf8');
  assert.match(js, /url: '\/api\/template\/preference-tally'/);
});
