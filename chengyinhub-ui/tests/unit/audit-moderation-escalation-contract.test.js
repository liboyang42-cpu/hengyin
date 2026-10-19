const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// F0528 / F0529:决定已生效但对象投影未更新显示「待处置」;升级不是裁决,旧层级迟到提交被拒后读回当前版本。
function loadComponent(api) {
  const src = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/audit/index.vue'), 'utf8');
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1];
  const body = script
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ');
  const names = ['listAuditTasks', 'getAuditTaskDetail', 'reviewAuditTask', 'replayAuditModeration'];
  return new Function(...names, body)(...names.map(name => api[name]));
}

function vmOf(component) {
  const messages = [];
  const vm = { ...component.data(), ...component.methods };
  vm.$modal = {
    msgSuccess: text => messages.push(['success', text]),
    msgWarning: text => messages.push(['warning', text])
  };
  vm.messages = messages;
  return vm;
}

test('已裁决但对象投影 PENDING 显示待处置,而不是已通过/已拒绝', () => {
  const { methods } = loadComponent({});
  const decided = { id: 1, status: 2, params: { moderationOutcome: 'PENDING' } };
  assert.equal(methods.rowStatusLabel.call(methods, decided), '待处置');
  assert.equal(methods.rowStatusLabel.call(methods, { id: 2, status: 2, params: { moderationOutcome: 'APPLIED' } }), '已拒绝');
  assert.equal(methods.rowStatusLabel.call(methods, { id: 3, status: 0, params: { moderationOutcome: 'PENDING' } }), '待审核',
    '未裁决的任务不受投影影响');
});

test('升级只显示处理层级推进,不显示为裁决结果', () => {
  const { methods } = loadComponent({});
  assert.equal(methods.statusLabel(3), '已升级 · 待裁决');
  assert.equal(methods.isPendingDisposal({ status: 3, params: { moderationOutcome: 'PENDING' } }), false);
});

test('审核提交带上当前案件版本', () => {
  const vm = vmOf(loadComponent({}));
  vm.openReview({ id: 9, version: 4 }, 2);
  assert.equal(vm.reviewForm.expectedVersion, 4);
});

test('旧层级迟到提交被拒后关闭旧表单并读回当前版本', async () => {
  let listed = 0;
  let detailed = 0;
  const vm = vmOf(loadComponent({
    reviewAuditTask: () => Promise.reject(new Error('该审核单已升级，旧层级的提交已失效，请刷新后按当前层级处理')),
    listAuditTasks: () => { listed++; return Promise.resolve({ rows: [{ id: 9, status: 3, version: 5 }], total: 1 }); },
    getAuditTaskDetail: () => { detailed++; return Promise.resolve({ task: { id: 9, status: 3, version: 5 }, records: [] }); }
  }));
  vm.detailOpen = true;
  vm.detailRow = { id: 9 };
  vm.openReview({ id: 9, version: 4 }, 2);
  await vm.submitReview();
  assert.equal(vm.reviewOpen, false);
  assert.equal(listed, 1);
  assert.equal(detailed, 1);
});

test('普通校验失败保留表单(如缺少拒绝原因)', async () => {
  const vm = vmOf(loadComponent({ reviewAuditTask: () => Promise.reject(new Error('拒绝必须填写原因')) }));
  vm.openReview({ id: 9, version: 4 }, 2);
  await vm.submitReview();
  assert.equal(vm.reviewOpen, true);
});

test('重放处置调用后端并读回结果,不在前端假定已更新', async () => {
  let replayed = null;
  let detailed = 0;
  const vm = vmOf(loadComponent({
    replayAuditModeration: id => { replayed = id; return Promise.resolve({ data: { outcome: 'PENDING' } }); },
    listAuditTasks: () => Promise.resolve({ rows: [], total: 0 }),
    getAuditTaskDetail: () => { detailed++; return Promise.resolve({ task: { id: 7, status: 2 }, records: [] }); }
  }));
  vm.detailOpen = true;
  vm.detailTask = { id: 7, status: 2 };
  vm.detailRow = { id: 7 };
  await vm.replayDisposal();
  assert.equal(replayed, 7);
  assert.deepEqual(vm.messages[0], ['warning', '重放后对象仍未更新，保持待处置']);
  assert.equal(detailed, 1);
});
