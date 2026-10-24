'use strict';

// CL4 / G4:「如何获取积分」弹窗的规则只来自 /api/points/result_list(后台积分规则),
// 花分规则(eventType=14)不再被客户端静默丢掉,而是单列;客户端不写任何分值或汇率。

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const { splitPointsRules } = require('../../subpackageMember/components/cy/profile/points-rules.js');
const ROOT = path.resolve(__dirname, '../..');

test('启用中的规则按赚/花分拆;数值、次数取自后台', () => {
  const rules = splitPointsRules([
    { id: 1, title: '完成主题', description: '通关一次', value: 20, eventType: 1, status: 1, pointsNum: 3 },
    { id: 2, title: '停用规则', value: 5, eventType: 2, status: 0, pointsNum: 0 },
    { id: 3, title: '解锁提示', description: '查看一次提示', value: '10.00', eventType: 14, status: 1, pointsNum: 2 },
    { id: 4, title: '', value: null, eventType: 5, status: '1' },
  ]);
  assert.deepEqual(rules.earn.map((r) => r.id), [1, 4]);
  assert.equal(rules.earn[0].points, 20);
  assert.equal(rules.earn[0].doneCount, 3);
  assert.equal(rules.earn[1].title, '积分规则');
  assert.equal(rules.earn[1].pointsText, '以平台规则为准', '后台没配数值不显示 +0');
  assert.deepEqual(rules.spend.map((r) => [r.id, r.points, r.doneCount]), [[3, 10, 2]]);
});

test('畸形回包返回 null(页面落失败可重试),不伪装成暂无任务', () => {
  assert.equal(splitPointsRules(null), null);
  assert.equal(splitPointsRules({}), null);
  assert.equal(splitPointsRules([null]), null);
  assert.deepEqual(splitPointsRules([]), { earn: [], spend: [] });
});

test('profile 积分弹窗走规则整理,不再写死分值或 eventType 过滤', () => {
  const js = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/profile/index.js'), 'utf8');
  const start = js.indexOf('loadPointsTasks: function');
  const body = js.slice(start, js.indexOf('onPrimaryCta', start));
  assert.match(body, /req\('\/api\/points\/result_list'/);
  assert.match(body, /splitPointsRules\(res\.data\)/);
  assert.doesNotMatch(body, /=== 14/);
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml'), 'utf8');
  assert.match(wxml, /pointsSheet\.spendRules/);
  assert.match(wxml, /积分消耗/);
});

test('pointValue:正数/小数/数字串保留,负数、空、非数字一律 null(页面显示「以平台规则为准」)', () => {
  const { pointValue } = require('../../subpackageMember/components/cy/profile/points-rules.js');
  assert.equal(pointValue(20), 20);
  assert.equal(pointValue('10.00'), 10);
  assert.equal(pointValue(0.125), 0.13);
  assert.equal(pointValue(0), 0);
  assert.equal(pointValue(-5), null);
  assert.equal(pointValue(null), null);
  assert.equal(pointValue(undefined), null);
  assert.equal(pointValue(''), null);
  assert.equal(pointValue('abc'), null);
});
