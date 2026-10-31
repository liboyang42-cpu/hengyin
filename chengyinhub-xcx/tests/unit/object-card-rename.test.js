// BL-79 藏品卡改名:POST /api/object-card/rename(id,title),后端报错原文回显,成功以返回卡名为准并回读列表。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');

function loadPage() {
  let page;
  const requests = [];
  const toasts = [];
  vm.runInNewContext(fs.readFileSync(path.join(root, 'subpackageP3/pages/object-cards/index/index.js'), 'utf8'), {
    getApp: () => ({
      getUserID: () => 7,
      globalData: { authorization: 'Bearer test' },
      sendRequest: r => { requests.push(r); },
      getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    }),
    Page: p => { page = p; },
    require: id => {
      if (id.endsWith('/object-card.js')) return { toCard: row => row };
      if (id.endsWith('/toast.js')) return (msg) => toasts.push(msg);
      // master 的卡册页按当前登录身份读写(utils/session/login-reload.js),夹具给一个已登录账号
      if (id.endsWith('/login-reload.js')) return require(path.join(root, 'utils/session/login-reload.js'));
      return {};
    },
  });
  page.data = JSON.parse(JSON.stringify(page.data));
  page.setData = patch => { Object.assign(page.data, patch); };
  page._images = {};
  page._bodies = [];
  return { page, requests, toasts };
}

test('点名称行进改名,带出当前卡名;取消回到放大态', () => {
  const { page } = loadPage();
  page.onRenameStart();
  assert.equal(page.data.renaming, false, '没有放大的卡不进改名');
  page.data.active = { id: 7, title: '旧杯子' };
  page.onRenameStart();
  assert.equal(page.data.renaming, true);
  assert.equal(page.data.renameValue, '旧杯子');
  page.onRenameCancel();
  assert.equal(page.data.renaming, false);
  assert.deepEqual(page.data.active, { id: 7, title: '旧杯子' });
});

test('提交走 rename(id,title);后端报错原文回显,不关半屏', () => {
  const { page, requests } = loadPage();
  page.data.active = { id: 7, title: '旧杯子' };
  page.onRenameStart();
  page.onRenameInput({ detail: { value: '' } });
  page.onRenameSubmit();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/object-card/rename');
  assert.equal(requests[0].method, 'POST');
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0].data)), { id: 7, title: '' });
  page.onRenameSubmit();
  assert.equal(requests.length, 1, '提交中不重复发');
  requests[0].success({ code: 500, msg: '给这张卡起个名字' });
  assert.equal(page.data.renaming, true);
  assert.equal(page.data.renameSubmitting, false);
  assert.equal(page.data.renameError, '给这张卡起个名字');
  page.onRenameSubmit();
  requests[1].fail();
  assert.equal(page.data.renameError, '网络异常，请重试');
});

test('成功:以返回卡名为准更新放大态,提示并回读当前分类', () => {
  const { page, requests, toasts } = loadPage();
  page.data.active = { id: 7, title: '旧杯子', place: '某地' };
  page.data.filter = '日用杂物';
  page.onRenameStart();
  page.onRenameInput({ detail: { value: '  新杯子 ' } });
  page.onRenameSubmit();
  requests[0].success({ code: 200, data: { id: 7, title: '新杯子' } });
  assert.equal(page.data.renaming, false);
  assert.equal(page.data.active.title, '新杯子');
  assert.equal(page.data.active.place, '某地');
  assert.deepEqual(toasts, ['已改名']);
  assert.equal(requests[1].url, '/api/object-card/list');
  assert.equal(requests[1].data.category, '日用杂物');
});

test('页面接线:名称行可点、改名半屏用 cy-sheet + cy-field;回调不用 this 方法简写', () => {
  const wxml = fs.readFileSync(path.join(root, 'subpackageP3/pages/object-cards/index/index.wxml'), 'utf8');
  const js = fs.readFileSync(path.join(root, 'subpackageP3/pages/object-cards/index/index.js'), 'utf8');
  // 2026-10-30 业主定稿 P-3:藏品册成了「收集册 · 藏品」,标题由收集册的段头给
  assert.match(wxml, /<album-head active="object">/, '收集册段头');
  assert.match(wxml, /bindtap="onRenameStart" aria-role="button"/);
  assert.match(wxml, /<cy-sheet show="\{\{renaming\}\}" title="改名"/);
  assert.match(wxml, /<cy-field label="卡名"/);
  const submit = js.match(/onRenameSubmit\(\)\s*\{[\s\S]*?\n {2}\},/)[0];
  assert.doesNotMatch(submit, /success\s*\(res\)\s*\{|fail\s*\(\)\s*\{/);
});
