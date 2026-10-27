'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function load() {
  let definition, member = '11';
  const requests = [], patches = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => member, sendRequest: r => requests.push(r) });
  global.Page = v => { definition = v; }; global.wx = {};
  const file = path.resolve(__dirname, '../../pages/play/index.js'); delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: { activityId: 9, topicId: 8, showFinish: true },
    setData(patch, callback) { patches.push(patch); Object.assign(this.data, patch); if (callback) callback(); } });
  return { page, requests, patches, account: value => { member = value; } };
}
for (const change of ['account', 'activity', 'closed', 'reopened', 'newread']) test('milestone rejects old ' + change, () => {
  const h = load(); h.page.loadMilestone();
  if (change === 'account') h.account('22');
  if (change === 'activity') h.page.data.activityId = 19;
  if (change === 'closed' || change === 'reopened') h.page._hideFinish();
  if (change === 'reopened') h.page.data.showFinish = true;
  if (change === 'newread') h.page.loadMilestone();
  const before = h.patches.length;
  h.requests[0].success({ code: 200, data: [{ topicId: 8 }] });
  assert.equal(h.patches.length, before);
});
test('current milestone still uses the real distinct completed topics', () => {
  const h = load(); h.page.loadMilestone();
  h.requests[0].success({ code: 200, data: [{ topicId: 8 }, { topicId: 8 }, { topicId: 9 }] });
  assert.ok(h.page.data.milestone);
  assert.notEqual(h.page.data.milestone, null);
});
// 2026-10-27 生产实测:主题规则是「主线 N 站」时 arrive 判了通关,但完成事实列表按路线局状态算(选做站没走完就不算),
// 通关卡对新玩家写「你的第一个主题正在路上」。服务端刚判过通关的这一个主题要算进里程碑,且不重复计。
test('milestone counts the topic the server just declared completed', () => {
  const h = load(); h.page.data.topicCompleted = true; h.page.loadMilestone();
  h.requests[0].success({ code: 200, data: [] });
  assert.equal(h.page.data.milestone.count, 1);
  const again = load(); again.page.data.topicCompleted = true; again.page.loadMilestone();
  again.requests[0].success({ code: 200, data: [{ topicId: 8 }, { topicId: 5 }] });
  assert.equal(again.page.data.milestone.count, 2);
  const plain = load(); plain.page.loadMilestone();
  plain.requests[0].success({ code: 200, data: [] });
  assert.equal(plain.page.data.milestone.count, 0, '没判通关(手动结束)不凭空加一');
});
test('finish card title and result use the topic when the server declared completion', () => {
  const wxml = require('node:fs').readFileSync(path.resolve(__dirname, '../../pages/play/index.wxml'), 'utf8');
  assert.match(wxml, /finsheet__title">\{\{allDone \|\| topicCompleted \? topicName : chapter\.name\}\}/);
  assert.match(wxml, /allDone \|\| topicCompleted \? '已通关' : '本章完成'/);
});
