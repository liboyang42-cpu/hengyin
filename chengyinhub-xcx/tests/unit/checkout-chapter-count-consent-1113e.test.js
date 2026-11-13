'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../..');
const { chapterCountText, isEndingChapter } = require('../../utils/chapter-count.js');
const motion = require('../../pages/topic/utils/topic-motion.js');
const { headPills, checkoutView } = require('../../pages/topic/utils/checkout-view.js');

const ch = (n) => ({ name: n });
const route = { productType: 1, totalChapterCount: 6, chaptersList: [ch('一'), ch('二'), ch('三'), ch('结局A'), ch('结局B'), ch('结局C')] };

test('确认订单:3 章 + 3 结局写「3 章 · 3 个结局」,不写 6 章', () => {
  assert.deepEqual(headPills(route).filter((p) => /章/.test(p)), ['3 章 · 3 个结局']);
  assert.ok(!checkoutView({ info: route, quoteState: 'ready', quote: { payAmount: 9 } }).pills.some((p) => p === '6 章'));
});

test('详情页和确认订单共用一份口径', () => {
  assert.equal(motion.chapterCountText, chapterCountText);
  assert.equal(motion.isEndingChapter, isEndingChapter);
  assert.equal(chapterCountText(route), '3 章 · 3 个结局');
});

test('确认订单:无结局、单章、未下发章名', () => {
  assert.deepEqual(headPills({ chaptersList: [ch('一'), ch('二')] }), ['2 章']);
  assert.deepEqual(headPills({ chaptersList: [ch('一')] }), []);
  assert.deepEqual(headPills({ productType: 2, totalChapterCount: 4 }), ['自由探索', '4 章']);
});

test('同意行在固定底栏内、支付行上方,不再靠底部留白', () => {
  const dir = path.join(root, 'pages/topic/components/cy/self-play-checkout');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  const wxss = fs.readFileSync(path.join(dir, 'index.wxss'), 'utf8');
  const bar = wxml.slice(wxml.indexOf('<view class="ck__bar">'));
  assert.ok(bar.indexOf('cy-consent-check') > 0);
  assert.ok(bar.indexOf('cy-consent-check') < bar.indexOf('ck__pay-row'));
  assert.ok(wxml.indexOf('cy-consent-check') > wxml.indexOf('<view class="ck__bar">'));
  assert.ok(!wxml.includes('ck__pad') && !wxss.includes('.ck__pad'));
  assert.match(wxml, /同意将姓名、手机号提供给主办方，用于报名核验/);
  assert.match(wxml, /bind:change="onConsent"/);
});
