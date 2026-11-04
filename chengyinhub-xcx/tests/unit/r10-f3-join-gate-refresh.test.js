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
