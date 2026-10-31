const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const vm = require('../helpers/ui-sandbox-vm');
const ROOT = path.resolve(__dirname, '../..');
function mount(kind) {
  let owner = '7', read = false, write = false, definition;
  const requests = [], refreshes = [], toasts = [];
  const app = { globalData: {}, getUserID: () => owner, getUserRole: () => 'player', getUserType: () => 1,
    getPageSize: () => 10, getTotalPage: () => 1, isDevEnv: () => false, setUserRole() {},
    canUseCommunity: mode => mode ? write : read,
    loadFeatureFlags: () => new Promise(resolve => refreshes.push(resolve)),
    sendRequest: options => { requests.push(options); return { abort() {} }; }, tips() {} };
  const file = path.join(ROOT, 'subpackageMember/components/cy/profile/index.js');
  const wx = { hideTabBar() {}, showToast: o => toasts.push(o.title), stopPullDownRefresh() {} };
  global.getApp = () => app; global.wx = wx;
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { getApp: () => app, wx, console,
    require: id => id.includes('community-post-api') ? require('../helpers/community-page-seam') : createRequire(file)(id),
    Component: d => definition = d, Page: d => definition = d, setTimeout() {}, clearTimeout() {} }, { filename: file });
  const page = Object.assign({}, definition, definition.methods, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = (patch, done) => { Object.entries(patch).forEach(([key,value]) => { const parts=key.split('.');let o=page.data;for(const p of parts.slice(0,-1))o=o[p]||(o[p]={});o[parts.at(-1)]=value; }); if(done)done(); };
  Object.assign(page.data, { userId: '8', isSelf: false, activeTab: 'posts', list: [{id: 12, contents: '缓存'}] });
  return { page, app, requests, refreshes, toasts, flags(r,w) { read=r;write=w; }, owner(id) { owner=id; } };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
test('profile closed account removes posts tab, clears cache and refuses direct feed reads', () => {
  const h=mount('profile');h.page._syncCommunityProfile();
  assert.equal(h.page.data.activeTab,'about');assert.equal(h.page.data.memberTabs.some(t=>t.key==='posts'),false);
  assert.equal(h.page.data.list.length,0);h.page.getCreativesquareList();assert.equal(h.requests.length,0);
  h.page.onTabChange({detail:{key:'posts'}});assert.equal(h.page.data.activeTab,'about');
});
test('profile requested posts resumes after real flag read but not after user changes tab', async () => {
  const h=mount('profile');h.page._pendingCommunityTab=true;h.page._syncCommunityProfile();h.flags(true,true);h.refreshes[0]();await flush();
  assert.equal(h.page.data.activeTab,'posts');assert.equal(h.requests[0].url,'/api/creativesquare/list');
  const other=mount('profile');other.page._pendingCommunityTab=true;other.page._syncCommunityProfile();
  other.page.onTabChange({detail:{key:'about'}});other.flags(true,true);other.refreshes[0]();await flush();
  assert.equal(other.page.data.activeTab,'about');assert.equal(other.requests.length,0);
});
// 2026-10-29:广场列表页(pages/square/list)已删,square 侧的入口闸用例随页撤走,只留个人主页。
test('late feature completion cannot apply old account visibility to profile', async () => {
  for(const kind of ['profile']) {
    const h=mount(kind);const sync='_syncCommunityProfile';h.page[sync]();
    const snapshot=JSON.stringify(h.page.data);h.owner('99');h.flags(true,true);h.refreshes[0]();await flush();
    assert.equal(JSON.stringify(h.page.data),snapshot);assert.equal(h.requests.length,0);
  }
});
