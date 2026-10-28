// BL-80 官方通知复盘:点「我发布的」里的通知卡,读 GET /api/official/broadcast/{id}/stats,就地显示触达/点击。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');
const FILE = path.join(root, 'pages/activity/official-mine/index.js');

function loadPage() {
  let page;
  const requests = [];
  vm.runInNewContext(fs.readFileSync(FILE, 'utf8'), {
    getApp: () => ({
      getUserID: () => '7', getAuthorization: () => 'fixture',
      sendRequest: r => { requests.push(r); },
      getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    }),
    Page: p => { page = p; },
    require: id => {
      if (id.endsWith('/user-character.js')) return require(path.join(root, 'utils/user-character.js'));
      if (id.endsWith('/response-shape.js')) return require(path.join(root, 'utils/response-shape.js'));
      return {};
    },
  });
  page.data = { broadcasts: [{ id: 9, _reach: '站内触达 3 人' }, { id: 10, _reach: '站内触达 0 人' }] };
  page.setData = patch => {
    Object.keys(patch).forEach((k) => {
      const m = k.match(/^broadcasts\[(\d+)\]\.(\w+)$/);
      if (m) page.data.broadcasts[Number(m[1])][m[2]] = patch[k];
      else page.data[k] = patch[k];
    });
  };
  return { page, requests };
}

const tap = (index) => ({ currentTarget: { dataset: { index } } });

test('点通知卡读该条复盘,成功显示触达与点击', () => {
  const { page, requests } = loadPage();
  page.openBroadcastStats(tap(0));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/official/broadcast/9/stats');
  assert.equal(requests[0].method, 'GET');
  assert.equal(page.data.broadcasts[0]._statsText, '正在读取复盘…');
  page.openBroadcastStats(tap(0));
  assert.equal(requests.length, 1, '读取中不重复发');
  requests[0].success({ code: 200, data: { reached: 12, clicked: 4 } });
  assert.equal(page.data.broadcasts[0]._statsText, '站内触达 12 人 · 点击 4 人');
  assert.equal(page.data.broadcasts[0]._statsLoading, false);
});

test('后端拒绝/形状不对/网络失败如实提示,可再点重试', () => {
  const { page, requests } = loadPage();
  page.openBroadcastStats(tap(1));
  requests[0].success({ code: 500, msg: '无权查看该通知数据' });
  assert.equal(page.data.broadcasts[1]._statsText, '无权查看该通知数据，点一下重试');
  page.openBroadcastStats(tap(1));
  requests[1].success({ code: 200, data: { reached: 1 } });
  assert.equal(page.data.broadcasts[1]._statsText, '复盘没读到，点一下重试');
  page.openBroadcastStats(tap(1));
  requests[2].fail({});
  assert.equal(page.data.broadcasts[1]._statsText, '网络连接失败，点一下重试');
});

test('列表已刷新换了条目时,迟到的复盘不写到别的卡上', () => {
  const { page, requests } = loadPage();
  page.openBroadcastStats(tap(0));
  page.data.broadcasts = [{ id: 77, _reach: 'x' }];
  requests[0].success({ code: 200, data: { reached: 1, clicked: 1 } });
  assert.equal(page.data.broadcasts[0]._statsText, undefined);
});

test('接线:通知卡可点、复盘就地替换原触达行;不改主标题;回调不用 this 方法简写', () => {
  const wxml = fs.readFileSync(path.join(root, 'pages/activity/official-mine/index.wxml'), 'utf8');
  const js = fs.readFileSync(FILE, 'utf8');
  assert.match(wxml, /<cy-page-title title="我发布的" \/>/);
  assert.match(wxml, /data-index="\{\{index\}\}" bindtap="openBroadcastStats"/);
  assert.match(wxml, /\{\{item\._statsText \|\| item\._reach\}\}/);
  const fn = js.match(/openBroadcastStats\(e\)\s*\{[\s\S]*?\n {2}\},/)[0];
  assert.doesNotMatch(fn, /success\s*\(res\)\s*\{|fail\s*\(res\)\s*\{/);
});
