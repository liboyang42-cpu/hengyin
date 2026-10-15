const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const root = path.resolve(__dirname, '../..');
const tick = () => new Promise(resolve => setImmediate(resolve));
const reporting = { policyVersion: 'synthetic', reasons: [{ label: '合成原因', code: 'SYNTHETIC' }] };
const entries = [['pages/square/list', false], ['pages/square/detail', false], ['pages/square/detail', true]];
function harness(route, comment) {
  let definition, actor = 1001, authorization = 'synthetic-old';
  const calls = [], tips = [], timers = [], patches = [], modals = [], navigations = [];
  const app = { globalData: {}, getUserID: () => actor, getAuthorization: () => authorization, getPageSize: () => 10,
    tips: value => tips.push(value), sendRequest(options) { calls.push(options); return { abort() {} }; } };
  const file = path.join(root, route, 'index.js'), localRequire = createRequire(file);
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    getApp: () => app, Page: config => { definition = config; }, console, wx: {},
    setTimeout(callback) { timers.push(callback); }, clearTimeout() {}, setInterval() {}, clearInterval() {},
    require(id) {
      if (id.endsWith('/utils/modal.js')) return { show(options) { modals.push(options); } };
      if (id.endsWith('/utils/toast.js')) return value => tips.push(value);
      if (id.endsWith('/utils/merchant-theme.js')) return { merchantPageRestore() {} };
      return localRequire(id);
    },
  }, { filename: file });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { patches.push(patch); Object.assign(this.data, patch); }, goBack() { navigations.push('back'); } });
  page.data.id = 59; page.data.list = [{ id: 59 }]; page._actionIndex = 0;
  return { page, calls, tips, timers, patches, modals, navigations,
    changeOwner() { actor = 1002; }, renew() { authorization = 'synthetic-renewed'; },
    start() { if (comment) page.reportComment({ currentTarget: { dataset: { index: 0 } } }); else page.reportSquare(59); },
  };
}
async function confirm(h) { h.start(); h.modals[0].success({ confirm: true }); await tick(); }
async function choose(h) {
  h.calls[0].success({ code: 200, data: { reporting } }); await tick();
  h.page.onReportReasonSelect({ detail: { index: 0 } }); await tick();
}
for (const [route, comment] of entries) {
  const label = route + (comment ? ':comment' : ':post');
  for (const phase of ['capabilities', 'post']) for (const outcome of ['business', 'network', 'http']) {
    test(label + ' ' + phase + ' ' + outcome + ' displays exactly one recoverable error', async () => {
      const h = harness(route, comment); await confirm(h); if (phase === 'post') await choose(h);
      const request = h.calls.at(-1);
      if (outcome === 'business') request.success({ code: 503, msg: '合成服务失败' });
      else if (outcome === 'http') request.successStatusAbnormal({ msg: '合成服务失败' }, 503);
      else request.fail({ errMsg: 'synthetic network' });
      await tick();
      assert.deepEqual(h.tips, [outcome === 'business' ? '合成服务失败' : '网络异常，请重试']);
      assert.equal(h.page.data.list.length, 1); assert.equal(h.timers.length, 0);
    });
  }
  test(label + ' reason cancellation stays silent and never POSTs', async () => {
    const h = harness(route, comment); await confirm(h); h.calls[0].success({ code: 200, data: { reporting } }); await tick();
    h.page.onReportReasonCancel(); await tick(); assert.equal(h.calls.length, 1); assert.deepEqual(h.tips, []);
  });
  for (const phase of ['capabilities', 'post']) {
    test(label + ' ' + phase + ' transport cancellation stays silent', async () => {
      const h = harness(route, comment); await confirm(h); if (phase === 'post') await choose(h);
      h.calls.at(-1).fail({ code: 499, msg: '操作已取消' }); await tick();
      assert.deepEqual(h.tips, []); assert.equal(h.page.data.list.length, 1); assert.equal(h.timers.length, 0);
    });
  }
  test(label + ' malformed taxonomy is visible once', async () => {
    const h = harness(route, comment); await confirm(h); h.calls[0].success({ code: 200, data: {} }); await tick();
    assert.deepEqual(h.tips, ['举报原因暂不可用']); assert.equal(h.calls.length, 1);
  });
  for (const invalidation of ['unload', 'owner']) for (const phase of ['modal', 'capabilities', 'selection', 'post-ok', 'post-business', 'post-network']) {
    test(label + ' ' + invalidation + ' during ' + phase + ' prevents late effects', async () => {
      const h = harness(route, comment);
      if (phase === 'modal') h.start(); else await confirm(h);
      if (phase === 'selection') { h.calls[0].success({ code: 200, data: { reporting } }); await tick(); }
      if (phase.startsWith('post-')) await choose(h);
      if (invalidation === 'unload') h.page.onUnload(); else h.changeOwner();
      h.patches.length = 0; h.tips.length = 0;
      if (phase === 'modal') h.modals[0].success({ confirm: true });
      else if (phase === 'capabilities') h.calls[0].success({ code: 200, data: { reporting } });
      else if (phase === 'selection') h.page.onReportReasonSelect({ detail: { index: 0 } });
      else if (phase === 'post-network') h.calls[1].fail({ errMsg: 'old network' });
      else h.calls[1].success({ code: phase === 'post-ok' ? 200 : 503, data: { id: 1 }, msg: '旧响应' });
      await tick();
      assert.deepEqual(h.tips, []); assert.equal(h.timers.length, 0);
      // Selecting a still-open reason sheet closes that sheet; report callbacks cannot touch other UI.
      const unexpected = h.patches.filter(patch => Object.keys(patch).some(key => !['reportReasonShow', 'reportReasonItems'].includes(key)));
      assert.deepEqual(unexpected, []);
      assert.equal(h.calls.length, phase === 'modal' ? 0 : phase.startsWith('post-') ? 2 : 1);
    });
  }
  test(label + ' same owner token renewal preserves successful submission', async () => {
    const h = harness(route, comment); await confirm(h); await choose(h); h.renew();
    h.calls[1].success({ code: 200, data: { id: 1 } }); await tick(); assert.deepEqual(h.tips, ['已提交举报']);
    assert.equal(h.calls[1].data.targetType, comment ? 'COMMENT' : 'CONTENT');
    if (route.endsWith('/detail') && !comment) { assert.equal(h.timers.length, 1); h.timers[0](); assert.deepEqual(h.navigations, ['back']); }
    else assert.equal(h.page.data.list.length, 0);
  });
}
for (const invalidation of ['unload', 'owner']) {
  test('detail delayed return rechecks ' + invalidation, async () => {
    const h = harness('pages/square/detail', false); await confirm(h); await choose(h);
    h.calls[1].success({ code: 200, data: { id: 1 } }); await tick(); assert.equal(h.timers.length, 1);
    if (invalidation === 'unload') h.page.onUnload(); else h.changeOwner();
    h.timers[0](); assert.deepEqual(h.navigations, []);
  });
}
