// 走查 v2 R04/R16(2026-10-30):主题工作台底部进度按 lifecycle 推进,子项只指向工作台已有入口。
const test = require('node:test');
const assert = require('node:assert/strict');
const { buildHostSteps } = require('../../utils/project-progress.js');

test('招募中:招商是当前步,子项写俱乐部与商家进度并指向招商/俱乐部弹窗', () => {
  const steps = buildHostSteps({ lifecycle: 1, productType: 1, nodeTotal: 4, nodeFilled: 2, pendingCount: 1, clubCount: 1, paidCount: 0 });
  assert.deepEqual(steps.map((s) => s.state), ['done', 'done', 'run', 'todo', 'todo', 'todo', 'todo']);
  const recruit = steps[2];
  assert.equal(recruit.status, '待邀约');
  assert.deepEqual(recruit.subs.map((s) => s.actKey), ['club', 'merchant']);
  assert.match(recruit.subs[1].text, /2\/4 站 · 1 个申请待处理/);
  assert.equal(steps[6].lock, '每月 5 日', '结算未到时显示锁定标签');
});

test('先招商后配票务:待定价阶段当前步是配置票务,入口是现有主题编辑器', () => {
  const steps = buildHostSteps({ lifecycle: 2, productType: 2, nodeTotal: 3, nodeFilled: 3 });
  const ticket = steps.find((s) => s.key === 'ticket');
  assert.equal(ticket.state, 'run');
  assert.equal(ticket.subs[0].act, 'goAddStations');
  assert.equal(steps[2].subs.length, 0, '已完成的步骤不再列子项');
  assert.match(buildHostSteps({ lifecycle: 1, productType: 2, nodeTotal: 3, nodeFilled: 1 })[2].subs[1].text, /1\/3 章/, '自由探索按章计');
});

test('未知 lifecycle 按草稿处理,已关闭全部完成', () => {
  assert.equal(buildHostSteps({ lifecycle: 99 })[1].state, 'run');
  assert.ok(buildHostSteps({ lifecycle: 6 }).every((s) => s.state === 'done'));
});
