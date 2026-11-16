'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const file = path.resolve(__dirname, '../../subpackageMember/components/cy/profile/index.js');

function mount(merchant = false) {
  const requests = [], navs = [];
  let definition;
  const app = { globalData: {}, getUserID: () => '990052', getAuthorization: () => 'synthetic-session',
    getUserRole: () => 'club', getUserType: () => 1, getImgUrl: value => value || '',
    canUseCommunity: () => false, sendRequest(options) { requests.push(options); } };
  global.getApp = () => app;
  global.wx = { getStorageSync: () => '', navigateTo: options => navs.push(options) };
  global.Component = value => { definition = value; };
  delete require.cache[require.resolve(file)];
  require(file);
  const component = Object.assign({}, definition.methods, { data: structuredClone(definition.data), _profileAttached: true });
  for (const [key, spec] of Object.entries(definition.properties)) component.data[key] = spec.value;
  Object.assign(component.data, { isSelf: true, hostSelf: true, userId: '990052', isMerchantView: merchant });
  if (merchant) Object.assign(component.data, { merchantAccessState: 'ready', merchantAccess: { active: true, canManageCoop: true } });
  component.setData = patch => Object.assign(component.data, patch);
  return { component, requests, navs };
}

async function read(rows, merchant = false) {
  const h = mount(merchant);
  const pending = h.component.loadProjectPreview();
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].url, merchant ? '/api/merchant/chapter-application/mine' : '/api/topic/list');
  if (!merchant) assert.equal(h.requests[0].data.is_my, 1);
  h.requests[0].success({ code: 200, data: merchant ? rows : { rows, total: rows.length } });
  await pending;
  return h;
}

test('production read → normalized draft card → tap resumes the same draft editor, with the existing double-tap guard', async () => {
  const h = await read([{ id: 9915907, isDraft: true, status: 0, userStatus: 0, name: 'Synthetic draft' }]);
  const row = h.component.data.projectPreviewList[0];
  assert.equal(row.statusText, '草稿');
  assert.equal(row.state, 'draft');
  const event = { currentTarget: { dataset: { id: row.id } } };
  h.component.onProjectPreviewTap(event); h.component.onProjectPreviewTap(event);
  assert.equal(h.navs.length, 1);
  assert.equal(h.navs[0].url, '/pages/publish/fabu/index?id=9915907');
  h.navs[0].complete(); h.component.onProjectPreviewTap(event);
  assert.equal(h.navs.length, 2);
});

for (const [fields, expectedState, expectedRoute] of [
  [{ status: 2 }, 'rejected', '/pages/publish/fabu/index?id=8'],
  [{ status: 0, isDraft: false }, 'pending', '/pages/topic/index/index?id=8'],
  [{ status: 0 }, 'pending', '/pages/topic/index/index?id=8'],
  [{ status: 1, userStatus: 1 }, '', '/pages/topic/index/index?id=8'],
  [{ status: 1, userStatus: 0 }, 'offline', '/pages/topic/index/index?id=8'],
]) test('project state routing preserves the existing non-draft and rejected contract: ' + expectedState, async () => {
  const h = await read([Object.assign({ id: 8 }, fields)]);
  assert.equal(h.component.data.projectPreviewList[0].state, expectedState);
  h.component.onProjectPreviewTap({ currentTarget: { dataset: { id: '8' } } });
  assert.equal(h.navs[0].url, expectedRoute);
});

test('merchant undertaking cards keep their read-only topic destination', async () => {
  const h = await read([{ topicId: 8, status: 0, topicName: 'Synthetic undertaking' }], true);
  h.component.onProjectPreviewTap({ currentTarget: { dataset: { id: 8 } } });
  assert.equal(h.navs[0].url, '/pages/topic/index/index?id=8');
});

test('unknown or absent current card cannot infer draft privilege from its id; absent id opens management', () => {
  const h = mount();
  h.component.onProjectPreviewTap({ currentTarget: { dataset: { id: 9915907 } } });
  assert.equal(h.navs[0].url, '/pages/topic/index/index?id=9915907');
  h.navs[0].complete();
  h.component.onProjectPreviewTap({ currentTarget: { dataset: {} } });
  assert.equal(h.navs[1].url, '/subpackageA/pages/myproject/index');
});
