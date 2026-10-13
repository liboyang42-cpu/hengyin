const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/ailog/index.vue'), 'utf8');

function loadAiLog(listAiLog, getAiLogSnapshot = () => Promise.resolve({ data: null })) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[^}]+\} from "@\/api\/business\/ailog"$/m, 'const { listAiLog, getAiLogSnapshot } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ listAiLog, getAiLogSnapshot });
}

test('AI 调用审计页以标准页头、渐进筛选和数据表承载定位任务', () => {
  assert.ok(source.includes("import CyDataTable from '@/components/CyDataTable'"));
  assert.ok(source.includes("import CyFilterBar from '@/components/CyFilterBar'"));
  assert.ok(source.includes("import CyPageHeader from '@/components/CyPageHeader'"));
  assert.ok(source.includes("import CyStatusTag from '@/components/CyStatusTag'"));
  assert.match(source, /<cy-page-header[\s\S]*?title="AI 调用审计"[\s\S]*?:updated-at="updatedAt"/);
  assert.match(source, /<cy-filter-bar[\s\S]*?:active-filters="activeFilters"[\s\S]*?@submit="handleQuery"[\s\S]*?@remove-filter="clearFilter"/);
  assert.match(source, /<template #more>[\s\S]*?安全[\s\S]*?调用时间/);
  assert.match(source, /<cy-data-table[\s\S]*?:columns="tableColumns"[\s\S]*?@pagination="handlePagination"/);
  assert.match(source, /key: 'actions'[\s\S]*?locked: true/);
  assert.match(source, /<el-button[\s\S]*?v-hasPermi="\['business:ailog:query'\]"[\s\S]*?@click="handleRowClick\(row\)"/);
  assert.match(source, /v-else-if="snapshotError"[^>]*role="alert"/);
  assert.match(source, /ailog-detail__error \{ color: var\(--cy-color-status-danger\); \}/);
  assert.doesNotMatch(source, /<el-table v-loading="loading" :data="list"/);
});

test('AI 审计筛选将编辑草稿与已应用条件分离，并复制日期范围发送请求', async () => {
  const pending = [];
  const component = loadAiLog(params => new Promise(resolve => pending.push({ params, resolve })));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: () => {} },
    parseTime: () => '2026-07-22 03:00'
  });
  vm.getList = component.methods.getList.bind(vm);

  vm.queryForm.traceId = 'trace-a';
  vm.dateRange = ['2026-07-20', '2026-07-21'];
  assert.deepEqual(component.computed.activeFilters.call(vm), []);

  const request = component.methods.handleQuery.call(vm);
  assert.deepEqual(pending[0].params, {
    pageNum: 1,
    pageSize: 10,
    traceId: 'trace-a',
    memberId: null,
    bizType: null,
    safetyFlag: null,
    params: { beginTime: '2026-07-20', endTime: '2026-07-21' }
  });
  assert.deepEqual(component.computed.activeFilters.call(vm), [
    { key: 'traceId', label: 'traceId', value: 'trace-a' },
    { key: 'dateRange', label: '调用时间', value: '2026-07-20 至 2026-07-21' }
  ]);

  vm.dateRange = ['2026-07-22', '2026-07-23'];
  assert.deepEqual(pending[0].params.params, { beginTime: '2026-07-20', endTime: '2026-07-21' });
  pending[0].resolve({ rows: [{ id: 1 }], total: 1 });
  await request;
  assert.deepEqual(vm.list, [{ id: 1 }]);
  assert.equal(vm.loading, false);
});

test('AI 审计列表只接收最新请求的结果', async () => {
  const pending = [];
  const component = loadAiLog(params => new Promise(resolve => pending.push({ params, resolve })));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: () => {} },
    parseTime: () => '2026-07-22 03:00'
  });
  vm.getList = component.methods.getList.bind(vm);

  vm.queryForm.memberId = '1001';
  const firstRequest = component.methods.handleQuery.call(vm);
  vm.queryForm.memberId = '2002';
  const secondRequest = component.methods.handleQuery.call(vm);

  pending[0].resolve({ rows: [{ id: 1 }], total: 1 });
  await firstRequest;
  assert.deepEqual(vm.list, []);
  assert.equal(vm.loading, true);

  pending[1].resolve({ rows: [{ id: 2 }], total: 1 });
  await secondRequest;
  assert.deepEqual(vm.list, [{ id: 2 }]);
  assert.equal(vm.loading, false);
});

test('AI 审计详情在切换到无快照行时，不保留前一行的加载态或结果', async () => {
  const snapshots = [];
  const component = loadAiLog(
    () => Promise.resolve({ rows: [], total: 0 }),
    () => new Promise(resolve => snapshots.push(resolve))
  );
  const vm = component.data();

  component.methods.handleRowClick.call(vm, { id: 1, traceId: 'trace-a' });
  assert.equal(vm.snapshotLoading, true);
  component.methods.handleRowClick.call(vm, { id: 2, traceId: null });
  assert.equal(vm.snapshotLoading, false);
  assert.equal(vm.snapshot, null);

  snapshots[0]({ data: { idea: '旧快照' } });
  await Promise.resolve();
  assert.equal(vm.snapshot, null);
  assert.equal(vm.snapshotLoading, false);
});
