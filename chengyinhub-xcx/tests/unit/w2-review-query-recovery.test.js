const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const viewModel = require('../../pages/merchant/reviews/view-model.js');

function harness(relative) {
  let page, owner = '1';
  const requests = [], modals = [];
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../..', relative), 'utf8'), {
    Page: value => { page = value; }, wx: {},
    getApp: () => ({ globalData: {}, getUserID: () => owner, sendRequest: request => requests.push(request), getRequestErrorMessage: (_r, text) => text }),
    require: name => name === './view-model.js' ? viewModel
      : name.includes('/toast') ? () => {} : name.includes('/modal') ? { show: value => modals.push(value) } : {},
  });
  page.data = JSON.parse(JSON.stringify(page.data));
  page.setData = value => Object.assign(page.data, value);
  return { page, requests, modals, switchOwner(value) { owner = value; } };
}
function response(filter, id) {
  return { code: 200, data: { filter, mode: 'manage', pageNum: 1, pageSize: 20, total: id ? 1 : 0, hasMore: false,
    items: id ? [{ id, version: 0, rating: 2, status: 'VISIBLE', imageUrls: [], verifiedRedemption: true, canReply: true, canReport: true }] : [] } };
}

test('review filters start a new server query and late old-filter responses cannot overwrite it', () => {
  const { page, requests } = harness('pages/merchant/reviews/index.js');
  page.data.mode = 'manage';
  page.loadReviews(false);
  page.setReviewFilter({ currentTarget: { dataset: { filter: 'low' } } });
  assert.match(requests[1].url, /pageNum=1&pageSize=20&filter=low/);
  requests[1].success(response('low', 21));
  requests[0].success(response('all', 1));
  assert.equal(page.data.filteredItems[0].id, 21);
  assert.equal(page.data.total, 1);
  page.setReviewFilter({ currentTarget: { dataset: { filter: 'photo' } } });
  requests[2].success(response('photo', null));
  assert.equal(page.data.pageState, 'ready', 'empty filtered result must keep filter controls reachable');
  assert.equal(page.data.total, 0);
});

test('a backend that does not confirm the selected filter or a late different-account response fails closed', () => {
  const { page, requests, switchOwner } = harness('pages/merchant/reviews/index.js');
  page.data.mode = 'manage'; page.data.activeFilter = 'low';
  page.loadReviews(false);
  const unsupported = response('low', 21); delete unsupported.data.filter;
  requests[0].success(unsupported);
  assert.equal(page.data.pageState, 'error');
  page.loadReviews(false); switchOwner('2');
  requests[1].success(response('low', 21));
  assert.equal(page.data.items.length, 0);
});

test('cooperation review read failure stays unknown and cannot authorize another write', () => {
  const { page, requests } = harness('pages/coop/invite-detail/index.js');
  page.data.invite = { inviteId: 7, fromId: 2, toId: 1, topicId: 3, status: 1 };
  page.loadMyReview(page.data.invite);
  requests[0].fail();
  assert.equal(page.data.reviewReadState, 'unknown');
  page.reviewCoop();
  assert.equal(page.data.reviewSheetShow, false);
  requests[1].success({ code: 200, data: { reviews: [] } });
  page.reviewCoop();
  assert.equal(page.data.reviewSheetShow, true);
});

test('cooperation review cannot cross identities and a lost write response must read the original review', () => {
  const { page, requests, modals, switchOwner } = harness('pages/coop/invite-detail/index.js');
  page.data.invite = { inviteId: 7, fromId: 2, toId: 1, topicId: 3, status: 1 };
  page.loadMyReview(page.data.invite);
  switchOwner('9');
  requests[0].success({ code: 200, data: { reviews: [{ fromId: 1, topicId: 3, rating: 5 }] } });
  assert.equal(page.data.myReviewStars, '');
  switchOwner('1'); requests[0].success({ code: 200, data: { reviews: [] } });
  page.reviewCoop(); page.onReviewSheetSelect({ detail: { index: 0 } });
  modals[0].success({ confirm: true, content: '合作意见' });
  page.reviewCoop();
  assert.equal(requests.filter(r => r.url === '/api/coop/review/save').length, 1);
  requests[1].fail();
  assert.equal(requests[2].url, '/api/coop/review/summary');
  requests[2].success({ code: 200, data: { reviews: [{ fromId: 1, topicId: 3, rating: 5 }] } });
  page.reviewCoop();
  assert.equal(page.data.myReviewStars, '★★★★★');
  assert.equal(requests.filter(r => r.url === '/api/coop/review/save').length, 1);
});
