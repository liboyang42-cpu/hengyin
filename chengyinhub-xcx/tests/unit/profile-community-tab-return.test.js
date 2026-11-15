const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const seam = require('../helpers/community-page-seam');
const file = path.resolve(__dirname, '../../subpackageMember/components/cy/profile/index.js');
const flush = () => new Promise(resolve => setImmediate(resolve));

function mount(readable = true) {
  let owner = '7', token = 'Bearer test', read = readable, definition;
  const requests = [], refreshes = [];
  const app = { globalData: {}, getUserID: () => owner, getAuthorization: () => token,
    getUserRole: () => 'player', getUserType: () => 1, getPageSize: () => 10, getTotalPage: () => 1,
    canUseCommunity: () => read,
    loadFeatureFlags() { read = false; return new Promise(resolve => refreshes.push(allowed => { read = allowed; resolve(); })); },
    sendRequest(options) { requests.push(options); }, isDevEnv: () => false };
  global.getApp = () => app;
  global.wx = { getStorageSync: () => '', setStorageSync() {}, hideTabBar() {}, setNavigationBarColor() {}, setBackgroundColor() {} };
  global.Component = value => { definition = value; };
  seam.loadPage(file);
  const data = structuredClone(definition.data);
  for (const [key, value] of Object.entries(definition.properties)) data[key] = value.value;
  Object.assign(data, { isSelf: true, hostSelf: true, userId: '7', activeTab: 'posts',
    memberTabs: ['projects','posts','achievements','about'].map(key => ({key})), list: [{id:12}] });
  const page = Object.assign({}, definition.methods, { data, _profileAttached: true,
    refreshCoopPublisherAccess() {},
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.'); let target = data;
        for (const part of parts.slice(0,-1)) target = target[part] || (target[part] = {});
        target[parts.at(-1)] = value;
      }
      if ('isMerchantView' in patch || 'isSelf' in patch)
        definition.observers['isMerchantView, isSelf'].call(this, data.isMerchantView, data.isSelf);
    }
  });
  function observer() { definition.observers['isMerchantView, isSelf'].call(page, data.isMerchantView, data.isSelf); }
  return {page, definition, requests, refreshes, observer,
    identity(id, authorization) { owner=id; token=authorization; }, flags(allowed) { read=allowed; }};
}

test('profile posts selection returns after the synchronous capability-close observer path', async () => {
  const h=mount(); h.page._syncCommunityProfile(); h.observer();
  assert.equal(h.page.data.activeTab,'about'); assert.equal(h.page._pendingCommunityTab,true);
  assert.equal(h.page.data.memberTabs.some(tab=>tab.key==='posts'),false);
  h.page.getCreativesquareList(); assert.equal(h.requests.length,0);
  h.refreshes[0](true); await flush();
  assert.equal(h.page.data.activeTab,'posts'); assert.equal(h.page._pendingCommunityTab,false);
});

test('capability-close settle clears cached posts and restores the same selection only on success', async () => {
  const h=mount(false); h.page._syncCommunityProfile();
  assert.equal(h.page.data.activeTab,'about'); assert.deepEqual(h.page.data.list,[]);
  assert.equal(h.requests.length,0); h.refreshes[0](true); await flush();
  assert.equal(h.page.data.activeTab,'posts'); assert.equal(h.requests[0].url,'/api/creativesquare/list');
  const closed=mount(false); closed.page._syncCommunityProfile(); closed.refreshes[0](false); await flush();
  assert.equal(closed.page.data.activeTab,'about'); assert.equal(closed.requests.length,0);
});

test('a deliberate tab choice while flags refresh cancels the remembered posts intent', async () => {
  for (const key of ['about','projects']) {
    const h=mount(); h.page._syncCommunityProfile(); h.observer();
    h.page.onTabChange({detail:{key}}); h.refreshes[0](true); await flush();
    assert.equal(h.page.data.activeTab,key); assert.equal(h.requests.length,0);
  }
});

test('restored posts consume the existing dirty-list contract instead of keeping a deleted cached row', async () => {
  const h=mount(); h.page._squareListDirty=true; h.page._syncCommunityProfile(); h.observer();
  h.refreshes[0](true); await flush();
  assert.equal(h.page.data.activeTab,'posts'); assert.equal(h.requests[0].url,'/api/creativesquare/list');
  assert.equal(h.requests.filter(r=>r.url==='/api/creativesquare/list').length,1);
});

test('old owner, token, target, hidden, detached or superseded flag responses cannot restore a tab', async () => {
  for (const transition of ['owner','token','target','hidden','detached','epoch']) {
    const h=mount(); h.page._syncCommunityProfile(); h.observer();
    if(transition==='owner') h.identity('8','Bearer other');
    if(transition==='token') h.identity('7','Bearer renewed');
    if(transition==='target') h.page.data.userId='8';
    if(transition==='hidden') h.definition.pageLifetimes.hide.call(h.page);
    if(transition==='detached') h.definition.lifetimes.detached.call(h.page);
    if(transition==='epoch') h.page._syncCommunityProfile();
    h.refreshes[0](true); await flush();
    assert.equal(h.page.data.activeTab,'about',transition); assert.equal(h.requests.length,0,transition);
  }
});

test('identity change clears an old tab intent before a new read can use it', () => {
  const h=mount(); h.page._syncCommunityProfile(); h.observer();
  assert.equal(h.page._pendingCommunityTab,true); h.identity('8','Bearer other');
  h.page._syncProfileReadIdentity(); assert.equal(h.page._pendingCommunityTab,false);
});
