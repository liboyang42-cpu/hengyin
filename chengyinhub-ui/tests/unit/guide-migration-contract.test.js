const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const oldPage = path.join(root, 'src/views/business/infomation/index.vue');
const guidePage = path.join(root, 'src/views/business/content/guide/index.vue');
const router = path.join(root, 'src/router/index.js');

function loadGuidePage(dependencies) {
  const source = fs.readFileSync(guidePage, 'utf8');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ addInfomation, getInfomation, listInfomation, updateInfomation \} from .+$/m, 'const { addInfomation, getInfomation, listInfomation, updateInfomation } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies);
}

test('旧玩法说明入口只做兼容跳转，唯一页面宿主是内容配置页', () => {
  const legacy = fs.readFileSync(oldPage, 'utf8');
  const canonical = fs.readFileSync(guidePage, 'utf8');
  const routes = fs.readFileSync(router, 'utf8');

  assert.match(legacy, /path: '\/config\/content\/guide'/);
  assert.match(legacy, /query: this\.\$route\.query/);
  assert.match(canonical, /title="玩法说明"/);
  assert.match(routes, /path: '\/config\/content\/guide'/);
  assert.match(routes, /permissions: \['business:infomation:list'\]/);
});

test('玩法说明使用标准配置页组件，提供新增和编辑但不再提供物理删除入口', () => {
  const source = fs.readFileSync(guidePage, 'utf8');

  assert.match(source, /import CyPageHeader from '@\/components\/CyPageHeader'/);
  assert.match(source, /import CyFilterBar from '@\/components\/CyFilterBar'/);
  assert.match(source, /import CyDataTable from '@\/components\/CyDataTable'/);
  assert.match(source, /import CyConfirmAction from '@\/components\/CyConfirmAction'/);
  assert.match(source, /addInfomation/);
  assert.match(source, /updateInfomation/);
  assert.match(source, /auditReason: reason/);
  assert.doesNotMatch(source, /delInfomation\(/);
  assert.doesNotMatch(source, /business:infomation:remove/);
});

test('玩法说明列表和详情读取忽略过期回包，保存保留变更说明', async () => {
  const pendingLists = [];
  const pendingDetails = [];
  const saved = [];
  const component = loadGuidePage({
    addInfomation: payload => {
      saved.push(payload);
      return Promise.resolve();
    },
    getInfomation: id => new Promise(resolve => pendingDetails.push({ id, resolve })),
    listInfomation: params => new Promise(resolve => pendingLists.push({ params, resolve })),
    updateInfomation: payload => {
      saved.push(payload);
      return Promise.resolve();
    }
  });
  const vm = Object.assign(component.data(), {
    $modal: { msgError: () => {}, msgSuccess: () => {} },
    parseTime: () => '2026-07-23 10:00'
  });
  vm.getList = component.methods.getList.bind(vm);
  vm.invalidateDetailRequest = component.methods.invalidateDetailRequest.bind(vm);
  vm.resetForm = component.methods.resetForm.bind(vm);

  vm.queryForm.title = '城市漫游';
  const first = component.methods.handleQuery.call(vm);
  vm.queryForm.title = '定向探索';
  const second = component.methods.handleQuery.call(vm);

  assert.deepEqual(pendingLists[0].params, { pageNum: 1, pageSize: 10, title: '城市漫游', subtitle: null, sortId: null });
  assert.deepEqual(pendingLists[1].params, { pageNum: 1, pageSize: 10, title: '定向探索', subtitle: null, sortId: null });
  pendingLists[0].resolve({ rows: [{ id: 1 }], total: 1 });
  await first;
  assert.deepEqual(vm.guideList, []);
  pendingLists[1].resolve({ rows: [{ id: 2 }], total: 1 });
  await second;
  assert.deepEqual(vm.guideList, [{ id: 2 }]);

  const editA = component.methods.handleEdit.call(vm, { id: 21 });
  const editB = component.methods.handleEdit.call(vm, { id: 22 });
  pendingDetails[1].resolve({ data: { id: 22, title: 'B', subtitle: '', sortId: 2, contents: '' } });
  await editB;
  assert.equal(vm.form.id, 22);
  pendingDetails[0].resolve({ data: { id: 21, title: 'A', subtitle: '', sortId: 1, contents: '' } });
  await editA;
  assert.equal(vm.form.id, 22);

  vm.getList = () => Promise.resolve();
  vm.form = { id: 22, title: 'B', subtitle: '', sortId: 2, contents: '<p>正文</p>' };
  vm.pendingAction = 'save';
  await component.methods.submitConfirmed.call(vm, '更新小程序玩法说明');
  assert.deepEqual(saved, [{ id: 22, title: 'B', subtitle: '', sortId: 2, contents: '<p>正文</p>', auditReason: '更新小程序玩法说明' }]);
});
