'use strict';
// C:U-027:前端状态 key 有两套命名 —— 活动生命周期(camelCase,后端 ActivityLifecycle 算)
// 与订单读模型(snake_case,utils/order-status.js 算)。两套是不同领域的词表,
// 这里钉住:各自有唯一导出、导出与真实产出一致、两套不互相串味。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { ACTIVITY_STATE } = require('../../utils/activity-lifecycle-state.js');
const { ORDER_STATE_KEY } = require('../../utils/order-status.js');

const read = (p) => fs.readFileSync(path.resolve(__dirname, '../../', p), 'utf8');
const JAVA = '../chengyinhub-system/src/main/java/com/chengyinhub/business/util/ActivityLifecycle.java';

test('ACTIVITY_STATE 与后端 ActivityLifecycle.stateText 的 case 全集一致', () => {
  const java = read(JAVA);
  const body = java.slice(java.indexOf('public static String stateText'));
  const cases = new Set([...body.matchAll(/case "(\w+)":/g)].map((m) => m[1]));
  assert.ok(cases.size >= 7, '没解析出 stateText 的 case');
  assert.deepEqual(new Set(Object.values(ACTIVITY_STATE)), cases);
});

test('后端 state()/timeBucket() 返回的每个状态都在 ACTIVITY_STATE 里', () => {
  const java = read(JAVA);
  const values = new Set(Object.values(ACTIVITY_STATE));
  const returned = [...java.matchAll(/return "(\w+)";/g)].map((m) => m[1]);
  assert.ok(returned.length >= 7);
  for (const v of returned) assert.ok(values.has(v), `后端返回 ${v} 但前端 ACTIVITY_STATE 没有`);
});

test('ORDER_STATE_KEY 覆盖 order-status.js 产出的全部 key(含 payoutSummary 的退款态)', () => {
  const src = read('utils/order-status.js');
  const fnSrc = src.slice(src.indexOf('function payoutSummary'));
  const keys = new Set([...fnSrc.matchAll(/key: '(\w+)'/g)].map((m) => m[1]));
  assert.ok(keys.size >= 8);
  assert.deepEqual(new Set(Object.values(ORDER_STATE_KEY)), keys);
});

test('两套词表各守各的命名风格,不互相串味', () => {
  for (const v of Object.values(ACTIVITY_STATE)) assert.doesNotMatch(v, /_/, `活动状态 ${v} 不应是 snake_case`);
  for (const v of Object.values(ORDER_STATE_KEY)) assert.doesNotMatch(v, /[A-Z]/, `订单 key ${v} 不应是 camelCase`);
  assert.ok(Object.isFrozen(ACTIVITY_STATE) && Object.isFrozen(ORDER_STATE_KEY));
});

test('活动详情徽章颜色表用 ACTIVITY_STATE 取键,不再手写 camelCase 字面量', () => {
  const src = read('subpackageMember/components/cy/scene-play-activity-detail/index.js');
  const table = src.slice(src.indexOf('const STATE_VARIANT'), src.indexOf('Component({'));
  assert.doesNotMatch(table, /\bnotStarted:/);
  assert.match(table, /\[ACTIVITY_STATE\.NOT_STARTED\]/);
});
