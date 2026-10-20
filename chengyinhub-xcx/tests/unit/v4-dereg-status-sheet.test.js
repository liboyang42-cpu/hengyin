'use strict';
// SC-DEREG-01:设置页注销半屏(scene settings-deregister,shezhi 的 wx:if 宿主)与 pages/deregister 路由页
// 共用 cy-scene-settings-deregister。打开→关闭→再打开、状态读取失败、重试、未知/缺字段状态:
// 错误态绝不显示为可注销;关闭/销毁/隐藏后的迟到回包不得覆盖更新的状态。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const COMPONENT = '../../components/cy/scene-settings-deregister/index.js';
const FLOW = '../../utils/deregister-flow.js';
let def;
let requests;

global.getApp = () => ({
  globalData: { authorization: 'fixture-token' },
  getUserID: () => 7,
  recordConsent: () => Promise.resolve(),
  sendRequest: (request) => { requests.push(request); },
});
global.wx = { showToast() {}, navigateTo() {}, showModal() {} };
global.Component = (config) => { def = config; };

beforeEach(() => {
  requests = [];
  delete require.cache[require.resolve(COMPONENT)];
  delete require.cache[require.resolve(FLOW)];
  require(COMPONENT);
});

// 模拟一次组件实例化(shezhi 每次 openScene 都会经 wx:if 重新挂一份)
function mount() {
  const inst = Object.assign({}, def.methods, { data: JSON.parse(JSON.stringify(def.data)) });
  inst.setData = (patch, cb) => { Object.assign(inst.data, patch); if (cb) cb(); };
  def.lifetimes.attached.call(inst);
  return inst;
}
const statusReq = () => requests.filter(r => r.url === '/api/user/deregister/status').at(-1);
const precheckReq = () => requests.filter(r => r.url === '/api/user/deregister/precheck').at(-1);
// 「显示为可注销」= 正文落在 ELIGIBLE 卡片,或者申请按钮可点
const deletable = (inst) => inst.data.status === 'ELIGIBLE' || inst.data.canApply === true;

test('SC-DEREG-01 状态读取失败(网络/HTTP 异常/业务失败)一律 ERROR,不显示为可注销,重试可恢复', () => {
  const inst = mount();
  assert.equal(inst.data.status, 'LOADING');
  statusReq().fail({ errMsg: 'request:fail timeout' });
  assert.equal(inst.data.status, 'ERROR'); assert.equal(deletable(inst), false);
  assert.ok(inst.data.statusError);

  inst.loadStatus(); // cy-error 的「重试」
  statusReq().successStatusAbnormal({ code: 503 }, 503);
  assert.equal(inst.data.status, 'ERROR'); assert.equal(deletable(inst), false);

  inst.loadStatus();
  statusReq().success({ code: 500, msg: '服务繁忙' });
  assert.equal(inst.data.status, 'ERROR'); assert.equal(deletable(inst), false);

  inst.loadStatus();
  statusReq().success({ code: 200, data: { status: 'NORMAL' } });
  assert.equal(inst.data.status, 'LOADING', '状态正常后还要过资格预检,未预检前不可注销');
  assert.equal(deletable(inst), false);
  precheckReq().fail({ errMsg: 'request:fail' });
  assert.equal(inst.data.status, 'ERROR'); assert.equal(deletable(inst), false);

  inst.loadStatus();
  statusReq().success({ code: 200, data: { status: 'NORMAL' } });
  precheckReq().success({ code: 200, data: { status: 'ELIGIBLE', blockers: [], verificationMethod: 'SMS' } });
  assert.equal(inst.data.status, 'ELIGIBLE');
  assert.equal(inst.data.canApply, false, '资格通过后仍需勾选+验证码才可提交');
});

test('SC-DEREG-01 未知或缺失的状态字段 fail-closed 为 ERROR,不落到可注销', () => {
  const statusBodies = [
    { code: 200 }, { code: 200, data: null }, { code: 200, data: {} }, { code: 200, data: [] },
    { code: 200, data: { status: 7 } }, { code: 200, data: { status: 'ELIGIBLE' } }, { code: 200, data: { status: 'MYSTERY' } },
  ];
  for (const body of statusBodies) {
    const inst = mount();
    statusReq().success(body);
    assert.equal(inst.data.status, 'ERROR', JSON.stringify(body));
    assert.equal(deletable(inst), false, JSON.stringify(body));
  }
  const precheckBodies = [
    { code: 200, data: {} }, { code: 200, data: { status: 'ELIGIBLE' } },
    { code: 200, data: { status: 'ELIGIBLE', blockers: ['未结清订单'] } }, { code: 200, data: { status: 'BLOCKED', blockers: [] } },
    { code: 200, data: { status: 'OK', blockers: [] } }, { code: 200, data: { status: 'ELIGIBLE', blockers: [3] } },
  ];
  for (const body of precheckBodies) {
    const inst = mount();
    statusReq().success({ code: 200, data: { status: 'NORMAL' } });
    precheckReq().success(body);
    assert.equal(inst.data.status, 'ERROR', JSON.stringify(body));
    assert.equal(deletable(inst), false, JSON.stringify(body));
  }
  // 未知校验方式:资格本身成立,但验证方式被收成 UNAVAILABLE,提交被拦在本地,不发同意/注销请求
  const inst = mount();
  statusReq().success({ code: 200, data: { status: 'NORMAL' } });
  precheckReq().success({ code: 200, data: { status: 'ELIGIBLE', blockers: [], verificationMethod: 'FACE_ID' } });
  assert.equal(inst.data.verificationMethod, 'UNAVAILABLE');
  inst.data.confirmed = true; inst.refreshApplyState();
  assert.equal(inst.data.canApply, false);
  const before = requests.length;
  inst.apply();
  assert.equal(requests.length, before);
  assert.match(inst.data.applyError, /身份验证暂不可用/);
});

test('SC-DEREG-01 关闭半屏(组件销毁)后迟到回包不改旧实例;再打开的新实例只认自己的回包', () => {
  const first = mount();
  const firstStatus = statusReq();
  // shezhi.closeScene → wx:if 移除宿主 → detached
  def.lifetimes.detached.call(first);
  const frozen = JSON.parse(JSON.stringify(first.data));
  firstStatus.success({ code: 200, data: { status: 'NORMAL' } });
  assert.equal(requests.filter(r => r.url === '/api/user/deregister/precheck').length, 0, '销毁后不得续发预检');
  assert.deepEqual(first.data, frozen);

  // 再打开:新实例,读取失败 → ERROR
  const second = mount();
  const secondStatus = statusReq();
  assert.notEqual(secondStatus, firstStatus);
  secondStatus.fail({ errMsg: 'request:fail' });
  assert.equal(second.data.status, 'ERROR');
  // 旧实例的晚到失败/成功都不得影响新实例
  firstStatus.fail({ errMsg: 'late' });
  firstStatus.successStatusAbnormal({ code: 503 }, 503);
  assert.equal(second.data.status, 'ERROR');
  assert.deepEqual(first.data, frozen);

  // 新实例重试后进入 PENDING;第一轮迟到的 ELIGIBLE 预检也不能把它覆盖成可注销
  second.loadStatus();
  const retryStatus = statusReq();
  second.loadStatus(); // 连点重试:只认最后一次
  const latest = statusReq();
  latest.success({ code: 200, data: { status: 'PENDING', executeAfter: '' } });
  assert.equal(second.data.status, 'PENDING');
  retryStatus.success({ code: 200, data: { status: 'NORMAL' } });
  assert.equal(second.data.status, 'PENDING');
  assert.equal(requests.filter(r => r.url === '/api/user/deregister/precheck').length, 0);
});

test('SC-DEREG-01 宿主页隐藏时在途回包作废,回到前台重新读取', () => {
  const inst = mount();
  const firstStatus = statusReq();
  def.pageLifetimes.hide.call(inst);
  firstStatus.success({ code: 200, data: { status: 'NORMAL' } });
  assert.equal(requests.filter(r => r.url === '/api/user/deregister/precheck').length, 0);
  def.pageLifetimes.show.call(inst);
  const reread = statusReq();
  assert.notEqual(reread, firstStatus);
  assert.equal(inst.data.status, 'LOADING');
  reread.success({ code: 200, data: { status: 'NORMAL' } });
  const precheck = precheckReq();
  def.pageLifetimes.hide.call(inst);
  precheck.success({ code: 200, data: { status: 'ELIGIBLE', blockers: [] } });
  assert.equal(deletable(inst), false, '隐藏期间的迟到 ELIGIBLE 不得落地');
});

test('SC-DEREG-01 两个入口共用同一注销 scene:设置页走 scene,路由页只做宿主', () => {
  const registry = fs.readFileSync(path.join(ROOT, 'utils/scene-registry.js'), 'utf8');
  assert.match(registry, /'settings-deregister':\s*\{\s*route:\s*'\/pages\/deregister\/index'/);
  const shezhiWxml = fs.readFileSync(path.join(ROOT, 'pages/shezhi/shezhi.wxml'), 'utf8');
  assert.match(shezhiWxml, /wx:if="\{\{sceneCurrent && sceneCurrent\.id !== 'settings-identity-picker'\}\}"/);
  const pageWxml = fs.readFileSync(path.join(ROOT, 'pages/deregister/index.wxml'), 'utf8');
  assert.match(pageWxml, /<cy-scene-settings-deregister/);
  const wxml = fs.readFileSync(path.join(ROOT, 'components/cy/scene-settings-deregister/index.wxml'), 'utf8');
  // ERROR 与无法识别的状态各自落在错误卡,不会进入 ELIGIBLE 卡
  assert.match(wxml, /wx:elif="\{\{status === 'ERROR'\}\}"[^>]*bind:retry="loadStatus"/);
  assert.match(wxml, /<cy-error wx:else[^>]*bind:retry="loadStatus"/);
});
