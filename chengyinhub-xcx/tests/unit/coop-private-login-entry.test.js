const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
function mount(kind) {
  let owner = '7', definition;
  const requests = [];
  const app = { globalData: {}, getUserID: () => owner, sendRequest: r => requests.push(r), firstLogin() { owner = '9'; return Promise.resolve(); } };
  global.getApp = () => app;
  global.wx = { stopPullDownRefresh() {}, getStorageSync() { return ''; }, nextTick(fn) { fn(); } };
  global.Page = d => definition = d;
  const file = path.resolve(__dirname, '../../pages/coop/'+kind+'/index.js');
  delete require.cache[file]; require(file);
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)), setData(p) { Object.assign(this.data, p); } });
  return { page, app, requests, owner(value) { owner = value; }, load() { kind === 'list' ? page.load() : (page.loadMyTopics(), page.loadTargets()); } };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
for (const kind of ['list', 'invite']) {
  const state = kind === 'list' ? 'listState' : 'topicsState';
  test(kind+' anonymous coop entry sends no private query and has one explicit login/reload', async () => {
    const h = mount(kind); h.owner(''); h.load(); assert.equal(h.requests.length, 0); assert.equal(h.page.data[state], 'login');
    let calls = 0; h.app.firstLogin = () => { calls++; h.owner('9'); return Promise.resolve(); };
    h.page.onLogin(); h.page.onLogin(); await flush(); assert.equal(calls, 1); assert.ok(h.requests.length > 0);
    assert.ok(h.requests.every(r => !/handle|respond|revoke|refund/.test(r.url)), 'login reload is read-only');
  });
  test(kind+' actual HTTP401/403 are visible while HTTP500 keeps normal read-failure', () => {
    for (const code of [401, 403, 500]) {
      const h = mount(kind); h.load(); h.requests[0].successStatusAbnormal({statusCode: code}, code);
      assert.equal(h.page.data[state], code === 401 ? 'login' : code === 403 ? 'permission' : 'error');
    }
  });
  test(kind+' late private rows from former account cannot appear after account switch', () => {
    const h = mount(kind); h.load(); const old = h.requests[0]; h.owner(''); h.load();
    old.success(kind === 'list' ? {code:200,data:{received:[{id:1}],sent:[]}} : {code:200,data:{rows:[{id:1,name:'OLD'}]}});
    assert.equal(h.page.data[state], 'login'); assert.equal((h.page.data.received || h.page.data.myTopics).length, 0);
  });
}
test('a revoked coop pool or chapter permission clears cached private lists and rejects earlier successful callbacks', () => {
  for(const endpoint of ['/api/coop/pool/received','/api/merchant/chapter-application/owner-mine']) {
    const h=mount('list');h.load();const request=h.requests.find(r=>r.url===endpoint);
    assert.ok(request, endpoint);h.page.data.received=[{id:8}];request.success({code:403,msg:'真实权限拒绝'});
    assert.equal(h.page.data.listState,'permission');assert.equal(h.page.data.received.length,0);
    h.requests[0].success({code:200,data:{received:[{id:8}],sent:[]}});assert.equal(h.page.data.received.length,0);
  }
});
