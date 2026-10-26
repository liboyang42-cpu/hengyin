const test = require('node:test');
const assert = require('node:assert/strict');
const { decimalId: positiveId, positiveId: legacyPositiveId, parseReviewRouteOptions, shapePage } = require('../../pages/merchant/reviews/view-model');
test('decimal Long boundary, safe-number compatibility and invalid input rejection', () => {
  for (const id of ['1', '9007199254740991', '9007199254740992', '9007199254740993', '9223372036854775807']) {
    assert.equal(positiveId(id), id); assert.equal(parseReviewRouteOptions({ merchantRowId: id }).merchantRowId, id);
  }
  assert.equal(positiveId(7), '7');
  for (const id of [9007199254740992, 9223372036854775807, '9223372036854775808', '0', '-1', '01', '1e3', ' 7 ', {}, true, null]) assert.equal(positiveId(id), null);
});
test('legacy shared finance and registration parser retains previous numeric contract', () => {
  assert.equal(legacyPositiveId('1'), 1); assert.equal(legacyPositiveId(' 02 '), 2);
  assert.equal(legacyPositiveId('9007199254740993'), null);
});
test('list rejects already-rounded numeric IDs and accepts exact strings', () => {
  const payload = id => ({ mode: 'manage', pageNum: 1, pageSize: 20, total: 1, hasMore: false,
    items: [{ id, version: 1, rating: 5, imageUrls: [], status: 'VISIBLE', verifiedRedemption: true, canReply: true, canReport: true }] });
  assert.equal(shapePage(payload(9007199254740992), 'manage'), null);
  assert.equal(shapePage(payload('9223372036854775808'), 'manage'), null);
  assert.equal(shapePage(payload('9007199254740993'), 'manage').items[0].id, '9007199254740993');
});
