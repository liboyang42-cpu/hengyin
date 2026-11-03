'use strict';
// 第七轮 C 第 3 项:分享卡片用真实名称与封面,不让微信截屏;队伍链接不带私有数据。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { ROAM_SHARE_COVER } = require('../../utils/roam-menu-share.js');

const root = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const plain = (v) => JSON.parse(JSON.stringify(v));

function loadDefinition(file, kind) {
  let definition;
  const localRequire = createRequire(path.join(root, file));
  const stub = new Proxy(function () {}, { get: (t, k) => (k === Symbol.toPrimitive ? () => '' : stub), apply: () => stub });
  const app = { globalData: { statusBarHeight: 20, navBarHeight: 44 }, getImgUrl: (v) => v, getUserID: () => '', sendRequest() {} };
  vm.runInNewContext(read(file), {
    getApp: () => app, wx: new Proxy({}, { get: () => () => '' }), console, setTimeout, clearTimeout, getCurrentPages: () => [],
    [kind || 'Page']: (def) => { definition = def; }, Behavior: (b) => b,
    require(name) { try { return localRequire(name); } catch (e) { return stub; } },
    module: {}, exports: {},
  }, { filename: file });
  return definition;
}

test('俱乐部:菜单与帖文转发带俱乐部封面(无封面取 Logo)', () => {
  const def = loadDefinition('pages/club/detail/index.js');
  const ctx = { data: { clubId: 9, club: { name: '夜行社', cover: 'https://img.example/c.jpg' } } };
  assert.deepEqual(plain(def.onShareAppMessage.call(ctx, {})), { title: '夜行社', path: '/pages/club/detail/index?id=9', imageUrl: 'https://img.example/c.jpg' });
  const post = def.onShareAppMessage.call({ data: { clubId: 9, club: { name: '夜行社', logo: 'https://img.example/l.jpg' } } }, { target: { dataset: { postId: 5 } } });
  assert.equal(post.imageUrl, 'https://img.example/l.jpg');
  assert.equal(post.path, '/pages/club/detail/index?id=9&postId=5');
});

test('玩法:带详情封面,落点不带 scope=my', () => {
  const def = loadDefinition('pages/templatedetail/templatedetail.js');
  assert.deepEqual(plain(def.onShareAppMessage.call({ data: { id: '12', scope: 'my', coverUrl: 'https://img.example/g.jpg', info: { title: '寻宝' } } })),
    { title: '寻宝', path: '/pages/templatedetail/templatedetail?id=12', imageUrl: 'https://img.example/g.jpg' });
});

test('节点:读正文组件已加载的店名与封面;没加载到用固定插画,不截屏', () => {
  const def = loadDefinition('subpackageRoam/poi-detail/index.js');
  const scene = { data: { poiId: '77', merchant: { displayName: '巷口咖啡', coverImage: 'https://img.example/p.jpg' }, node: { name: '节点A' }, gallery: [] } };
  const ctx = { data: { poiId: '77' }, selectComponent: () => ({ selectComponent: () => scene }) };
  ctx._shareSnapshot = def._shareSnapshot;
  assert.deepEqual(plain(def.onShareAppMessage.call(ctx)), { title: '巷口咖啡', path: '/subpackageRoam/poi-detail/index?poiId=77', imageUrl: 'https://img.example/p.jpg' });
  const empty = { data: { poiId: '77' }, selectComponent: () => null, _shareSnapshot: def._shareSnapshot };
  assert.deepEqual(plain(def.onShareAppMessage.call(empty)), { title: '这个节点等你到店打卡', path: '/subpackageRoam/poi-detail/index?poiId=77', imageUrl: ROAM_SHARE_COVER });
  const other = { data: { poiId: '78' }, selectComponent: () => ({ selectComponent: () => scene }), _shareSnapshot: def._shareSnapshot };
  assert.equal(def.onShareAppMessage.call(other).title, '这个节点等你到店打卡', '别的据点的数据不能串进来');
});

test('队伍:固定插画,链接只带邀请码', () => {
  const def = loadDefinition('pages/team/detail/index.js');
  const share = plain(def.onShareAppMessage.call({ data: { team: { id: 31, title: '夜行小队', inviteCode: 'abc', leaderMemberId: 9 } } }));
  assert.deepEqual(share, { title: '夜行小队', path: '/pages/team/join/index?code=abc', imageUrl: ROAM_SHARE_COVER });
  assert.doesNotMatch(share.path, /teamId|leader|member/i);
});

test('商家:旧壳与统一主页都用真实店名与封面,落 canonical 商家主页', () => {
  const shell = loadDefinition('pages/merchant/profile/index.js');
  assert.deepEqual(plain(shell.onShareAppMessage.call({ data: { memberId: '88', shareName: '巷口咖啡', shareCover: 'https://img.example/m.jpg' } })),
    { title: '巷口咖啡', path: '/pages/userinfo/userinfo?userId=88&tab=about', imageUrl: 'https://img.example/m.jpg' });
  assert.equal(shell.onShareAppMessage.call({ data: { memberId: '', shareName: '', shareCover: '' } }).title, '城瘾 · 商家主页');
  const comp = loadDefinition('subpackageMember/components/cy/profile/index.js', 'Component');
  const ctx = { data: { userId: '88', isSelf: false, userInfo: {}, subjectIsMerchant: true, subjectMerchant: { name: '巷口咖啡', coverImage: 'https://img.example/m.jpg' } } };
  ctx.getTimelinePayload = comp.methods.getTimelinePayload;
  assert.deepEqual(plain(comp.methods.getSharePayload.call(ctx, {})),
    { title: '巷口咖啡', path: '/pages/userinfo/userinfo?userId=88&tab=about', imageUrl: 'https://img.example/m.jpg' });
  const person = { data: { userId: '5', isSelf: false, userInfo: { nickname: '小李' }, subjectIsMerchant: false } };
  person.getTimelinePayload = comp.methods.getTimelinePayload;
  assert.equal(comp.methods.getSharePayload.call(person, {}).path, '/pages/userinfo/userinfo?userId=5');
});

test('首页:菜单转发固定插画', () => {
  const def = loadDefinition('pages/index/index.js');
  const share = def.onShareAppMessage.call({ data: {} }, { from: 'menu' });
  assert.equal(share.imageUrl, ROAM_SHARE_COVER);
  assert.equal(share.path, '/pages/index/index');
});
