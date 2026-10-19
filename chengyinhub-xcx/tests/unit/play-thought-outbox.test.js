const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.js'), 'utf8');
const methods = source.slice(source.indexOf('  _pendingThoughtKeys() {'), source.indexOf('  /* 故事流声音'));
function setup(shared = {}, storageFailure = false) {
  let member = 1, shouldFail = true, requests = 0;
  const data = { topicId: 8, isPreview: false, isMock: false, chapStory: {} };
  const storyResume = {
    read: scope => shared[scope.memberId] || {},
    patch: (scope, fields) => { if (storageFailure) return false; shared[scope.memberId] = { ...shared[scope.memberId], ...fields }; return true; },
  };
  const spec = vm.runInNewContext('({' + methods + '})', {
    storyResume,
    req: (url, method, body) => { requests++; return shouldFail ? Promise.reject(Error('offline')) : Promise.resolve({ code: 200, data: { thoughts: body.claim.map(key => ({ key })) } }); },
  });
  const page = { ...spec, data, setData(p) { Object.assign(data, p); }, _runSessionScope: () => ({ memberId: member, topicId: 8 }),
    _sessionParams: () => ({ topicId: 8 }), _weRunCipher: () => Promise.resolve(null), _applyRouteState(s) { this._routeState = s; } };
  return { page, shared, online: () => { shouldFail = false; }, count: () => requests, member: v => { member = v; } };
}
test('断网心声持久化，重进补发成功后清理，仅由回读产生已收集', async () => {
  const a = setup(); await a.page._syncThoughts(['t1', 't1']);
  assert.equal(a.shared[1].pendingThoughts.length, 1);
  assert.match(a.page.data.thoughtSyncNotice, /待同步/); assert.equal(a.page._routeState, undefined);
  const b = setup(a.shared); b.online(); await b.page.retryThoughtSync();
  assert.equal(b.count(), 1); assert.equal(b.shared[1].pendingThoughts.length, 0);
  assert.equal(b.page._routeState.thoughts[0].key, 't1');
  await b.page.retryThoughtSync(); assert.equal(b.count(), 1);
});
test('预览与演示不读写正式队列或请求，B账号不承接A待同步', async () => {
  const a = setup(); await a.page._syncThoughts(['t1']);
  for (const mode of ['isPreview', 'isMock']) {
    const b = setup(a.shared); b.page.data[mode] = true; b.online();
    await b.page._syncThoughts(['preview']); await b.page.retryThoughtSync();
    assert.equal(b.count(), 0); assert.equal(b.shared[1].pendingThoughts[0], 't1');
  }
  a.member(2); await a.page.retryThoughtSync(); assert.equal(a.count(), 1);
});
test('保存失败提示勿退出，内存仍可重试', async () => {
  const a = setup({}, true); a.page._persistThoughtKeys(['x']);
  assert.match(a.page.data.thoughtSyncNotice, /请勿退出/);
  assert.equal(a.shared[1], undefined);
  assert.equal(a.page._pendingThoughtKeys()[0], 'x');
});

test('真实存档补写保留阅读进度；读取失败与坏档禁止覆盖', () => {
  const resume = require('../../pages/play/utils/story-resume.js');
  let current = { pos: { chapterId: 3, i: 2 } }, writes = 0;
  global.wx = { getStorageSync: () => current, setStorageSync: (_, value) => { current = value; writes++; } };
  const scope = { memberId: 1, topicId: 8 };
  assert.equal(resume.patch(scope, { pendingThoughts: ['a'] }), true);
  assert.equal(current.pos.i, 2);
  current = 'damaged';
  assert.equal(resume.patch(scope, { pendingThoughts: ['b'] }), false);
  assert.equal(writes, 1); assert.equal(current, 'damaged');
  global.wx.getStorageSync = () => { throw Error('unreadable'); };
  assert.equal(resume.patch(scope, { pendingThoughts: ['b'] }), false);
  assert.equal(writes, 1);
  delete global.wx;
});
