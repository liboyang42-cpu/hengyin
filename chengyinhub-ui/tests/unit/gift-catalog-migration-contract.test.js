const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const oldPage = path.join(root, 'src/views/business/gift/index.vue');
const catalogPage = path.join(root, 'src/views/business/gift-catalog/index.vue');
const router = path.join(root, 'src/router/index.js');

function loadGiftCatalog(dependencies) {
  const source = fs.readFileSync(catalogPage, 'utf8');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ addGift, getGift, listGift, updateGift \} from .+$/m, 'const { addGift, getGift, listGift, updateGift } = dependencies;')
    .replace(/^import \{ getToken \} from .+$/m, 'const { getToken } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies);
}

test('旧众筹礼物入口只做兼容跳转，唯一页面宿主是配置域礼物目录', () => {
  const source = fs.readFileSync(oldPage, 'utf8');
  const catalog = fs.readFileSync(catalogPage, 'utf8');
  const routes = fs.readFileSync(router, 'utf8');

  assert.match(source, /path: '\/config\/gift'/);
  assert.match(source, /query: this\.\$route\.query/);
  assert.match(catalog, /title="礼物目录与定价"/);
  assert.match(routes, /path: '\/config\/gift'/);
  assert.match(routes, /permissions: \['business:gift:list'\]/);
});

test('礼物目录使用标准配置页组件，停用替代删除，调价必须带审计原因', () => {
  const source = fs.readFileSync(catalogPage, 'utf8');

  assert.match(source, /import CyPageHeader from '@\/components\/CyPageHeader'/);
  assert.match(source, /import CyFilterBar from '@\/components\/CyFilterBar'/);
  assert.match(source, /import CyDataTable from '@\/components\/CyDataTable'/);
  assert.match(source, /import CyConfirmAction from '@\/components\/CyConfirmAction'/);
  assert.match(source, /handleToggleStatus\(row\)/);
  assert.match(source, /auditReason: reason/);
  assert.doesNotMatch(source, /delGift\(/);
  assert.doesNotMatch(source, /business:gift:remove/);
});

test('礼物筛选和详情读取不接受旧回包，停用只提交状态和审计说明', async () => {
  const pending = [];
  const pendingDetails = [];
  const saved = [];
  const component = loadGiftCatalog({
    addGift: () => Promise.resolve(),
    getGift: id => new Promise(resolve => pendingDetails.push({ id, resolve })),
    listGift: params => new Promise(resolve => pending.push({ params, resolve })),
    updateGift: payload => {
      saved.push(payload);
      return Promise.resolve();
    },
    getToken: () => 'token'
  });
  const vm = Object.assign(component.data(), {
    $modal: { msgError: () => {}, msgSuccess: () => {} },
    parseTime: () => '2026-07-23 09:00'
  });
  vm.getList = component.methods.getList.bind(vm);
  vm.isAvailable = component.methods.isAvailable;
  vm.invalidateDetailRequest = component.methods.invalidateDetailRequest.bind(vm);
  vm.resetForm = component.methods.resetForm.bind(vm);

  vm.queryForm.giftName = '徽章';
  const first = component.methods.handleQuery.call(vm);
  vm.queryForm.giftName = '路线';
  const second = component.methods.handleQuery.call(vm);

  assert.deepEqual(pending[0].params, { pageNum: 1, pageSize: 10, giftName: '徽章', status: null });
  assert.deepEqual(pending[1].params, { pageNum: 1, pageSize: 10, giftName: '路线', status: null });
  pending[0].resolve({ rows: [{ id: 1 }], total: 1 });
  await first;
  assert.deepEqual(vm.giftList, []);
  pending[1].resolve({ rows: [{ id: 2 }], total: 1 });
  await second;
  assert.deepEqual(vm.giftList, [{ id: 2 }]);

  const editA = component.methods.handleEdit.call(vm, { id: 31 });
  const editB = component.methods.handleEdit.call(vm, { id: 32 });
  pendingDetails[1].resolve({ data: { id: 32, giftName: 'B', point: 20, status: 2, delFlag: 0 } });
  await editB;
  assert.equal(vm.form.id, 32);
  pendingDetails[0].resolve({ data: { id: 31, giftName: 'A', point: 10, status: 1, delFlag: 0 } });
  await editA;
  assert.equal(vm.form.id, 32);

  component.methods.handleToggleStatus.call(vm, { id: 9, giftName: '城市徽章', point: 30, status: 1, delFlag: 0 });
  assert.equal(vm.pendingAction, 'status');
  assert.equal(vm.form.status, 2);
  vm.getList = () => Promise.resolve();
  await component.methods.submitConfirmed.call(vm, '活动结束后停止赠送');
  assert.deepEqual(saved, [{ id: 9, status: 2, auditReason: '活动结束后停止赠送' }]);
});
