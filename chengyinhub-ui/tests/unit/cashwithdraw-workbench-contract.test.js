const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/cashwithdraw/index.vue'), 'utf8');

function loadCashwithdraw(listCashwithdraw) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[^}]+\} from "@\/api\/business\/cashwithdraw"$/m, 'const { listCashwithdraw } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ listCashwithdraw });
}

test('提现人工回补页复用标准页头、筛选和数据表，不再保留旧式表单工具栏', () => {
  assert.ok(source.includes("import CyPageHeader from '@/components/CyPageHeader'"));
  assert.ok(source.includes("import CyFilterBar from '@/components/CyFilterBar'"));
  assert.ok(source.includes("import CyDataTable from '@/components/CyDataTable'"));
  assert.match(source, /<cy-page-header[\s\S]*?title="提现回补队列"[\s\S]*?:updated-at="updatedAt"/);
  assert.match(source, /<cy-filter-bar[\s\S]*?:active-filters="activeFilters"[\s\S]*?@submit="handleQuery"[\s\S]*?@remove-filter="clearFilter"/);
  assert.match(source, /<cy-data-table[\s\S]*?:columns="tableColumns"[\s\S]*?@pagination="handlePagination"/);
  assert.doesNotMatch(source, /<right-toolbar/);
  assert.doesNotMatch(source, /<el-table v-loading="loading" :data="list">/);
});

test('提现队列表格首列标识业务对象，固定操作列保留现有三个领域动作', () => {
  assert.match(source, /key: 'withdrawal'/);
  assert.match(source, /label: '提现单'/);
  assert.match(source, /key: 'actions'[\s\S]*?locked: true/);
  assert.match(source, /<template #withdrawal="\{ row \}">[\s\S]*?提现 #\{\{ row\.id \}\}/);
  assert.match(source, /handleRequery\(row\)/);
  assert.match(source, /handleManual\(row\)/);
});

test('提现筛选将编辑草稿与已应用查询分离，并只接收最新请求结果', () => {
  assert.match(source, /queryForm: createQueryParams\(\)/);
  assert.match(source, /queryParams: createQueryParams\(\)/);
  assert.match(source, /const requestId = \+\+this\.listRequestId/);
  assert.match(source, /if \(requestId !== this\.listRequestId\) return/);
});

test('提现筛选草稿不会冒充已查询结果，旧响应不会覆盖最新筛选', async () => {
  const pending = [];
  const component = loadCashwithdraw(params => new Promise(resolve => pending.push({ params, resolve })));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: () => {} },
    parseTime: () => '2026-07-22 02:00'
  });
  vm.statusText = component.methods.statusText;
  vm.getList = component.methods.getList.bind(vm);

  vm.queryForm.memberId = '1001';
  assert.deepEqual(component.computed.activeFilters.call(vm), []);

  const firstRequest = component.methods.handleQuery.call(vm);
  assert.deepEqual(pending[0].params, { pageNum: 1, pageSize: 10, memberId: '1001', status: null });
  assert.deepEqual(component.computed.activeFilters.call(vm), [{ key: 'memberId', label: '用户 ID', value: '1001' }]);

  vm.queryForm.memberId = '2002';
  assert.deepEqual(component.computed.activeFilters.call(vm), [{ key: 'memberId', label: '用户 ID', value: '1001' }]);
  const secondRequest = component.methods.handleQuery.call(vm);
  assert.deepEqual(pending[1].params, { pageNum: 1, pageSize: 10, memberId: '2002', status: null });

  pending[0].resolve({ rows: [{ id: 1 }], total: 1 });
  await firstRequest;
  assert.deepEqual(vm.list, []);
  assert.equal(vm.loading, true);

  pending[1].resolve({ rows: [{ id: 2 }], total: 1 });
  await secondRequest;
  assert.deepEqual(vm.list, [{ id: 2 }]);
  assert.equal(vm.loading, false);
});
