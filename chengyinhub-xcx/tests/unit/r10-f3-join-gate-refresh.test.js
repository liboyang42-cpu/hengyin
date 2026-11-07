// 第十轮 f3 第 1 项 j6 F4:活动页「加入后查看」闸 —— 去俱乐部加入后返回(onShow),要重读活动,不能停在旧闸上。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');

const file = path.join(__dirname, '..', '..', 'subpackageMember/components/cy/scene-play-activity-detail/index.js');

test('闸态返回页面时重读活动;非闸态且身份未变不重读', () => {
  let def;
  const app = { globalData: { authorization: 't' }, getUserID: () => '9009', sendRequest() {} };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component: (d) => { def = d; }, getApp: () => app, getCurrentPages: () => [], wx: {}, require: createRequire(file),
    setTimeout, clearTimeout, console,
  });
  const loads = [];
  const scene = Object.assign({}, def.methods, {
    data: { activityId: '13', state: 'gate' },
    setData(p) { Object.assign(this.data, p); },
    load(id) { loads.push(id); },
  });
  scene._activityReadOwner = scene._activityReadIdentity();
  scene._loadedId = '13';
  def.pageLifetimes.show.call(scene);
  assert.deepEqual(loads, ['13']);
  assert.equal(scene._loadedId, '', '清掉去重标记,load 才会真的重发');
  scene.data.state = 'ready';
  def.pageLifetimes.show.call(scene);
  assert.deepEqual(loads, ['13']);
});

function activityScene() {
  let def;
  const requests = [];
  const app = { globalData: { authorization: 'same-session' }, getUserID: () => '990017',
    sendRequest(request) { requests.push(request); } };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Component: (d) => { def = d; }, getApp: () => app, getCurrentPages: () => [], wx: {}, require: createRequire(file),
    setTimeout, clearTimeout, console,
  });
  const scene = Object.assign({}, def.methods, {
    data: Object.assign(JSON.parse(JSON.stringify(def.data)), { activityId: '25', reducedMotion: true }),
    setData(p) { Object.assign(this.data, p); }, triggerEvent() {},
  });
  return { def, scene, requests, show: () => def.pageLifetimes.show.call(scene),
    hide: () => def.pageLifetimes.hide.call(scene) };
}

function activityReply(registrationCount, isSignUp, remainingInventory) {
  return { code: 200, data: { id: 25, name: '普通免费活动', registrationCount, isSignUp,
    startDate: '2099-01-01', endDate: '2099-01-02',
    omsTicketList: [{ id: 92, price: 0, remainingInventory }] } };
}

test('同账号报名返回保留详情时只重读一次并更新报名数、余票与本人报名状态', () => {
  const h = activityScene();
  h.def.observers.activityId.call(h.scene, '25');
  h.def.lifetimes.attached.call(h.scene);
  h.show();
  assert.equal(h.requests.length, 1, '首次 observer/attached/show 共用同一次读取');
  h.requests[0].success(activityReply(2, 0, 98));
  assert.equal(h.scene.data.registrationCountText, '2');
  assert.equal(h.scene.data.actionText, '立即报名');
  h.scene._navigating = true;
  h.hide(); h.show();
  assert.equal(h.requests.length, 2, '真正 hide→show 必须越过同身份 loadedId 缓存');
  assert.equal(h.scene._navigating, false);
  h.show(); h.def.observers.activityId.call(h.scene, '25');
  assert.equal(h.requests.length, 2, '重复 show/observer 不叠加刷新');
  h.requests[1].success(activityReply(3, 1, 97));
  assert.equal(h.scene.data.registrationCountText, '3');
  assert.equal(h.scene.data.info.isSignUp, 1);
  assert.equal(h.scene.data.tickets[0].remainingInventory, 97);
  assert.equal(h.scene.data.actionText, '查看报名');
  h.show();
  assert.equal(h.requests.length, 2, '没有再次 hide 不重复请求');
});

test('返回详情重读使上一轮在途响应失效，旧成功或失败均不能覆盖新报名结果', () => {
  const h = activityScene();
  h.def.lifetimes.attached.call(h.scene);
  const previous = h.requests[0];
  h.hide(); h.show();
  assert.equal(h.requests.length, 2, '返回必须同时清掉在途 loadingId 去重');
  h.requests[1].success(activityReply(3, 1, 97));
  previous.success(activityReply(2, 0, 98));
  previous.fail({ msg: '旧请求失败' });
  assert.equal(h.scene.data.state, 'ready');
  assert.equal(h.scene.data.registrationCountText, '3');
  assert.equal(h.scene.data.info.isSignUp, 1);
  assert.equal(h.scene.data.tickets[0].remainingInventory, 97);
  assert.equal(h.scene.data.actionText, '查看报名');
  assert.equal(h.requests.length, 2);
});
