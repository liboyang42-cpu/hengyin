'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { load, post, comment, tick, root } = require('../helpers/community-square-vm');

function thread() {
  return [
    comment(501, 1, { lifecycle: 'DELETED', deletedPlaceholder: true, body: '', root_id: null }),
    comment(503, 2, { lifecycle: 'PUBLISHED', body: '正常回复', root_id: 501, parent_id: 501 }),
    comment(504, 1, { lifecycle: 'PUBLISHED', body: '正常子回复', root_id: 501, parent_id: 503 }),
    comment(500, 2, { lifecycle: 'PUBLISHED', body: '另一个正常根', root_id: null }),
  ];
}

test('deleted ancestor WXML renders only its notice and keeps normal comment controls in the other branch', () => {
  const wxml = fs.readFileSync(path.join(root, 'pages/square/detail/index.wxml'), 'utf8');
  assert.match(wxml, /wx:for="\{\{ list \}\}" wx:key="id"/);
  assert.match(wxml, /<view[^>]*wx:if="\{\{item\.deletedPlaceholder === true \|\| item\.lifecycle === 'DELETED'\}\}"[^>]*>该评论已删除<\/view>\s*<view[^>]*wx:else[^>]*class="item flex-b"/);
  const placeholder = wxml.match(/<view[^>]*wx:if="\{\{item\.deletedPlaceholder[^>]*>([\s\S]*?)<\/view>/)[1];
  assert.equal(placeholder, '该评论已删除');
  assert.doesNotMatch(placeholder, /bindtap|contents|memberNickname|likeCount/);
});

test('deleted ancestor page readback preserves descendant IDs and hierarchy without adding the placeholder to the server count', async () => {
  const h = load('detail'); await h.ready(post(77, 1, { commentCount: 3 }), thread());
  assert.deepEqual(h.page.data.list.map(row => [row.id, row.rootId, row.parentId]),
    [[501, null, null], [503, 501, 501], [504, 501, 503], [500, null, null]]);
  assert.equal(h.page.data.list[0].deletedPlaceholder, true);
  assert.equal(h.page.data.list[0].contents, '');
  assert.equal(h.page.data.info.commentCount, 3);
  assert.equal(h.page.data.nodata, false);
});

for (const handler of ['msgClick', 'commentLikeClick', 'reportComment', 'deleteComment', 'commentFollowClick']) {
  test('deleted ancestor cannot dispatch ' + handler + ' even through a direct stale tap', async () => {
    const h = load('detail'); await h.ready(post(), thread());
    const count = h.requests.length;
    h.page[handler]({ currentTarget: { dataset: { index: 0, item: h.page.data.list[0] } } }); await tick();
    assert.equal(h.requests.length, count);
    assert.equal(h.modals.length, 0);
    assert.equal(h.page.data.popShow, false);
  });
}

test('reply selected before an ancestor becomes deleted keeps its draft but cannot submit to the placeholder', async () => {
  const h = load('detail'); await h.ready(post(), [comment()]);
  h.page.msgClick({ currentTarget: { dataset: { item: h.page.data.list[0] } } });
  h.page.setData({ replyContent: '保留未发送的回复', canReply: true });
  h.page.refreshComments(); await tick();
  await h.answer('/api/v1/community/posts/77/comments', { items: thread(), hasMore: false, nextCursor: 500 });
  const count = h.requests.length; h.page.addReplyComment(); await tick();
  assert.equal(h.requests.length, count);
  assert.equal(h.page.data.replyContent, '保留未发送的回复');
  assert.deepEqual(h.toasts, ['回复目标已变化，请重新选择']);
});

test('normal descendant keeps its own reply target and like endpoint after a deleted ancestor', async () => {
  const h = load('detail'); await h.ready(post(), thread());
  h.page.msgClick({ currentTarget: { dataset: { item: h.page.data.list[1] } } });
  assert.equal(h.page.data.replyId, 503);
  h.page.setData({ replyContent: '回复正常子评论', canReply: true });
  h.page.addReplyComment(); await tick();
  const reply = h.requests.find(request => request.method === 'POST' && request.url === '/api/v1/community/posts/77/comments');
  assert.equal(reply.data.parentId, 503);
  assert.equal(reply.data.body, '回复正常子评论');
  h.page.commentLikeClick({ currentTarget: { dataset: { index: 2 } } }); await tick();
  assert.equal(h.asked('/api/v1/community/comments/504/actions')[0].method, 'POST');
});
