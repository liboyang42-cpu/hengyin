'use strict';
// NOT_RUN: user deferred validation.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../pages/mylike/mylike.js');
function mount(tab) {
  let definition; const requests = [], prompts = [];
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Page: value => { definition = value; }, require(name) {
      if (name.endsWith('/modal.js')) return { show: options => prompts.push(options) };
      if (name.endsWith('/toast.js')) { const toast = () => {}; toast.success = toast; return toast; }
      // 帖文收藏走社区客户端;页面态单测隔离传输层(同 community-page-seam 用法)。
      if (name.includes('community-post-api')) return require('../helpers/community-page-seam');
      return createRequire(file)(name);
    }, console,
    getApp: () => ({ globalData: {}, getUserID: () => '11', sendRequest: r => requests.push(r),
      getRequestErrorMessage: (_r, fallback) => fallback }), wx: {},
  });
  const page = Object.assign({}, definition); page.data = JSON.parse(JSON.stringify(definition.data));
  page.data.activeFavoriteTab = tab; page.setData = patch => Object.assign(page.data, patch);
  return { page, requests, prompts, unlike() {
    if (tab === 'posts') page.postUnlike({ detail: { index: 0 } });
    else { page.topicUnlike({ currentTarget: { dataset: { id: 7 } } }); prompts.at(-1).success({ confirm: true }); }
  }, list: () => tab === 'posts' ? page.data.postList : page.data.ztList1 };
}
function seed(h) { h.page.data.postList = [{ id: 7 }]; h.page.data.ztList1 = [{ id: 7 }]; }
for (const tab of ['posts', 'topics']) {
  test('E05-07 ' + tab + ' old refresh cannot resurrect confirmed unlike', () => {
    const h = mount(tab); seed(h); h.page.refreshData(); const read = h.requests[0];
    h.unlike(); const unlike = h.requests[1]; unlike.success({ code: 200 }); unlike.complete();
    read.success({ code: 200, data: { rows: [{ id: 7 }, { id: 8 }] } }); read.complete();
    assert.deepEqual(Array.from(h.list(), row => row.id), [8]);
    assert.equal(h.page.data.pageConfig.pageNum, 1);
  });
  test('E05-07 ' + tab + ' failed unlike leaves refresh facts intact and allows retry', () => {
    const h = mount(tab); seed(h); h.page.refreshData(); h.unlike();
    h.requests[1].fail({}); h.requests[1].complete();
    h.requests[0].success({ code: 200, data: { rows: [{ id: 7 }] } }); h.requests[0].complete();
    assert.deepEqual(Array.from(h.list(), row => row.id), [7]);
    // F-current-favorites-AI(PC-024):主题取消断网=结果未知,先经 like_list 回读确认没取消成功才允许重试
    if (tab === 'topics') {
      assert.equal(h.requests[2].url, '/api/topic/like_list');
      h.requests[2].success({ code: 200, data: { rows: [{ id: 7 }] } });
    }
    h.unlike(); assert.equal(h.requests.length, tab === 'topics' ? 4 : 3);
    assert.equal(h.requests.at(-1).url, tab === 'topics' ? '/api/topic/like' : h.requests[1].url);
  });
  test('E05-07 ' + tab + ' subsequent authoritative read may show newly favorited same ID', () => {
    const h = mount(tab); seed(h); h.unlike(); h.requests[0].success({ code: 200 }); h.requests[0].complete();
    h.page.refreshData(); h.requests[1].success({ code: 200, data: { rows: [{ id: 7 }] } }); h.requests[1].complete();
    assert.deepEqual(Array.from(h.list(), row => row.id), [7]);
  });
  test('E05-07 ' + tab + ' stale complete cannot release subsequent same-ID retry', () => {
    const h = mount(tab); seed(h); h.unlike(); const old = h.requests[0];
    old.fail({}); old.complete();
    // 主题取消断网=结果未知(PC-024):回读确认未生效后才放锁,随后的重试不能被旧 complete 释放
    if (tab === 'topics') h.requests[1].success({ code: 200, data: { rows: [{ id: 7 }] } });
    h.unlike(); old.complete();
    if (tab === 'posts') h.page.postUnlike({ detail: { index: 0 } });
    else h.page.topicUnlike({ currentTarget: { dataset: { id: 7 } } });
    assert.equal(h.requests.length, tab === 'topics' ? 3 : 2);
    if (tab === 'topics') assert.equal(h.prompts.length, 2);
  });
}
test('E05-07 duplicate topic taps share one confirmation and old prompt cannot dispatch twice', () => {
  const h = mount('topics'); seed(h);
  const tap = () => h.page.topicUnlike({ currentTarget: { dataset: { id: 7 } } });
  tap(); tap(); assert.equal(h.prompts.length, 1); const old = h.prompts[0];
  old.success({ confirm: false }); tap(); old.complete(); old.success({ confirm: true });
  assert.equal(h.requests.length, 0);
  h.prompts[1].success({ confirm: true }); assert.equal(h.requests.length, 1);
});
