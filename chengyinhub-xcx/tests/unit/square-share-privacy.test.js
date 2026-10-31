'use strict';
// U-050:广场帖转发卡只给公开且已发布的帖子带正文摘要/封面/详情链接,其余退回发现页帖文 tab 的通用卡。
const test = require('node:test');
const assert = require('node:assert/strict');
const { buildSquareShare } = require('../../pages/square/utils/square-share.js');

const GENERIC = { title: '城瘾Hub', path: '/pages/talent/list/index?tab=feed' };

test('public published post shares its own excerpt, cover and detail path', () => {
  const card = buildSquareShare({ id: 56, audience: 'PUBLIC', status: 1,
    contents: '今天在  西湖边走完了整条路线，风很大但是值得，推荐大家周末来试试', pics: 'https://cdn.example/a.jpg;https://cdn.example/b.jpg' });
  assert.equal(card.path, '/pages/square/detail/index?id=56');
  assert.equal(card.title, '今天在 西湖边走完了整条路线，风很大但是值得，推荐大家周…');
  assert.equal(card.imageUrl, 'https://cdn.example/a.jpg');
});

test('non-public or unpublished posts fall back to the generic card without text or image', () => {
  for (const post of [
    { id: 1, audience: 'PRIVATE', status: 1, contents: '私密正文', pics: 'https://cdn.example/p.jpg' },
    { id: 2, audience: 'FOLLOWERS', status: 1, contents: '仅关注者' },
    { id: 3, audience: 'COMMUNITY', status: 1, contents: '社区可见' },
    { id: 4, audience: 'PUBLIC', status: 0, contents: '审核中' },
    { id: 5, audience: 'PUBLIC', status: 'REMOVED', contents: '已下架' },
    undefined,
  ]) assert.deepEqual(buildSquareShare(post), GENERIC);
});

test('legacy rows without audience are public; missing text or relative image are not invented', () => {
  assert.deepEqual(buildSquareShare({ id: 9, status: 1, contents: '', picList: ['/profile/x.jpg'] }),
    { title: '城瘾Hub', path: '/pages/square/detail/index?id=9' });
});
