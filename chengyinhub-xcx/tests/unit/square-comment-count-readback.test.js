const test = require('node:test');
const assert = require('node:assert/strict');
const { load, post } = require('../helpers/community-square-vm');

test('confirmed original comment replay refreshes authoritative count without optimistic increment', async () => {
  const h = load('detail'); await h.ready(post(77, 1, { commentCount: 0 }), []);
  h.page.setData({ commentContent: 'original', canComment: true }); h.page.addComment();
  const command = h.asked('/api/v1/community/posts/77/comments').at(-1); const key = command.data.clientRequestId;
  command.fail({ errMsg: 'timeout' }); command.complete(); await new Promise(r => setImmediate(r));
  h.page.addComment(); const retry = h.asked('/api/v1/community/posts/77/comments').at(-1);
  assert.equal(retry.data.clientRequestId, key); retry.success({ code: 200, data: {} }); await new Promise(r => setImmediate(r));
  assert.equal(h.page.data.info.commentCount, 0, 'No local +1 on an idempotent replay');
  await h.answer('/api/v1/community/posts/77', post(77, 1, { commentCount: 1 }));
  assert.equal(h.page.data.info.commentCount, 1);
  h.page.refreshCommentCount(); await h.answer('/api/v1/community/posts/77', post(77, 1, { commentCount: 1 }));
  assert.equal(h.page.data.info.commentCount, 1, 'Repeated read cannot inflate count');
});
test('late count read cannot overwrite another account or post', async () => {
  const h = load('detail'); await h.ready(post(), []); h.page.refreshCommentCount(); const r = h.asked('/api/v1/community/posts/77').at(-1);
  h.changeOwner(); r.success({ code: 200, data: post(77, 1, { commentCount: 99 }) }); await new Promise(r => setImmediate(r));
  assert.notEqual(h.page.data.info.commentCount, 99);
});

test('failed count read preserves confirmed comment and explains refresh without resending', async () => {
  for (const failure of ['network', 'business', 'invalid']) {
    const h = load('detail'); await h.ready(post(77, 1, { commentCount: 0 }), []);
    h.page.setData({ commentContent: 'confirmed', canComment: true }); h.page.addComment();
    const command = h.asked('/api/v1/community/posts/77/comments').at(-1);
    command.success({ code: 200, data: {} }); await new Promise(r => setImmediate(r));
    const read = h.asked('/api/v1/community/posts/77').at(-1);
    if (failure === 'network') read.fail({ errMsg: 'timeout' });
    else read.success(failure === 'business' ? { code: 503 } : { code: 200, data: post(77, 1, { commentCount: -1 }) });
    await new Promise(r => setImmediate(r));
    assert.equal(h.page.data.info.commentCount, 0);
    assert.equal(h.asked('/api/v1/community/posts/77/comments').filter(r => r.method === 'POST').length, 1);
    assert.ok(h.toasts.some(t => String(t).includes('评论已发送，计数请刷新查看')));
  }
});
