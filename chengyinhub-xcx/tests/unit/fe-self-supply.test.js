// F10(FE-G13/14)自由探索作者路径:自己承接权益卡、编辑器票价区状态、填价发布请求体。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
global.wx = global.wx || { showToast() {} };
const fe = require('../../utils/fe-author-sale.js');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const TICKETS = [{ id: 11, name: '示例门票', price: null }, { id: 12, name: '双人票', price: 59 }];

test('招募中:票价区置灰并写出还差几个位置', () => {
  const p = fe.pricingPanel({ productType: 2, lifecycle: 1 },
    { lifecycle: 1, totalSlots: 3, openSlots: 2, canFillPrice: false }, TICKETS);
  assert.equal(p.show, true);
  assert.equal(p.disabled, true);
  assert.equal(p.hint, '还有 2 个位置待承接');
  assert.deepEqual(p.rows.map(r => r.price), ['', '59']);
});

test('全部承接后(待定价)可填价发布;城市定向 / 已开售不出票价区', () => {
  const p = fe.pricingPanel({ productType: 2, lifecycle: 2 },
    { lifecycle: 2, totalSlots: 3, openSlots: 0, canFillPrice: true }, TICKETS);
  assert.equal(p.disabled, false);
  assert.equal(p.hint, '');
  assert.equal(fe.pricingPanel({ productType: 1, lifecycle: 2 }, null, TICKETS).show, false);
  assert.equal(fe.pricingPanel({ productType: 2, lifecycle: 3 }, null, TICKETS).show, false);
});

test('发布请求体:每张票都要合法票价,0 元允许', () => {
  assert.deepEqual(fe.publishBody(81, [{ id: 11, price: '0' }, { id: 12, price: '39.5' }]),
    { topicId: 81, ticketPrices: { 11: '0', 12: '39.5' } });
  assert.throws(() => fe.publishBody(81, [{ id: 11, price: '' }]), /每张票都要填写票价/);
  assert.throws(() => fe.publishBody(81, [{ id: 11, price: '-1' }]), /不能为负/);
  assert.throws(() => fe.publishBody(81, [{ id: 11, price: '1.234' }]), /两位小数/);
});

test('自己承接权益卡:名称、人次、有效期必填', () => {
  const body = fe.selfSupplyBody(81, 82, { name: ' 示例权益 ', redeemRule: '8 折，到店出示', capacity: '30', validEnd: '2026-12-31' });
  assert.deepEqual(body, { topicId: 81, chapterId: 82,
    perk: { name: '示例权益', redeemRule: '8 折，到店出示', validEnd: '2026-12-31', capacity: 30 } });
  assert.throws(() => fe.selfSupplyBody(81, 82, { name: '', capacity: 1, validEnd: '2026-12-31' }), /权益名称/);
  assert.throws(() => fe.selfSupplyBody(81, 82, { name: 'a', capacity: 0, validEnd: '2026-12-31' }), /人次/);
  assert.throws(() => fe.selfSupplyBody(81, 82, { name: 'a', capacity: 1, validEnd: '' }), /有效期/);
});

test('请求包装:调用对应接口,非 200 原话退回', async () => {
  const sent = [];
  const app = { sendRequest: (o) => { sent.push(o); o.success({ code: '200', data: { offerId: 92 } }); } };
  const data = await fe.selfSupply(app, 81, 82, { name: 'a', capacity: 1, validEnd: '2026-12-31' });
  assert.equal(data.offerId, 92);
  assert.equal(sent[0].url, '/api/topic/chapter/self-supply');
  assert.equal(sent[0].method, 'POST');
  const bad = { sendRequest: (o) => o.success({ code: '500', msg: '还有 1 个位置待承接' }) };
  await assert.rejects(fe.publishForSale(bad, 81, [{ id: 11, price: '1' }]), /还有 1 个位置待承接/);
  const r = { sendRequest: (o) => { sent.push(o); o.success({ code: '200', data: {} }); } };
  await fe.loadReadiness(r, 81);
  assert.equal(sent.at(-1).url, '/api/topic/fe/sale-readiness');
  assert.deepEqual(sent.at(-1).data, { topicId: 81 });
});

test('页面接线:提示删除、自己承接不再跳 merchantapply、锁价入口去编辑器', () => {
  const fabu = read('pages/publish/fabu/index.js');
  assert.doesNotMatch(fabu, /必须先邀 1 家俱乐部承办才能开售/);
  assert.match(read('pages/publish/fabu/index.wxml'), /bindtap="onFePublish"/);
  const info = read('pages/topic/merchantinfo/merchantinfo.js');
  assert.match(info, /this\._isFreeExploreHost && ds\.chapter && topicId/);
  assert.match(info, /feAuthorSale\.selfSupply\(/);
  assert.match(read('pages/topic/components/project-host/index.wxml'), /data-act="selfTakeNode"[^>]*data-chapter="\{\{ item\.chapterId \}\}"/);
  assert.match(read('pages/topic/index/index.js'), /Number\(this\.data\.info\.productType\) === 2\s*\? '\/pages\/publish\/fabu\/index\?id='/);
});
