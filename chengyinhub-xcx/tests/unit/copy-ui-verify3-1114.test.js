'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const pops = require('../../pages/play/utils/play-stage-pops.js');
const read = file => fs.readFileSync(path.join(__dirname, '../..', file), 'utf8');

test('站点距离复用米／公里格式，零距离不虚构步行时间', () => {
  for (const [input, expected, walk] of [['0m', '0 米', ''], ['80m', '80 米', '1'], ['1.2km', '1.2 公里', '15'], ['', '', '']]) {
    assert.deepEqual(pops.sheetFacts({}, input), { distText: expected, walkText: walk, hoursText: '' });
  }
});

test('站点面板和路线标题共享尾部三字保护，原名不变', () => {
  const context = { module: { exports: {} }, getRegExp: RegExp };
  vm.runInNewContext(read('utils/wxs/station-title.wxs'), context);
  const title = '1 · 五份称呼是否一只猫';
  const display = context.module.exports.text(title);
  assert.ok(display.endsWith('一\u2060只\u2060猫'));
  assert.equal(display.replace(/\u2060/g, ''), title);
  assert.match(read('pages/topic/index/index.wxml'), /stationTitle\.text\(node\.name\)/);
});
