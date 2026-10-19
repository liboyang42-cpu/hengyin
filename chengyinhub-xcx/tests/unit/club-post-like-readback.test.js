const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../pages/club/detail/index.js'), 'utf8');
function method(name, args) {
  const start = source.indexOf('  ' + name + '(');
  const end = source.indexOf('\n  },', start);
  return new Function(...Object.keys(args), 'return function' + source.slice(start + 2 + name.length, end) + '\n}')( ...Object.values(args));
}
test('动态点赞连点及丢响应后只读原动态，不重放 toggle', () => {
  const requests = [];
  let reads = 0;
  const onLikePost = method('onLikePost', { app: { sendRequest: r => requests.push(r) }, jsonBody: JSON.stringify, jsonHeader: () => ({}), cyToast() {} });
  const page = { loadPosts() { reads++; } };
  const event = { detail: { postId: 7 } };
  onLikePost.call(page, event);
  onLikePost.call(page, event);
  assert.equal(requests.length, 1);
  requests[0].fail();
  assert.equal(page._postLikeRequests['7'], 'unknown');
  onLikePost.call(page, event);
  assert.equal(requests.length, 1);
  assert.equal(reads, 2);
});
test('只有动态列表真实布尔关系才能释放未知锁', () => {
  const requests = [];
  const page = { data: { clubId: 3 }, _postLikeRequests: { '7': 'unknown' }, setData() {}, scrollToRequestedPost() {} };
  const loadPosts = method('loadPosts', { app: { sendRequest: r => requests.push(r) }, isDemoClubId: () => false, jsonBody: JSON.stringify, jsonHeader: () => ({}), normalizeClubPost: p => p });
  loadPosts.call(page);
  requests[0].success({ code: 200, data: [{ id: 7 }] });
  assert.equal(page._postLikeRequests['7'], 'unknown');
  requests[0].success({ code: 200, data: [{ id: 7, isLiked: false }] });
  assert.equal(page._postLikeRequests['7'], undefined);
});
