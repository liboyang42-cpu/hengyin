'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');

const ROOT = path.join(__dirname, '..', '..');
const games = require(path.join(ROOT, 'subpackageMember/utils/external-game-view.js'));
const rewards = require(path.join(ROOT, 'subpackageMember/utils/noncash-reward-view.js'));

test('external game catalog page keeps valid rows and only follows a real cursor', () => {
  const page = games.normalizePage({
    items: [
      { gameId: 'city.quiz-one', name: '城市小测', version: '1.0.0', author: 'Ada', contentRating: '9+', packageBytes: 2048,
        permissions: ['storage', 'haptics'], approvedAt: '2026-11-13T08:00:00Z', downloadPath: '/api/x' },
      { gameId: '../evil', name: 'bad' },
      null,
    ],
    hasMore: true,
    nextCursor: '17',
  });
  assert.equal(page.rows.length, 1);
  assert.deepEqual(page.rows[0], {
    gameId: 'city.quiz-one', name: '城市小测', author: 'Ada', summary: '', version: '1.0.0', ratingText: '9+',
    sizeText: '2.0 KB', approvedText: page.rows[0].approvedText, permissionText: '本地存储、振动',
  });
  assert.equal(page.hasMore, true);
  assert.equal(page.nextCursor, '17');
  assert.equal(games.normalizePage({ items: [], hasMore: true, nextCursor: null }).hasMore, false);
  assert.equal(games.normalizePage({ rows: [] }), null);
});

test('external game rows de-duplicate across pages', () => {
  const a = { gameId: 'aaa' };
  const b = { gameId: 'bbb' };
  assert.deepEqual(games.appendRows([a], [a, b]).map(r => r.gameId), ['aaa', 'bbb']);
});

test('external game detail never offers to run the game in the mini program', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageMember/external-game-detail/index.wxml'), 'utf8');
  const js = fs.readFileSync(path.join(ROOT, 'subpackageMember/external-game-detail/index.js'), 'utf8');
  assert.equal(/<web-view/.test(wxml), false);
  assert.match(wxml, /在城瘾 App 中游玩/);
  assert.equal(/downloadPath/.test(wxml), false);
  assert.equal(/downloadFile|\.downloadPath/.test(js), false);
});

test('reward offer is claimable only without an errorCode (POLICY_CLOSED = switch off)', () => {
  const open = rewards.normalizeOffer({ versionId: 'v1', terms: '一杯咖啡', available: 3, redeemStart: 1763000000000, redeemEnd: 1764000000000 });
  assert.equal(open.claimable, true);
  assert.equal(open.blockText, '');
  const closed = rewards.normalizeOffer({ versionId: 'v1', available: 3, errorCode: 'POLICY_CLOSED' });
  assert.equal(closed.claimable, false);
  assert.equal(closed.blockText, '奖励领取暂未开放');
  assert.equal(rewards.normalizeOffer({ versionId: 'v1', errorCode: 'NO_AUTHORITATIVE_FACT' }).blockText, '完成这个主题后可领取');
  assert.equal(rewards.normalizeOffer({ versionId: 'v1', errorCode: 'SOMETHING_NEW' }).blockText, '暂不可领取');
  assert.equal(rewards.normalizeOffer({}), null);
});

test('claim outcome: award, unknown (recover later) or refused', () => {
  assert.deepEqual(rewards.claimOutcome({ awardId: 'a'.repeat(64), state: 'GRANTED' }), { kind: 'awarded', awardId: 'a'.repeat(64) });
  assert.deepEqual(rewards.claimOutcome({ requestId: 'r', state: 'UNKNOWN' }), { kind: 'unknown' });
  assert.equal(rewards.claimOutcome({ requestId: 'r', state: 'REFUSED', errorCode: 'STOCK_EXHAUSTED' }).text, '奖励已领完');
  assert.deepEqual(rewards.claimOutcome(null), { kind: 'unknown' });
});

test('reward page follows nextCursor and maps validity', () => {
  const page = rewards.normalizeRewardPage({
    items: [{ awardId: 'b'.repeat(64), rewardTitle: '咖啡', redemptionConditions: '到店出示', validFrom: 1763000000000,
      validUntil: 1764000000000, validityStatus: 'IN_WINDOW', awardedAt: 1763000000000 }, { nope: 1 }],
    nextCursor: 'b'.repeat(64),
  });
  assert.equal(page.rows.length, 1);
  assert.equal(page.rows[0].validityText, '可使用');
  assert.equal(page.hasMore, true);
  assert.equal(rewards.normalizeRewardPage({ items: [], nextCursor: null }).hasMore, false);
});

test('claim request ids are lowercase uuid v4 and ids are validated', () => {
  assert.match(rewards.newRequestId(), /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.equal(rewards.validTopicId('30'), true);
  assert.equal(rewards.validTopicId('0'), false);
  assert.equal(rewards.validTopicId('1 OR 1=1'), false);
});

test('new pages are registered in subpackageMember and do not touch payment', () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const member = app.subPackages.find(s => s.root === 'subpackageMember');
  for (const p of ['external-games/index', 'external-game-detail/index', 'rewards/index', 'reward-offer/index']) {
    assert.ok(member.pages.includes(p), p);
    const js = fs.readFileSync(path.join(ROOT, 'subpackageMember', p + '.js'), 'utf8');
    assert.equal(/requestPayment|\/pay\b|checkout/i.test(js), false, p);
  }
});
