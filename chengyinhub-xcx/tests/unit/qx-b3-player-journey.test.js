'use strict';

// qx-b3 玩家闭环 P1/P2/P4/P5/P7/P8/P9 + §23.4：只验证展示投影守住清单 §二/§十二/§24.3 的口径。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const v = require('../../subpackageJourney/utils/journey-view.js');

const ROOT = path.join(__dirname, '..', '..');

test('P7 未计分显示「本局不计分」，未知用时不显示 0，名次缺失不伪造', () => {
  const r = v.reportView({ runId: 7, topicId: 3, activityId: 0, scopeKind: 'RUN', runState: 'COMPLETED', completionKind: 'LEGITIMATE_COMPLETION',
    completedNodes: 4, totalNodes: 4, elapsedSeconds: null, score: { state: 'NOT_SCORED', score: null, penalties: null },
    rank: { scope: 'quest', scopeId: 3, qualified: false, rank: null, score: null }, nodes: [], rewardsState: 'UNAVAILABLE' });
  assert.equal(r.scoreText, '本局不计分');
  assert.equal(r.scored, false);
  assert.equal(r.elapsedText, '未知');
  assert.equal(r.rankText, '暂无名次');
  assert.equal(r.nodesText, '4/4');
  assert.equal(r.win, true);
  assert.equal(r.rewardsText, '');
});

test('P7 辅助完成与已结束分开；名次只用服务端 rank，口径为完成节点数', () => {
  const r = v.reportView({ runId: 9, topicId: 3, scopeKind: 'RUN', runState: 'COMPLETED', completionKind: 'ASSISTED_COMPLETION',
    completedNodes: 2, totalNodes: 0, elapsedSeconds: 3725, score: { state: 'SCORED', score: 410, penalties: 190 },
    rank: { scope: 'quest', scopeId: 3, qualified: true, rank: 2, score: 2 },
    nodes: [{ nodeId: 1, name: 'A', completionMode: 'SKIPPED', scored: true, score: 0 }, { nodeId: 2, name: 'B', scored: false, score: null }] });
  assert.equal(r.result, '辅助完成');
  assert.equal(r.win, false);
  assert.equal(r.nodesText, '2/?');
  assert.equal(r.elapsedText, '1:02:05');
  assert.equal(r.penaltyText, '-190');
  assert.equal(r.rankText, '第 2 名');
  assert.equal(r.rankSub, '完成节点数 2');
  assert.equal(r.nodes[0].mode, '已跳过');
  assert.equal(r.nodes[1].scoreText, '不计分');
  const ended = v.reportView({ runState: 'ENDED', completionKind: 'ENDED', scopeKind: 'RUN' });
  assert.equal(ended.result, '已结束');
  assert.equal(ended.finished, true);
  assert.equal(v.reportView({ runState: 'IN_PROGRESS', completionKind: 'IN_PROGRESS', scopeKind: 'LEGACY_TOPIC_SCOPE' }).legacy, true);
});

test('§23.4 榜：无名次不显示 0 分名次；作用域被改写时不冒充所选榜；城市/国家缺冻结码不出页签', () => {
  const rank = v.rankView({ scope: 'quest', scopeId: '3', period: 'week', periodStart: '2026-11-09 00:00:00', periodEnd: '2026-11-16 00:00:00',
    scoreBasis: 'COMPLETED_NODES', entries: [{ rank: 1, memberId: 5, nickname: '甲', score: 4 }, { rank: 1, memberId: 6, nickname: '乙', score: 4 }],
    me: { memberId: 9, score: 0, rank: null }, coverage: { unattributedRuns: 0 } }, { scope: 'quest', scopeId: '3', period: 'week' });
  assert.equal(rank.meText, '暂无名次');
  assert.equal(rank.me, null);
  assert.deepEqual(rank.entries.map(e => e.rank), [1, 1]);
  assert.equal(rank.basis, '完成节点数');
  assert.equal(rank.window, '2026.11.09 – 2026.11.15');
  assert.deepEqual(v.rankView({ scope: 'world', scopeId: null, period: 'all' }, { scope: 'city', scopeId: '310100', period: 'all' }), { mismatch: true });
  assert.deepEqual(v.rankScopes({ topicId: 3 }).map(t => t.key), ['quest', 'world']);
  assert.deepEqual(v.rankScopes({ topicId: 3, cityCode: '310100', countryCode: 'cn', clubId: 7, teamId: 8 }).map(t => t.key),
    ['quest', 'city', 'country', 'world', 'club', 'team']);
  assert.equal(v.rankScopes({ countryCode: 'cn' }).find(t => t.key === 'country').scopeId, 'CN');
  const city = v.rankView({ scope: 'city', scopeId: '310100', period: 'all', entries: [], me: null, coverage: { unattributedRuns: 3 } });
  assert.equal(city.unattributed, '3 局缺少冻结地理，未计入');
});

test('P9 队内：未完成任务的成员不补名次', () => {
  const team = v.teamRankView({ rankBasis: 'COMPLETED_TASK_COUNT', members: [
    { memberId: 1, name: '甲', doneTaskCount: 0, ranked: false, rank: null },
    { memberId: 2, name: '乙', doneTaskCount: 3, ranked: true, rank: 1, mine: true }] });
  assert.equal(team.entries[0].rank, 1);
  assert.equal(team.entries[1].rank, null);
  assert.equal(team.basis, '完成任务数');
  assert.equal(v.teamRankView({ members: [{ memberId: 1, ranked: false }] }).hasRank, false);
});

test('P8 回忆：无同意事实不展示同行私照', () => {
  const m = v.memoryView({ participantCount: 3, photoShareCapability: 'NO_PRIVATE_PHOTO_SHARE_CONSENT_FACT',
    moments: [{ nodeId: 1, name: 'A', picUrl: 'a.jpg' }, { nodeId: 2, name: 'B' }] });
  assert.equal(m.peerPhotos, false);
  assert.equal(m.photos.length, 1);
  assert.equal(m.participants, '3 人同行');
  assert.equal(v.memoryView({ participantCount: 1, moments: [] }).participants, '');
});

test('P4 模式：只列服务端给的模式；不可用给原因；声明了能力清单时缺能力不可选', () => {
  const c = v.choicesView({ configState: 'PUBLISHED', choices: [{ key: 'SHARED_TEAM', progressScope: 'PER_TEAM', hintScope: 'PER_MEMBER',
    scoreScope: 'PER_MEMBER', available: false, unavailableReason: 'TEAM_MODE_DISABLED', teamCapacity: { teamId: 12, joinedCount: 2, maxMembers: 4 } }],
    requiredCapabilities: [{ key: 'hangman' }] });
  assert.equal(c.modes.length, 1);
  assert.equal(c.modes[0].title, '协作共享');
  assert.equal(c.modes[0].available, false);
  assert.equal(c.modes[0].reason, '本主题未开启组队');
  assert.deepEqual(c.modes[0].rows.map(r => r.v), ['全队共享', '各自', '各自', '2/4']);
  assert.equal(c.teamId, '12');
  const ind = v.choicesView({ configState: 'FROZEN_RUN', choices: [{ key: 'INDEPENDENT', progressScope: 'PER_MEMBER', hintScope: 'PER_MEMBER', scoreScope: 'PER_MEMBER', available: true }],
    requiredCapabilities: [{ key: 'hangman' }] }, ['qa']);
  assert.equal(ind.frozen, true);
  assert.equal(ind.modes[0].available, false);
  assert.ok(ind.modes[0].reason);
  assert.equal(v.choicesView({ configState: 'RUN_CONFIG_SNAPSHOT_MISSING', choices: [] }).unavailable, true);
});

test('P5 席位：只有服务端声明可撤销才出撤销；释放不出；可邀请数未知不显示 0', () => {
  const s = v.seatsView({ registrationId: 5, seatCount: 3, freeSeats: 1, buyerIsCaller: true, readOnly: false, seatCancelSupported: true,
    seatReleaseSupported: true, seats: [{ seatNo: 1, state: 'BUYER' }, { seatNo: 2, state: 'INVITED', invitationId: 8, invitationExpiresAt: '2026-11-16 10:00:00' },
      { seatNo: 3, state: 'CLAIMED', invitationId: 9 }] });
  assert.equal(s.canInvite, true);
  assert.equal(s.seats[1].canCancel, true);
  assert.equal(s.seats[2].canCancel, false);
  assert.ok(!('canRelease' in s.seats[2]));
  assert.equal(s.seats[1].expires, '2026.11.16 10:00 前有效');
  const unknown = v.seatsView({ seatCount: 2, freeSeats: null, buyerIsCaller: true, readOnly: false, seatCancelSupported: false,
    seats: [{ seatNo: 2, state: 'INVITED', invitationId: 3 }] });
  assert.equal(unknown.freeText, '未知');
  assert.equal(unknown.canInvite, false);
  assert.equal(unknown.seats[0].canCancel, false);
});

test('P1 落地：普通推荐不承诺免费；礼品才写送一局；失效链接无权益', () => {
  assert.deepEqual(v.landingKind({ k: 'r', t: 'abc' }), { kind: 'referral', token: 'abc' });
  assert.equal(v.landingKind({ k: 'x', t: 'abc' }), null);
  assert.equal(v.landingKind({ k: 'r' }), null);
  const ref = v.referralView({ state: 'VALID', inviter: { nickname: '小城' }, target: { type: 1, id: 3, title: '主题' }, registrationState: 'UNKNOWN', nextAction: 'VIEW_TARGET', freeSelfPlay: true });
  assert.equal(ref.headline, '朋友邀请你一起玩');
  assert.ok(!/送|免费|优惠/.test(ref.headline));
  assert.equal(ref.canView, true);
  const bad = v.referralView({ state: 'TOKEN_EXPIRED' });
  assert.equal(bad.valid, false);
  assert.equal(bad.inviter, null);
  assert.equal(bad.stateText, '邀请链接已过期');
  const gift = v.giftView({ state: 'VALID', nextAction: 'CLAIM_GIFT', product: { productName: '探索票', topicId: 3 } });
  assert.equal(gift.headline, '朋友送你一局');
  assert.equal(gift.canClaim, true);
  const claimed = v.giftView({ state: 'CLAIMED', product: { productName: '探索票' } });
  assert.equal(claimed.canClaim, false);
  assert.equal(claimed.stateText, '礼物已被领取');
});

test('P2 推广中心：金额未知不补 0；多币种不合计；无提现通道不出提现', () => {
  const a = v.affiliateView({ allocations: {
    pending: { allocationCount: 2, netAmount: 12.5 },
    confirmed: { allocationCount: 0, netAmount: null },
    paid: { allocationCount: 1, amountUnavailableReason: 'MULTIPLE_CURRENCIES', netAmount: null },
    reversed: { allocationCount: 1, netAmount: -3 } },
    held: { orders: 2, amountFen: null }, payout: { available: false, reason: 'NO_PLAYER_PAYOUT_PATH', endpoint: null },
    campaigns: [{ campaignKey: 'c', targetId: 1, targetTitle: 'T', attributedOrders: 3, paidOrders: 1, refundedOrders: 0 }] });
  assert.deepEqual(a.buckets.map(b => b.amount), ['¥12.50', '未知', '多币种暂不合计', '¥-3.00']);
  assert.deepEqual(a.buckets.map(b => b.label), ['待确认', '已确认', '已标记付款', '已冲销']);
  assert.ok(!a.buckets.some(b => /到账|可提现/.test(b.label)));
  assert.equal(a.canPayout, false);
  assert.equal(a.held, '2 笔待核算');
  const none = v.affiliateView({ allocations: { pending: { allocationCount: 0, netAmount: 0 } }, payout: { available: true, endpoint: '/x' },
    credentials: [{ targetType: 1, targetId: 3, targetTitle: 'T', statusText: 'active', active: true }] });
  assert.equal(none.hasRecords, false);
  assert.equal(none.hasAllocations, false);
  assert.equal(none.canPayout, true);
  assert.equal(none.links[0].status, '有效');
});

test('页面与入口：分包注册、战报只读入口、席位释放按钮不存在', () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const sub = app.subPackages.find(s => s.root === 'subpackageJourney');
  assert.deepEqual(sub.pages, ['pages/report/index', 'pages/landing/index', 'pages/seats/index', 'pages/promo/index']);
  const seatsWxml = fs.readFileSync(path.join(ROOT, 'subpackageJourney/pages/seats/index.wxml'), 'utf8');
  assert.ok(!/释放|release/i.test(seatsWxml));
  const seatsJs = fs.readFileSync(path.join(ROOT, 'subpackageJourney/pages/seats/index.js'), 'utf8');
  assert.ok(!/\/release/.test(seatsJs));
  const reportJs = fs.readFileSync(path.join(ROOT, 'subpackageJourney/pages/report/index.js'), 'utf8');
  // 战报页不调用任何写口；分享时才签发推广凭据。
  assert.ok(!/seat-invitations|attribution\/claim|\/settle|\/complete/.test(reportJs));
  assert.match(fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8'), /bindtap="openRunReport"/);
  assert.match(fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/scene-member-order-detail/index.wxml'), 'utf8'), /bindtap="goSeats"/);
  const promoJs = fs.readFileSync(path.join(ROOT, 'subpackageJourney/pages/promo/index.js'), 'utf8');
  assert.ok(!/withdraw|referral\/token/.test(promoJs));
});
