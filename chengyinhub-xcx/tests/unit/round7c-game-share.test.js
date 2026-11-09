'use strict';
// 第七轮 C 第 2 项:游玩页菜单转发不再截屏(位置/谜面)、不再发空 activityId;「我的参与」私人页不转发。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { playMenuShare } = require('../../utils/play-menu-share.js');
const { ROAM_SHARE_COVER } = require('../../utils/roam-menu-share.js');

const root = path.resolve(__dirname, '../..');

test('有主题:落到公开主题详情,带主题名与主题封面', () => {
  assert.deepEqual(playMenuShare({ topicId: 42, topicName: '老城夜行', topicCover: 'https://img.example/c.jpg', activityId: '' }),
    { title: '老城夜行', path: '/pages/topic/index/index?id=42', imageUrl: 'https://img.example/c.jpg' });
});

test('自玩会话(activityId 为空)不再拼出 activityId= 空串;没有封面用固定插画', () => {
  const share = playMenuShare({ topicId: '7', topicName: '', topicCover: '', activityId: '' });
  assert.equal(share.path, '/pages/topic/index/index?id=7');
  assert.equal(share.imageUrl, ROAM_SHARE_COVER);
  assert.doesNotMatch(share.path, /activityId=/);
});

test('mock / 非法主题 id 落首页,仍带固定封面', () => {
  for (const topicId of ['mock-free-explore', '', null, 0, '-3']) {
    assert.deepEqual(playMenuShare({ topicId }), { title: '来城瘾，走一段城市探索', path: '/pages/index/index', imageUrl: ROAM_SHARE_COVER });
  }
});

test('游玩页 onShareAppMessage 走 playMenuShare,每条返回都有 imageUrl;固定插画在仓内', () => {
  const src = fs.readFileSync(path.join(root, 'pages/play/index.js'), 'utf8');
  assert.match(src, /onShareAppMessage\(event\) \{\n\s*return activityShareFromEvent\(event\) \|\| playMenuShare\(this\.data\);/);
  assert.doesNotMatch(src, /path: '\/pages\/play\/index\?activityId=' \+ this\.data\.activityId/);
  assert.ok(fs.existsSync(path.join(root, ROAM_SHARE_COVER.slice(1))));
});

test('「我的参与」不再定义转发', () => {
  const src = fs.readFileSync(path.join(root, 'subpackageMember/mycanyu/mycanyu.js'), 'utf8');
  assert.doesNotMatch(src, /onShareAppMessage\s*\(/);
});
