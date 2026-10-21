// BL-152：utils/roam-shop-visitors.js 的 sourceKey 单测补齐
// 依赖 #113（BL-70），须在其后合并；不改被测代码。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const {
  sourceKey, visitorSourceOf, shapeVisitorRows,
} = require('../../utils/roam-shop-visitors.js');

test('sourceKey: 同一 id 不同来源类型不冲突', () => {
  assert.notEqual(sourceKey(1, 7), sourceKey(2, 7));
  assert.equal(sourceKey(1, 7), '1:7');
  assert.equal(sourceKey(2, 7), '2:7');
});

test('sourceKey: 不同 id 拼接不产生歧义（1:12 与 11:2 不同）', () => {
  assert.notEqual(sourceKey(1, 12), sourceKey(11, 2));
});

test('sourceKey: 数字与字符串数字 id 得到同一键（回包 sourceId 可能是字符串）', () => {
  assert.equal(sourceKey(1, '7'), sourceKey(1, 7));
});

test('sourceKey: 缺 id 时退化为 "type:undefined"/"type:null"，不会与任何有效来源键相撞', () => {
  assert.equal(sourceKey(1, undefined), '1:undefined');
  assert.equal(sourceKey(2, null), '2:null');
  const table = shapeVisitorRows(1, [
    { sourceId: 7, total: 3, avatars: [] },
    { sourceId: null, total: 9, avatars: [] },
    { total: 9, avatars: [] },
  ]);
  assert.deepEqual(Object.keys(table), ['1:7']);
  assert.equal(table[sourceKey(1, undefined)], undefined);
  assert.equal(table[sourceKey(1, null)], undefined);
});

test('同商家不同来源：roam 据点与报名商家的回包按来源分表，互不覆盖', () => {
  const roam = shapeVisitorRows(1, [{ sourceId: 5, total: 2, avatars: ['https://a/1.png'] }]);
  const reg = shapeVisitorRows(2, [{ sourceId: 5, total: 8, avatars: [] }]);
  const merged = Object.assign({}, roam, reg);
  assert.equal(merged[sourceKey(1, 5)].total, 2);
  assert.equal(merged[sourceKey(2, 5)].total, 8);
});

test('与 visitorSourceOf 配合：页面行的键能命中回包；演示点无来源不产生键', () => {
  const poiRoam = { _roamId: '5', regId: 9 };
  const poiReg = { regId: 9 };
  const poiDemo = { name: '演示点' };
  const a = visitorSourceOf(poiRoam);
  const b = visitorSourceOf(poiReg);
  assert.deepEqual(a, { sourceType: 1, sourceId: 5 }, '同时有据点与报名商家时据点优先');
  assert.deepEqual(b, { sourceType: 2, sourceId: 9 });
  assert.equal(visitorSourceOf(poiDemo), null);
  const table = Object.assign(
    shapeVisitorRows(1, [{ sourceId: '5', total: 1, avatars: [] }]),
    shapeVisitorRows(2, [{ sourceId: 9, total: 4, avatars: [] }]),
  );
  assert.equal(table[sourceKey(a.sourceType, a.sourceId)].total, 1);
  assert.equal(table[sourceKey(b.sourceType, b.sourceId)].total, 4);
});
