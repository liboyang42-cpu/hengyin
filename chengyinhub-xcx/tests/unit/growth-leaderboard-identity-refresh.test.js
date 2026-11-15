const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const identitySync = require('../../utils/identity-sync');
const file = path.resolve(__dirname, '../../subpackageP3/pages/growthcenter/leaderboard/index.js');
function mount() {
  identitySync._resetForTest(); let page, user = '7'; const requests = [];
  const app = { globalData: {}, getUserID: () => user, sendRequest: r => requests.push(r) };
  global.getApp = () => app; global.wx = { stopPullDownRefresh() {} }; global.Page = d => { page = d; };
  delete require.cache[require.resolve(file)]; require(file);
  page.data = JSON.parse(JSON.stringify(page.data)); page.setData = patch => Object.assign(page.data, patch);
  page.onLoad({ metric: 'exp', period: 'week' }); page.onShow();
  return { page, requests, account(id) { user = id; }, answer(avatar) {
    const row = { rank: 1, memberId: 7, nickname: 'current', avatar, score: 8 };
    requests.at(-1).success({ code: 200, data: { list: [row], me: row } }); requests.at(-1).complete();
  } };
}
test('same-account identity change refreshes cached board on return using current selected metric and period', () => {
  const h = mount(); h.answer('OLD'); h.page.onHide();
  identitySync.publishIdentity('member', { avatar: 'NEW', nickname: 'new name' }); h.page.onShow();
  assert.equal(h.requests.length, 2);
  assert.deepEqual(h.requests[1].data, { metric: 'exp', period: 'week', limit: 50 });
  h.answer('NEW'); assert.equal(h.page.data.top3[0].avatarUrl, 'NEW'); assert.equal(h.page.data.me.avatarUrl, 'NEW');
  h.page.onHide(); h.page.onShow(); assert.equal(h.requests.length, 2, 'unchanged identity does not refetch on every show');
});
test('identity refresh rejects superseded response and server may clear the previous avatar', () => {
  const h = mount(); const old = h.requests[0]; h.page.onHide();
  identitySync.publishIdentity('member', { nickname: 'updated' }); h.page.onShow();
  h.answer(''); old.success({ code: 200, data: { list: [{ rank: 1, avatar: 'OLD' }], me: null } }); old.complete();
  assert.equal(h.page.data.top3[0].avatarUrl, ''); assert.equal(h.page.data.me.avatarUrl, '');
  h.page.onHide(); h.account('8'); h.page.onShow();
  assert.equal(h.page.data.top3.length, 0); assert.equal(h.page.data.me, null);
});
