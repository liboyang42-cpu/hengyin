const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');

function reportPage(route, sourceOverride) {
  let definition, actor = 990005;
  const calls = [];
  const app = { globalData: {}, getUserID: () => actor, getPageSize: () => 10,
    sendRequest(o) { calls.push(o); return { abort() {} }; } };
  const file = path.join(root, route, 'index.js'), localRequire = createRequire(file);
  vm.runInNewContext(sourceOverride || fs.readFileSync(file, 'utf8'), {
    getApp: () => app, Page: config => { definition = config; }, console,
    wx: {}, setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {},
    require(id) {
      if (id.endsWith('/utils/modal.js')) return { show(o) { o.success({ confirm: true }); } };
      if (id.endsWith('/utils/toast.js')) return () => {};
      if (id.endsWith('/utils/merchant-theme.js')) return { merchantPageRestore() {} };
      return localRequire(id);
    },
  }, { filename: file });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { Object.assign(this.data, patch); } });
  page.data.id = 59; page.data.list = [{ id: 59 }]; page._actionIndex = 0;
  return { page, calls, setActor(id) { actor = id; } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
let cachedLedger;
const ledger = () => cachedLedger || (cachedLedger = require('../../scripts/uiaudit/build-action-ledger').buildLedger());
const reporting = { policyVersion: 'test-policy', reasons: [{ label: '真实服务端原因', code: 'REAL_CODE' }] };
for (const route of ['pages/square/list', 'pages/square/detail']) {
  test(route + ' renders server taxonomy, cancel sends no report', async () => {
    const h = reportPage(route); h.page.reportSquare(); await tick();
    assert.equal(h.calls[0].url, '/api/v1/community/capabilities');
    h.calls[0].success({ code: 200, data: { reporting } }); await tick();
    assert.equal(h.page.data.reportReasonShow, true);
    assert.deepEqual(Array.from(h.page.data.reportReasonItems), ['真实服务端原因']);
    const wxml = fs.readFileSync(path.join(root, route, 'index.wxml'), 'utf8');
    assert.match(wxml, /cy-option-sheet[^>]*bind:select="onReportReasonSelect"/);
    const json = JSON.parse(fs.readFileSync(path.join(root, route, 'index.json'), 'utf8'));
    assert.equal(json.usingComponents['cy-option-sheet'], '/components/cy/option-sheet/index');
    h.page.onReportReasonCancel(); await tick(); assert.equal(h.calls.length, 1);
    assert.equal(h.page.data.reportReasonShow, false);
  });
  test(route + ' selected reason submits canonical target, never legacy URL', async () => {
    const h = reportPage(route); h.page.reportSquare(); await tick();
    h.calls[0].success({ code: 200, data: { reporting } }); await tick();
    h.page.onReportReasonSelect({ detail: { index: 0 } }); await tick();
    assert.equal(h.calls.length, 2); assert.equal(h.calls[1].url, '/api/v1/community/reports');
    assert.equal(h.calls[1].method, 'POST'); assert.equal(h.calls[1].data.targetType, 'CONTENT');
    assert.equal(h.calls[1].data.targetId, 59); assert.equal(h.calls[1].data.reasonCode, 'REAL_CODE');
    h.calls[1].success({ code: 200, data: { id: 1 } }); await tick();
  });
  test(route + ' destroyed page cannot reopen a reason sheet from a late capability response', async () => {
    const h = reportPage(route); h.page.reportSquare(); await tick();
    h.page.onUnload(); h.calls[0].success({ code: 200, data: { reporting } }); await tick();
    assert.equal(h.calls.length, 1); assert.equal(h.page.data.reportReasonShow, false);
    assert.equal(h.page._reportReasonSelection, undefined);
  });
  test(route + ' missing taxonomy or owner switch never writes a report', async () => {
    for (const changeOwner of [false, true]) {
      const h = reportPage(route); h.page.reportSquare(); await tick();
      h.calls[0].success({ code: 200, data: changeOwner ? { reporting } : {} }); await tick();
      if (changeOwner) { h.setActor(990012); h.page.onReportReasonSelect({ detail: { index: 0 } }); }
      await tick(); assert.equal(h.calls.length, 1);
      assert.equal(h.page.data.reportReasonShow, false);
    }
  });
}

test('ledger includes four real dynamic handler branches and rejects unknown grammar', () => {
  const g = require('../../scripts/uiaudit/build-action-ledger');
  assert.deepEqual(g.eventHandlerNames("{{item.memberId ? 'goMemberProfile' : 'expandMembers'}}"), ['goMemberProfile', 'expandMembers']);
  assert.deepEqual(g.eventHandlerNames("{{item.locked ? 'noop' : 'openStory'}}"), ['noop', 'openStory']);
  assert.throws(() => g.eventHandlerNames('{{runtimeHandler}}'), /无法静态解析/);
  const rebuilt = ledger();
  for (const [route, handler] of [['pages/club/detail/index', 'expandMembers'], ['pages/club/detail/index', 'goMemberProfile'], ['pages/play/index', 'noop'], ['pages/play/index', 'openStory']])
    assert.ok(rebuilt.controls.some(c => c.route === route && c.control.handler === handler && c.control.ariaLabelExpression), route + ':' + handler);
});


test('real report handlers resolve canonical GET then POST; source aliases are not forged by comments', () => {
  const g = require('../../scripts/uiaudit/build-action-ledger');
  for (const [route, handler] of [['pages/square/list', 'reportSquare'],
    ['pages/square/detail', 'reportSquare'], ['pages/square/detail', 'reportComment']]) {
    const file = path.join(root, route, 'index.js');
    assert.deepEqual(g.targetsForHandler(file, handler).primaryApis,
      ['/api/v1/community/capabilities', '/api/v1/community/reports']);
    const declaration = "const community = require('../../../utils/community-post-api.js').createClient(app, wx);";
    assert.deepEqual(g.communityAliasesForSource(declaration, file), ['community']);
    for (const fake of ['// ' + declaration, '/* ' + declaration + ' */', JSON.stringify(declaration)])
      assert.deepEqual(g.communityAliasesForSource(fake, file), []);
  }
});

test('canonical report request parsing is narrow and preserves stage write authority', () => {
  const g = require('../../scripts/uiaudit/build-action-ledger');
  const request = "community.request({url:'/api/creativesquare/report', data:{id:59}})";
  const contracts = g.communityRequestContracts(request, ['community']).contracts;
  assert.deepEqual(contracts.map(c => [c.url, c.method]),
    [['/api/v1/community/capabilities', 'GET'], ['/api/v1/community/reports', 'POST']]);
  assert.deepEqual(g.communityRequestPlan(contracts).requiredRequests.map(c => c.method), ['GET', 'POST']);
  for (const fake of ['// ' + request, '/* ' + request + ' */', JSON.stringify(request)])
    assert.deepEqual(g.communityRequestContracts(fake, ['community']).contracts, []);
  assert.throws(() => g.communityRequestContracts("community.request({url:dynamic})", ['community']), /literal/);
  assert.throws(() => g.communityRequestContracts("community.request({url:'/api/comment/unknown'})", ['community']), /未登记/);
  assert.deepEqual(g.communityRequestContracts("unrelated.request({url:'/api/comment/unknown'})", ['community']).contracts, []);
  for (const url of ['/api/creativesquare/like', '/api/creativesquare/list', '/api/creativesquare/action',
    '/api/comment/add', '/api/v1/community/reports', '/other'])
    assert.throws(() => g.communityRequestContracts(`community.request({url:'${url}'})`, ['community']), /未登记/);
  const plan = g.communityRequestPlan(contracts);
  assert.deepEqual(plan.requiredRequestsByBranch['duplicate-trigger'].map(c => c.method), ['GET', 'POST']);
  for (const failure of ['error', 'timeout']) {
    assert.deepEqual(plan.requiredRequestsByBranch[failure].map(c => c.method), ['GET']);
    assert.deepEqual(plan.requiredRequestOutcomes[failure], [failure]);
    assert.deepEqual(plan.requiredRequestsByBranch['stage-2-' + failure].map(c => c.method), ['GET', 'POST']);
    assert.deepEqual(plan.requiredRequestOutcomes['stage-2-' + failure], ['success', failure]);
  }
  assert.throws(() => g.communityRequestPlan([{ adapterBranch: 'enable-LIKE', method: 'POST', url: '/unsupported' }]), /未登记/);
  assert.throws(() => g.communityRequestPlan(contracts.slice(0, 1)), /不完整/);
});

test('public growth CTA retains the old goExplore obligation without recreating a duplicate button', () => {
  const controls = ledger().controls.filter(c => c.route === 'subpackageP3/pages/growthcenter/index/index'
    && c.control.handler === 'goExplore');
  assert.equal(controls.length, 1); assert.equal(controls[0].control.event, 'bind:cta');
  assert.ok(controls[0].requiredBranches.includes('success'));
  assert.ok(controls[0].requiredBranches.includes('error'));
});


test('negative control: removing the unload guard reopens a late reason sheet', async () => {
  for (const route of ['pages/square/list', 'pages/square/detail']) {
    const source = fs.readFileSync(path.join(root, route, 'index.js'), 'utf8');
    const anchor = '  onUnload() {\n    this._unloaded = true;\n    if (this._community) this._community.dispose();\n    reportSheet.cancel(this);';
    assert.ok(source.includes(anchor));
    const h = reportPage(route, source.replace(anchor, '  onUnload() {\n    reportSheet.cancel(this);'));
    h.page.reportSquare(); await tick(); h.page.onUnload();
    h.calls[0].success({ code: 200, data: { reporting } }); await tick();
    assert.equal(h.page.data.reportReasonShow, true, 'negative control did not expose the missing guard');
    h.page.onReportReasonCancel(); await tick();
  }
});

// The ten restored controls retain their original obligations. Growth's old tap
// maps to one CTA; its three retry controls are separate additional obligations.
const restoredMappings = [
  ['pages/club/detail/index', 'catchtap', 'expandMembers', '.ov-strip-item'],
  ['pages/club/detail/index', 'catchtap', 'goMemberProfile', '.ov-strip-item'],
  ['pages/play/index', 'bindtap', 'noop', '.jkv'],
  ['pages/play/index', 'bindtap', 'openStory', '.jkv'],
  ['pages/publish/temp/index', 'bindtap', 'uploadPic', '.cg-hero-cover', '更换玩法封面'],
  ['pages/square/detail/index', 'bind:cancel', 'onReportReasonCancel', 'cy-option-sheet[bind:cancel]'],
  ['pages/square/detail/index', 'bind:select', 'onReportReasonSelect', 'cy-option-sheet[bind:select]'],
  ['pages/square/list/index', 'bind:cancel', 'onReportReasonCancel', 'cy-option-sheet[bind:cancel]'],
  ['pages/square/list/index', 'bind:select', 'onReportReasonSelect', 'cy-option-sheet[bind:select]'],
  ['subpackageB/pages/im/chat/index', 'bind:done', 'onDangerCancel', '#dc'],
];
function mappedControl(controls, [route, event, handler, selector, label]) {
  const matches = controls.filter(c => c.route === route && c.control.event === event
    && c.control.handler === handler && c.control.selectorHint === selector
    && (label === undefined || c.control.ariaLabel === label));
  assert.equal(matches.length, 1, `${route}:${event}:${handler}:${selector}`);
  return matches[0];
}
function inventoryIdentityDigest(controls) {
  const identities = controls.map(c => JSON.stringify([c.route, c.entry.wxml,
    c.control.tag, c.control.event, c.control.handler, c.control.selectorHint,
    c.control.ariaLabel, c.variant, c.role])).sort();
  return crypto.createHash('sha256').update(JSON.stringify(identities)).digest('hex');
}

test('mapping: ten restored controls preserve every public baseline identity, even at equal counts', () => {
  const frozen = require('../../scripts/uiaudit/action-ledger.json');
  const restored = restoredMappings.map(m => mappedControl(frozen.controls, m));
  assert.equal(new Set(restored.map(c => c.id)).size, 10);
  const restoredIds = new Set(restored.map(c => c.id));
  assert.equal(frozen.controlCount, 4445);
  const publishRoute = 'component:pages/square/components/cy/post-compose/index';
  const feedbackRoute = 'component:subpackageMember/components/cy/profile/index';
  const publish = frozen.controls.filter(c => c.route === publishRoute && c.control.handler === 'onPublish');
  assert.equal(publish.length, 2);
  assert.deepEqual(publish.map(c => [c.variant.id, c.variant.predicate.editMode]).sort(), [['create', false], ['edit', true]]);
  const feedback = frozen.controls.filter(c => c.route === feedbackRoute && c.control.handler === 'lickClick' && c.control.ariaLabel === '不感兴趣');
  assert.equal(feedback.length, 1); assert.equal(feedback[0].control.ariaLabel, '不感兴趣');
  // Two reviewed substitutions: one publish control has explicit create/edit
  // variants; the misleading feedback label now follows the approved action.
  const retained = frozen.controls.filter(c => !restoredIds.has(c.id)
    && !(c.route === publishRoute && c.control.handler === 'onPublish' && c.variant.id === 'edit')).map(c => {
      if (c.route === publishRoute && c.control.handler === 'onPublish') return { ...c, variant: null };
      if (c.route === feedbackRoute && c.control.handler === 'lickClick' && c.control.ariaLabel === '不感兴趣') return { ...c, control: { ...c.control, ariaLabel: '点赞' } };
      return c;
    });
  assert.equal(retained.length, 4434);
  // Reviewed public d16c292 baseline; excludes line/index shifts but preserves
  // multiplicity, variant, role and actual control identity. No private Git needed.
  const baselineDigest = '7831b983503e787af0df487ee917c0fb4f69840132c8246e9b8ee2c7a1a7fd7e';
  assert.equal(inventoryIdentityDigest(retained), baselineDigest);
  assert.notEqual(inventoryIdentityDigest(retained.slice(1)), baselineDigest);
  const sameCountDrift = retained.slice(1).concat(retained[1]);
  assert.equal(sameCountDrift.length, retained.length);
  assert.notEqual(inventoryIdentityDigest(sameCountDrift), baselineDigest,
    'duplicating a control must not hide a removed obligation');
});

test('mapping: all restored obligations have actual handler, event and dependency source closure', () => {
  const g = require('../../scripts/uiaudit/build-action-ledger');
  const frozen = require('../../scripts/uiaudit/action-ledger.json');
  for (const mapping of restoredMappings) {
    const c = mappedControl(frozen.controls, mapping);
    assert.ok(c.requiredBranches.length > 0); assert.ok(Array.isArray(c.requiredPlatforms));
    if (c.externalGate) assert.ok(c.requiredPlatforms.length > 0);
    assert.doesNotMatch(c.evidence.staticSource, /:0$/);
    const files = g.sourceFilesFor(c);
    for (const file of [c.entry.js, c.entry.wxml, c.entry.wxml.replace(/\.wxml$/, '.json')])
      assert.ok(files.includes(file), `unbound source ${file}`);
    const handler = require('../../scripts/lib/xcx-scan').extractOwnHandlerEntries(path.join(root, c.entry.js))
      .find(e => e.name === c.control.handler);
    assert.ok(handler, `missing actual handler ${c.id}`);
    assert.equal(c.evidence.staticSource, `${path.relative(root, handler.file).replace(/\\/g, '/')}:${handler.line}`);
    const wxml = fs.readFileSync(path.join(root, c.entry.wxml), 'utf8');
    assert.ok(wxml.includes(c.control.event));
    if (c.control.handler.startsWith('onReportReason')) {
      for (const file of ['utils/community-report-sheet.js', 'utils/community-post-api.js'])
        assert.ok(files.includes(file), `unbound report dependency ${file}`);
      assert.match(wxml, new RegExp(`${c.control.event}="${c.control.handler}"`));
    }
  }
});

test('mapping: original growth tap and current CTA retain navigation and identity boundaries; three retries remain distinct', () => {
  const frozen = require('../../scripts/uiaudit/action-ledger.json');
  const route = 'subpackageP3/pages/growthcenter/index/index';
  const growth = frozen.controls.filter(c => c.route === route);
  const cta = mappedControl(growth, [route, 'bind:cta', 'goExplore', 'cy-empty[bind:cta]']);
  assert.equal(growth.filter(c => c.control.handler === 'goExplore').length, 1);
  assert.equal(cta.role, 'player'); assert.deepEqual(cta.targets.routes, ['pages/index/index']);
  assert.deepEqual(cta.targets.apis, []); assert.deepEqual(cta.requiredBranches, ['success', 'error']);
  const g = require('../../scripts/uiaudit/build-action-ledger');
  const files = g.sourceFilesFor(cta);
  for (const file of ['app.js', 'utils/session/session-manager.js', 'utils/transport/request-client.js'])
    assert.ok(files.includes(file), `unbound identity runtime ${file}`);
  const file = path.join(root, cta.entry.js), source = fs.readFileSync(file, 'utf8');
  const entry = require('../../scripts/lib/xcx-scan').extractOwnHandlerEntries(file).find(e => e.name === 'goExplore');
  assert.ok(entry);
  const method = source.slice(entry.start, entry.end);
  // This is the original method contract, including its failure recovery; adding
  // an event-dependent or account-dependent prerequisite cannot pass by name.
  assert.equal(method.replace(/\s+/g, ' '), "goExplore() { wx.switchTab({ url: '/pages/index/index', fail: () => wx.navigateBack({ fail: () => {} }) }) }");
  for (const actor of [990005, 990012]) {
    for (const event of [{ currentTarget: { dataset: {} } }, { detail: {} }]) {
      const calls = [];
      const app = new Proxy({}, { get() { assert.fail('navigation read account state'); } });
      const definition = vm.runInNewContext(`({${method}})`, { app, getApp: () => app,
        wx: { switchTab(o) { calls.push(['switchTab', o.url]); o.fail(); },
          navigateBack(o) { calls.push(['navigateBack']); o.fail(); } } });
      assert.equal(definition.goExplore.length, 0);
      const session = { actor }; definition.goExplore.call(session, event);
      assert.equal(session.actor, actor);
      assert.deepEqual(calls, [['switchTab', '/pages/index/index'], ['navigateBack']]);
    }
  }
  for (const [event, handler, selector, api] of [
    ['bind:action', 'retryRank', 'cy-inline-error[bind:action]', '/api/growth/leaderboard'],
    ['bind:action', 'loadOverview', '.gc-overview-inline', '/api/growth/center'],
    ['bindtap', 'loadOverview', '.gc-badge-state-retry', '/api/growth/center'],
  ]) {
    const c = mappedControl(growth, [route, event, handler, selector]);
    assert.ok(c.targets.primaryApis.includes(api));
    for (const branch of ['success', 'error', 'timeout', 'duplicate-trigger'])
      assert.ok(c.requiredBranches.includes(branch), `${c.id}:${branch}`);
    assert.doesNotMatch(c.evidence.staticSource, /:0$/);
  }
});
