'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { load, post, comment } = require('../helpers/community-square-vm');
test('real detail reply binds exact Long parent ID at the wire boundary', async () => {
  const h = load('detail'); await h.ready(post(), [comment('9007199254740993')]);
  h.page.setData({ replyId: '9007199254740993', replyContent: 'reply' }); h.page.addReplyComment();
  assert.equal(h.asked('/api/v1/community/posts/77/comments').at(-1).data.parentId, '9007199254740993');
});
for (const transition of ['account', 'credential']) test('detail clears previous private projection and draft on ' + transition, async () => {
  const h = load('detail'); let credential = 'session-a'; h.app.getAuthorization = () => credential;
  await h.ready(post(), [comment()]);
  h.page.setData({ commentContent: 'private draft', replyContent: 'private reply', replyId: 501, composeShow: true, composeEdit: { contents: 'private' } });
  if (transition === 'account') h.changeOwner(); else credential = 'session-b';
  h.page.onShow();
  assert.equal(Object.keys(h.page.data.info).length, 0);
  assert.equal(h.page.data.list.length, 0); assert.equal(h.page.data.commentContent, '');
  assert.equal(h.page.data.replyContent, ''); assert.equal(h.page.data.composeEdit, null);
  assert.equal(h.page.data.composeShow, false);
  assert.ok(h.asked('/api/v1/community/posts/77').length >= 2);
});
for (const kind of ['comment', 'reply']) test('late ' + kind + ' write after unload does not mutate Page or refresh', async () => {
  const h = load('detail'); await h.ready();
  h.page.setData({ commentContent: 'comment', replyContent: 'reply', replyId: 501 });
  if (kind === 'comment') h.page.addComment(); else h.page.addReplyComment();
  const write = h.asked('/api/v1/community/posts/77/comments').at(-1);
  h.page.onUnload(); const before = h.patches.length, count = h.requests.length;
  write.success({ code: 200, data: {} }); write.complete();
  assert.equal(h.patches.length, before); assert.equal(h.requests.length, count);
});
test('real feed cannot resurrect a deleted post from a pre-delete response', async () => {
  const h = load('list'); h.page.data.userId = 1; h.page.getList();
  await h.answer('/api/v1/community/feeds/LATEST', { items: [post()], hasMore: false });
  h.page.getList(1, true); const old = h.asked('/api/v1/community/feeds/LATEST').at(-1);
  h.page.deleteCurrentSquarePost('77'); await h.answer('/api/v1/community/posts/77', {});
  old.success({ code: 200, data: { items: [post()], hasMore: false } });
  assert.equal(h.page.data.list.length, 0);
});
test('real Page restores and retries an unknown comment after a cold client without changing its input or key', async () => {
  const a = load('detail'); await a.ready();
  a.page.setData({ commentContent: 'original' }); a.page.addComment();
  const sent = a.asked('/api/v1/community/posts/77/comments').at(-1);
  sent.fail({ errMsg: 'timeout' }); sent.complete();
  assert.equal(a.page.data.commentRecoveryPending, true);
  const b = load('detail');
  for (const method of ['getStorageSync','setStorageSync','removeStorageSync']) b.wx[method] = a.wx[method];
  await b.ready();
  assert.equal(b.page.data.commentContent, 'original'); assert.equal(b.page.data.commentRecoveryPending, true);
  b.page.onCommentInput({ detail: { value: 'changed' } });
  assert.equal(b.page.data.commentContent, 'original');
  b.page.retryCommentSubmit();
  const retry = b.asked('/api/v1/community/posts/77/comments').at(-1);
  assert.equal(retry.data.clientRequestId, sent.data.clientRequestId);
  retry.success({ code: 200, data: {} }); retry.complete();
  assert.equal(b.page.data.commentRecoveryPending, false); assert.equal(b.page.data.commentContent, '');
});
test('a hidden parent blocks a new reply but does not prevent original UNKNOWN reply recovery', async () => {
  const h = load('detail'); await h.ready();
  h.page.setData({ replyId: 501, replyContent: 'original reply' }); h.page.addReplyComment();
  const original = h.asked('/api/v1/community/posts/77/comments').at(-1);
  original.fail({}); original.complete();
  h.page.setData({ list: [{ id: 501, deletedPlaceholder: true, lifecycle: 'DELETED' }] });
  h.page.retryReplySubmit();
  const retry = h.asked('/api/v1/community/posts/77/comments').at(-1);
  assert.notEqual(retry, original); assert.equal(retry.data.clientRequestId, original.data.clientRequestId);
  retry.success({ code: 200, data: {} }); retry.complete();
  h.page.setData({ replyId: 501, replyContent: 'new reply', list: [{ id: 501, deletedPlaceholder: true }] });
  const count = h.requests.length; h.page.addReplyComment(); assert.equal(h.requests.length, count);
});
