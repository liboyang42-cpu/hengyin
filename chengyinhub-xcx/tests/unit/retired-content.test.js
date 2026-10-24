const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { filterRetiredContent } = require('../../utils/retired-content.js');

const retired = [
  [1, '上海滨江 10KM 骑行'], [6, '成都宽窄巷子辣味食材寻味'],
  [7, '上海沉浸式戏剧'], [8, '西安大唐不夜城灯光秀'],
  [990052, '城市藏宝猎人'], [990053, '异常事务局：不存在的坐标'],
  [990054, '时间污染：被删除的今天'], [990055, '末日广播：最后一座中继站'],
  [990056, '野生邻居观察局'], [990062, '预制人生 · 第一站']
].map(([id, name]) => ({ id, name }));

test('十条旧样本从首页、搜索、收藏和俱乐部主题列表移除；源响应不改写', () => {
  const real = { id: 1000001, name: '本周真实活动' };
  const body = { code: 200, data: { total: 11, rows: retired.concat(real), code: 200 } };
  const before = JSON.stringify(body);
  for (const url of ['/api/topic/list', '/api/topic/like_list', '/api/club/topics']) {
    const result = filterRetiredContent(url + '?pageNum=1', body);
    assert.deepEqual(result.data.rows, [real]);
    assert.equal(result.data.total, 1);
  }
  assert.equal(JSON.stringify(body), before);
});

test('精确匹配 ID 和名称：同名新活动、同 ID 新内容及模板保留', () => {
  const rows = [{ id: 1234, name: retired[0].name }, { id: 1, name: '真实新活动' },
    ...[1, '1', true].map(isTemplate => ({ ...retired[0], isTemplate }))];
  const body = { code: 200, data: rows };
  assert.equal(filterRetiredContent('/api/topic/list', body), body);
  for (const url of ['/api/template/topic-template/list', '/api/template/list', '/api/topic/draft-info']) {
    const templateBody = { code: 200, data: retired };
    assert.equal(filterRetiredContent(url, templateBody), templateBody);
  }
});

test('只有旧样本的完整列表呈现真实空态；部分分页保留后续加载所需总数', () => {
  const full = filterRetiredContent('/api/topic/list', { code: '200', data: { total: 10, rows: retired } });
  assert.deepEqual(full.data.rows, []);
  assert.equal(full.data.total, 0);
  const page = filterRetiredContent('/api/topic/list', { code: 200, data: { total: 30, rows: retired } });
  assert.equal(page.data.total, 30);
});

test('旧分享链接得到已下架结果，错误和真实详情原样传递', () => {
  for (const row of retired) {
    assert.deepEqual(filterRetiredContent('/api/topic/info-to-user', { code: 200, data: row }),
      { code: 404, msg: '该测试活动已下架', data: null });
  }
  const failure = { code: 500, msg: '服务错误' };
  assert.equal(filterRetiredContent('/api/topic/info-to-user', failure), failure);
  const real = { code: 200, data: { id: 9000001, name: '真实活动' } };
  assert.equal(filterRetiredContent('/api/topic/info-to-user', real), real);
});

test('只移除关联旧主题的活动，保留独立活动和新主题活动', () => {
  const live = [{ id: 1, name: '独立活动' }, { id: 2, topicId: 1234 }];
  const body = { code: 200, data: { total: 3, rows: [{ id: 3, topicId: 990052 }, ...live] } };
  assert.deepEqual(filterRetiredContent('/api/activity/list', body).data.rows, live);
});

test('真实 App 请求入口应用过滤，并保留取消句柄、失败和完成回调', () => {
  const root = path.resolve(__dirname, '../..');
  let app;
  vm.runInNewContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), {
    App(config) { app = config; }, wx: {}, console,
    require(request) { return require(path.resolve(root, request)); }
  }, { filename: 'app.js' });
  let sent;
  const handle = { abort() {} };
  app.getRequestClient = () => ({ send(options, base) { sent = options; assert.equal(base, 'custom'); return handle; } });
  let result;
  const fail = () => {};
  const complete = () => {};
  const options = { url: '/api/topic/list', data: { pageNum: 1 }, fail, complete,
    success(body) { result = body; } };
  assert.equal(app.sendRequest(options, 'custom'), handle);
  assert.equal(sent.fail, fail);
  assert.equal(sent.complete, complete);
  sent.success({ code: 200, data: { total: 10, rows: retired } });
  assert.deepEqual(result.data.rows, []);
  assert.equal(options.success === sent.success, false);
});
