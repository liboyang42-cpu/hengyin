// W10b #25:审核回执文案只翻译后端回执;没有送检记录或读不到时不说「通过」也不说「审核中」。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const review = require('../../utils/profile-review.js');

test('个人头像:待复核/通过/未通过按 live 分开说', () => {
  const r = (state, live) => ({ available: true, avatar: { versionNo: 3, state, live } });
  assert.match(review.memberAvatarText(r('PENDING', true)), /复核中/);
  assert.equal(review.memberAvatarText(r('PENDING', false)), '', '不是当前展示的那张就不说');
  assert.match(review.memberAvatarText(r('APPROVED', true)), /已通过/);
  assert.match(review.memberAvatarText(r('REJECTED', true)), /正在撤下/);
  assert.match(review.memberAvatarText(r('REJECTED', false)), /已撤下/);
});

test('没有送检记录、回执不可用、没提交过头像:一律不显示结论', () => {
  assert.equal(review.memberAvatarText({ available: true, avatar: { state: 'NOT_SUBMITTED', live: true } }), '');
  assert.equal(review.memberAvatarText({ available: false }), '');
  assert.equal(review.memberAvatarText({ available: true, avatar: null }), '');
  assert.equal(review.memberAvatarText(null), '');
});

test('门店形象:待审时明说玩家看不到;形象图复核单独一句', () => {
  const pending = review.merchantNpcText({ hasProfile: true, review: 'PENDING', playerVisible: false, avatarReview: 'NOT_REQUIRED' });
  assert.match(pending, /审核通过前玩家看不到/);
  assert.doesNotMatch(pending, /图片/);
  assert.match(review.merchantNpcText({ hasProfile: true, review: 'APPROVED', playerVisible: true, avatarReview: 'PENDING' }), /玩家现在能看到这个形象；形象图片安全复核中/);
  assert.match(review.merchantNpcText({ hasProfile: true, review: 'APPROVED', playerVisible: false }), /当前未启用/);
  assert.match(review.merchantNpcText({ hasProfile: true, review: 'REJECTED', avatarReview: 'REJECTED' }), /未通过审核.*请换一张/);
  assert.equal(review.merchantNpcText({ hasProfile: false }), '');
  assert.equal(review.merchantNpcText(undefined), '');
});

test('两页都只经回执渲染审核状态,不再把保存当上线', () => {
  const root = path.join(__dirname, '../..');
  const npc = fs.readFileSync(path.join(root, 'pages/merchant/decor/ai-npc/index.js'), 'utf8');
  assert.match(npc, /url: '\/api\/merchant\/npc\/review'/);
  assert.match(npc, /receipt && receipt\.review === 'APPROVED' \? '角色已更新' : '已提交审核'/);
  const geren = fs.readFileSync(path.join(root, 'pages/gerenziliao/gerenziliao.js'), 'utf8');
  assert.match(geren, /url: '\/api\/user\/profile\/review'/);
  assert.match(fs.readFileSync(path.join(root, 'pages/gerenziliao/gerenziliao.wxml'), 'utf8'), /wx:if="\{\{avatarReviewText\}\}"/);
  assert.match(fs.readFileSync(path.join(root, 'pages/merchant/decor/ai-npc/index.wxml'), 'utf8'), /wx:if="\{\{reviewText\}\}"/);
});
