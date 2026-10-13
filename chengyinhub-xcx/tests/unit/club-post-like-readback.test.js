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
test('explicit post target and lost-response recovery never send a second toggle', () => {
  const requests = [];
  let reads = 0, owner = '1';
  const app = { getUserID: () => owner, sendRequest: r => requests.push(r) };
  const bindings = { app, jsonBody: JSON.stringify, jsonHeader: () => ({}), cyToast() {} };
  const page = { data: { posts: [{ id: 7, isLiked: false }] }, setData: value => Object.assign(page.data, value), loadPosts() { reads++; } };
  page.recoverPostLike = method('recoverPostLike', bindings);
  page.onLikePost = method('onLikePost', bindings);
  const event = { detail: { postId: 7 } };
  page.onLikePost(event); page.onLikePost(event);
  assert.equal(requests.length, 1);
  assert.deepEqual(JSON.parse(requests[0].data), { postId: 7, target: 1 });
  requests[0].fail(); page.onLikePost(event);
  assert.equal(requests.filter(r => r.url === '/api/club/post/like').length, 1);
  const lookup = requests.at(-1);
  lookup.success({ code: 200, data: { postId: 7, liked: 'true' } });
  assert.equal(page._postLikeRequests['7'], 'unknown');
  owner = '2';
  lookup.success({ code: 200, data: { postId: 7, liked: true } });
  assert.equal(page.data.posts[0].isLiked, false, 'late read cannot cross identities');
  owner = '1';
  lookup.success({ code: 200, data: { postId: 7, liked: true } });
  assert.equal(page._postLikeRequests['7'], undefined);
  assert.equal(page.data.posts[0].isLiked, true);
  assert.equal(reads, 1);
});
