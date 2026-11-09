// 第十轮 f1(rig 2026-11-04 走查):参与详情深链壳的 recordId 观察器先于 attached 触发时,
// attached 不得把观察器已发出的同一 id 请求判成过期 —— 否则页面永远停在「参与详情加载中」。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

function load() {
  let definition; const requests = [];
  const app = { globalData: {}, sendRequest(o) { requests.push(o); }, getRequestErrorMessage(_r, f) { return f; },
    getUserID() { return 9009; }, getAuthorization() { return 'tok'; }, getUserRole() { return 'player'; } };
  global.getApp = () => app;
  global.wx = { getStorageSync() { return ''; }, showToast() {}, getAccountInfoSync() { return { miniProgram: { envVersion: 'release' } }; } };
  global.Component = (v) => { definition = v; }; global.Page = () => {}; global.Behavior = (v) => v;
  global.getCurrentPages = () => [];
  const file = path.join(__dirname, '../../subpackageMember/components/scene-member-participation-detail/index.js');
  delete require.cache[require.resolve(file)];
  require(file);
  const vm = Object.assign({}, definition, definition.methods);
  vm.data = JSON.parse(JSON.stringify(definition.data || {}));
  vm.setData = function (p) { Object.assign(this.data, p); };
  vm.triggerEvent = () => {};
  return { vm, definition, requests };
}

const INFO = { code: '200', data: { id: 75, ownerType: 2, ownerId: 9, paymentStatus: 2, registrationStatus: 2,
  cmsActivity: { id: 9, name: '梧桐里读书夜', startDate: '2026-11-05 06:40:00', endDate: '2026-11-05 08:40:00' } } };

test('观察器先触发、attached 后到:在途回包仍落地', () => {
  const { vm, definition, requests } = load();
  vm.data.recordId = '75';
  definition.observers.recordId.call(vm, '75');
  definition.lifetimes.attached.call(vm);
  assert.equal(requests.length, 1, '同一 id 只发一次');
  requests[0].success(INFO);
  assert.equal(vm.data.loadState, 'ready');
});

test('attached 先到照旧只发一次并落地', () => {
  const { vm, definition, requests } = load();
  vm.data.recordId = '75';
  definition.lifetimes.attached.call(vm);
  definition.observers.recordId.call(vm, '75');
  assert.equal(requests.length, 1);
  requests[0].success(INFO);
  assert.equal(vm.data.loadState, 'ready');
});
