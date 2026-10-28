const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/merchant/index.vue'), 'utf8');

function loadMerchant(listMerchant) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[^}]+\} from "@\/api\/business\/merchant"$/m, 'const { listMerchant } = dependencies;')
    .replace(/^import (\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ listMerchant });
}

test('商家准入页以标准页头、筛选和数据表承载列表工作台', () => {
  assert.ok(source.includes("import CyDataTable from '@/components/CyDataTable'"));
  assert.ok(source.includes("import CyFilterBar from '@/components/CyFilterBar'"));
  assert.ok(source.includes("import CyMediaPreview from '@/components/CyMediaPreview'"));
  assert.ok(source.includes("import CyPageHeader from '@/components/CyPageHeader'"));
  assert.match(source, /<cy-page-header[\s\S]*?title="商家准入"[\s\S]*?:updated-at="updatedAt"/);
  assert.match(source, /<cy-filter-bar[\s\S]*?:active-filters="activeFilters"[\s\S]*?@submit="handleQuery"[\s\S]*?@remove-filter="clearFilter"/);
  assert.match(source, /<cy-data-table[\s\S]*?:columns="tableColumns"[\s\S]*?:selectable="true"[\s\S]*?@selection-change="handleSelectionChange"/);
  assert.match(source, /<template #toolbar-actions>[\s\S]*?v-hasPermi="\['business:merchant:remove'\]"[\s\S]*?handleDelete/);
  assert.match(source, /key: 'actions'[\s\S]*?locked: true/);
  assert.match(source, /<cy-media-preview :sources="row\.logo \? \[row\.logo\] : \[\]" label="商家 LOGO"/);
  assert.doesNotMatch(source, /<right-toolbar/);
  assert.doesNotMatch(source, /<el-table v-loading="loading" :data="merchantList"/);
});

test('商家准入筛选将编辑草稿与已应用条件分离，并只接收最新列表结果', async () => {
  const pending = [];
  const component = loadMerchant(params => new Promise(resolve => pending.push({ params, resolve })));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: () => {} },
    parseTime: () => '2026-07-22 03:20'
  });
  vm.getList = component.methods.getList.bind(vm);

  vm.queryForm.memberId = '101';
  vm.queryForm.name = '旧城茶馆';
  assert.deepEqual(component.computed.activeFilters.call(vm), []);

  const firstRequest = component.methods.handleQuery.call(vm);
  assert.deepEqual(pending[0].params, { pageNum: 1, pageSize: 10, memberId: '101', name: '旧城茶馆', status: null });
  assert.deepEqual(component.computed.activeFilters.call(vm), [
    { key: 'memberId', label: '会员 ID', value: '101' },
    { key: 'name', label: '商家名称', value: '旧城茶馆' }
  ]);

  vm.queryForm.memberId = '202';
  const secondRequest = component.methods.handleQuery.call(vm);
  assert.deepEqual(pending[1].params, { pageNum: 1, pageSize: 10, memberId: '202', name: '旧城茶馆', status: null });

  pending[0].resolve({ rows: [{ id: 1 }], total: 1 });
  await firstRequest;
  assert.deepEqual(vm.merchantList, []);
  assert.equal(vm.loading, true);

  pending[1].resolve({ rows: [{ id: 2 }], total: 1 });
  await secondRequest;
  assert.deepEqual(vm.merchantList, [{ id: 2 }]);
  assert.equal(vm.loading, false);
});

test('首页「商家准入」卡片带 status=0 进入时列表真的只查待审，而不是全量', async () => {
  // mms_merchant 没有 check_status 列，「待审」的实体字段就是 status
  //（MmsMerchantMapper.xml: <if test="status != null"> and status = #{status}）。
  const requests = [];
  const component = loadMerchant(params => {
    requests.push(params);
    return Promise.resolve({ rows: [], total: 0 });
  });

  const makeVm = query => {
    const vm = Object.assign(component.data(), {
      $route: { query },
      $modal: { msgError: () => {} },
      dict: { type: { sys_merchant_status: [{ value: '0', label: '待审核' }] } },
      parseTime: () => '2026-07-26 12:00'
    });
    for (const name of ['getList', 'applyRouteStatus', 'statusLabel', 'clearFilter']) {
      vm[name] = component.methods[name].bind(vm);
    }
    return vm;
  };

  // 带 status=0（URL 里是字符串）：并入 queryParams 后首查即带 status: 0
  const vm = makeVm({ status: '0' });
  component.created.call(vm);
  await Promise.resolve();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].status, 0);
  assert.equal(vm.queryForm.status, 0);
  // 用户看得见「审核状态: 待审核」筛选条，且能移除后回到全量
  assert.deepEqual(component.computed.activeFilters.call(vm), [
    { key: 'status', label: '审核状态', value: '待审核' }
  ]);
  await vm.clearFilter('status');
  assert.equal(requests.length, 2);
  assert.equal(requests[1].status, null);

  // 不带 query：行为与从侧栏进入完全一致，不夹带 status
  const plainVm = makeVm({});
  component.created.call(plainVm);
  assert.equal(requests[2].status, null);

  // 非法值（伪造 URL）：静默忽略，不把 NaN 发给后端
  const junkVm = makeVm({ status: 'abc' });
  component.created.call(junkVm);
  assert.equal(requests[3].status, null);
});
