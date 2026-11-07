// 走查修复 l4-counter:M-02 / M-05 / M-06 / M-23 / M-25 柜台扫码结果。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { resolveVerificationResult, classifyVerificationOutcome } = require('../../utils/merchant-verification');

const DYN = { url: '/api/registration/scan_dynamic_code', data: { code: 'v1.1.activity.1.1.sig' } };
const CHAPTER = { url: '/api/registration/scan_qr_code_chapter', data: { code: 'v1.1.activity.1.1.sig', chapterId: 45 } };

test('M-02 服务端明确标记 notRedeemed 的拒绝判为失败，不再是待确认', () => {
  for (const msg of ['签名不匹配', '已过期', '您在本路线没有可核销权益或有效核销回执']) {
    const res = { code: 500, msg, data: { notRedeemed: true } };
    assert.equal(classifyVerificationOutcome(res, 'success', undefined, DYN).status, 'failed', msg);
    assert.equal(classifyVerificationOutcome(res, 'success', undefined, CHAPTER).status, 'failed', msg);
  }
});

test('M-02 没有标记的业务500（可能事务已提交）仍是待确认', () => {
  for (const res of [{ code: 500, msg: '名额已用完' }, { code: 500, msg: '签名不匹配', data: {} },
    { code: 500, msg: 'x', data: { notRedeemed: 'true' } }, { code: 500, data: { notRedeemed: true } }]) {
    assert.equal(classifyVerificationOutcome(res, 'success', undefined, DYN).status, 'unknown', JSON.stringify(res));
  }
  // 非核销端点即使带了标记也不认
  assert.equal(classifyVerificationOutcome({ code: 500, msg: '已过期', data: { notRedeemed: true } }, 'success', undefined,
    { url: '/api/other' }).status, 'unknown');
});

test('M-02 未核销显示红色「未核销」与能对顾客说的原因', () => {
  const forged = resolveVerificationResult({ code: 500, msg: '签名不匹配', data: { notRedeemed: true } }, '验票');
  assert.deepEqual(forged, { state: 'rejected', title: '未核销', message: '这不是有效的核销码，请让玩家重新打开核销码', show: true });
  assert.equal(resolveVerificationResult({ code: 500, msg: '已过期', data: { notRedeemed: true } }).message, '核销码已过期，请让玩家刷新后重新出示');
  assert.equal(resolveVerificationResult({ code: 500, msg: ['参数', '错误'].join(''), data: { notRedeemed: true } }).message, '这不是有效的核销码，请让玩家重新打开核销码');
  assert.equal(resolveVerificationResult({ code: 500, msg: '您在本路线没有可核销权益或有效核销回执', data: { notRedeemed: true } }).message, '本店没有这张票的权益，无需提供');
  assert.equal(resolveVerificationResult({ code: 500, msg: '订单未完成支付或已退款,无法核销', data: { notRedeemed: true } }).message, '订单未完成支付或已退款,无法核销');
});

test('M-05 成功回执写出站点与要提供的权益', () => {
  const r = resolveVerificationResult({ code: 200, msg: '核销成功', data: { redemptionId: 1, chapterId: 45, chapterName: '第四站·一页手写的回信', perkName: '手冲一杯 + 日期章明信片' } }, '验票');
  assert.equal(r.state, 'success');
  assert.equal(r.title, '核销成功');
  assert.equal(r.stop, '第四站·一页手写的回信');
  assert.equal(r.perk, '手冲一杯 + 日期章明信片');
  assert.equal(r.message, '');
  assert.equal(r.warn, undefined);
  const xp = resolveVerificationResult({ code: 200, msg: '核销成功', data: { chapterName: 'A', completionEarnedXp: 36 } });
  assert.equal(xp.message, '玩家通关 +36 探索值已到账');
});

test('M-06 他日场次照常成功，并提示场次日期', () => {
  const r = resolveVerificationResult({ code: 200, msg: '核销成功', data: { chapterName: 'A', sessionDate: '11月12日', sessionNotToday: true } });
  assert.equal(r.state, 'success');
  assert.equal(r.warn, '这张票是 11月12日 的场次');
});

const read = f => fs.readFileSync(path.join(__dirname, '../../', f), 'utf8');

test('M-05/M-23 结果面板用现成的 cy-result-sheet，无「操作结果」标题栏，主按钮「继续扫码」', () => {
  const wxml = read('pages/merchant/index/index.wxml');
  const sheet = wxml.slice(wxml.indexOf("'merchant-verification-result'"), wxml.indexOf('<tabBar'));
  assert.match(sheet, /<cy-result-sheet /);
  assert.doesNotMatch(sheet, /cy-scene-sheet|verify-result/);
  assert.doesNotMatch(sheet, /sceneCurrent\.title/);
  for (const k of ['kind', 'title', 'sub', 'meta', 'pill', 'why']) assert.match(sheet, new RegExp(k + '="\\{\\{verificationResult\\.' + k + '\\}\\}"'));
  assert.match(sheet, /primary-text="\{\{merchantAccess\.canVerify \? '继续扫码' : '完成'\}\}"/);
  assert.match(sheet, /bind:primary="onVerificationPrimary"/);
  assert.match(sheet, /bind:secondary="closeVerificationResult"/);
  assert.match(sheet, /bind:close="closeVerificationResult"/);
  assert.match(sheet, /duration="\{\{0\}\}"/);
  const json = JSON.parse(read('pages/merchant/index/index.json'));
  assert.equal(json.usingComponents['cy-result-sheet'], '/components/cy/result-sheet/index');
  assert.doesNotMatch(read('pages/merchant/index/index.wxss'), /\.verify-result/);
});

test('M-05/M-06 面板内容：站点、权益、他日提醒都还在；重复核销不写「请提供」', () => {
  const { verificationSheetView } = require('../../utils/merchant-verification.js');
  const ok = resolveVerificationResult({ code: 200, msg: '核销成功', data: { chapterName: '第1章 咖啡', perkName: '美式一杯', sessionDate: '11月12日', sessionNotToday: true } });
  assert.deepEqual(verificationSheetView(ok), { kind: 'success', sub: '第1章 咖啡', pill: '请提供：美式一杯', meta: '这张票是 11月12日 的场次', why: '' });
  const rej = verificationSheetView({ state: 'rejected', title: '未核销', message: '这不是城瘾的核销码' });
  assert.equal(rej.kind, 'fail');
  assert.equal(rej.why, '这不是城瘾的核销码');
  const dup = resolveVerificationResult({ code: 200, msg: '核销成功', data: { replayed: true, chapterName: 'A', perkName: 'P' } });
  const dv = verificationSheetView(dup);
  assert.equal(dv.kind, 'fail');
  assert.equal(dv.pill, '');
  assert.equal(dv.sub, 'A');
});

function pageMethods() {
  const src = read('pages/merchant/index/index.js');
  const pick = name => {
    const start = src.indexOf('\n  ' + name + '(');
    let i = src.indexOf('{', start), depth = 0;
    for (; i < src.length; i++) { if (src[i] === '{') depth++; else if (src[i] === '}' && --depth === 0) break; }
    return src.slice(start, i + 1);
  };
  return pick;
}

test('M-23 继续扫码先收起面板再打开扫码；M-25 非城瘾码进同一张面板', () => {
  const pick = pageMethods();
  const calls = [];
  const wx = { nextTick: fn => fn() };
  const { verificationSheetView } = require('../../utils/merchant-verification.js');
  const page = new Function('wx', 'verificationSheetView', 'return ({' + pick('continueScan') + ',' + pick('showRejectedScan') + ',' + pick('onVerificationPrimary') + '});')(wx, verificationSheetView);
  page.closeScene = () => calls.push('close');
  page.goScanQR = () => calls.push('scan');
  page.openScene = id => calls.push('open:' + id);
  page.setData = d => calls.push(d);
  page.continueScan();
  assert.deepEqual(calls, ['close', 'scan']);
  calls.length = 0;
  page.closeVerificationResult = () => calls.push('done');
  page.data = { merchantAccess: { canVerify: true } };
  page.onVerificationPrimary();
  page.data = { merchantAccess: { canVerify: false } };
  page.onVerificationPrimary();
  assert.deepEqual(calls, ['close', 'scan', 'done']);
  calls.length = 0;
  page.showRejectedScan('这不是城瘾的核销码');
  assert.equal(calls[0].verificationResult.state, 'rejected');
  assert.equal(calls[0].verificationResult.kind, 'fail');
  assert.equal(calls[0].verificationResult.why, '这不是城瘾的核销码');
  assert.equal(calls[1], 'open:merchant-verification-result');
  const src = read('pages/merchant/index/index.js');
  assert.match(src, /that\.showRejectedScan\(scan\.message === '二维码格式错误' \? '这不是城瘾的核销码' : scan\.message\)/);
});

test('M-04 承接卡在开场当天写「今天 几点 · 几人已报名」并收起「今日无待办」', () => {
  const { attachVisitTexts } = require('../../utils/merchant-workbench.js');
  const now = Date.parse('2026-11-02T05:00:00.000Z'); // 上海 13:00
  const out = attachVisitTexts([
    { role: 'join', ownerId: 990014, todoText: '今日无待办', meta: '第1章 · 咖啡 · 周四 11月12日', metaNoDate: '第1章 · 咖啡' },
    { role: 'join', ownerId: 5, todoText: '今日无待办', meta: '第2章 · 周四 11月12日', metaNoDate: '第2章' },
  ], [
    { topicId: 990014, clubName: '执行俱乐部', paidCount: 4, startTime: '2026-11-02 13:23:00' },
    { topicId: 5, clubName: '夜跑团', paidCount: 2, startTime: '2026-11-12 10:00:00' },
  ], now);
  assert.equal(out[0].visitText, '今天 13:23 · 4 人已报名');
  assert.equal(out[0].todoText, '');
  assert.equal(out[1].visitText, '夜跑团 · 11月12日 周四 10:00 到店');
  assert.equal(out[1].todoText, '今日无待办');
  // 第4轮:当天那张卡收起主题日期,只留章节;他日场次的卡日期照旧
  assert.equal(out[0].meta, '第1章 · 咖啡');
  assert.equal(out[1].meta, '第2章 · 周四 11月12日');
});

test('M-04 第4轮 承接卡数据带一份不含日期的章节行', () => {
  const src = read('pages/merchant/index/index.js');
  assert.match(src, /assignmentPlaceText: placeText/);
  const { buildProjectCards, attachVisitTexts } = require('../../utils/merchant-workbench.js');
  const cards = buildProjectCards({
    todoByProject: [],
    joinList: [{ id: 1, topicId: 990014, topicName: '链', status: 1, assignmentText: '第1章 · 咖啡 · 周四 11月12日', assignmentPlaceText: '第1章 · 咖啡' }],
  });
  const now = Date.parse('2026-11-02T05:00:00.000Z');
  const out = attachVisitTexts(cards, [{ topicId: 990014, clubName: '俱乐部', paidCount: 1, startTime: '2026-11-02 13:23:00' }], now);
  assert.equal(out[0].visitText, '今天 13:23 · 1 人已报名');
  assert.equal(out[0].meta, '第1章 · 咖啡');
});

test('M-26 店铺参谋高峰时段读作「中午到店最多」，不再是「午上」', () => {
  const src = fs.readFileSync(path.join(__dirname, '../../pages/merchant/marketing/ai-insight/index.js'), 'utf8');
  let def; global.Page = d => { def = d; };
  const start = src.indexOf('_buildSlots(facts) {');
  const body = src.slice(start, src.indexOf('_buildSources(facts)'));
  const numberOrNull = v => (v == null || isNaN(Number(v)) ? null : Number(v));
  const page = new Function('numberOrNull', 'return ({' + body + '});')(numberOrNull);
  const out = page._buildSlots({ checkin: { slotBuckets: [{ slot: '早', count: 1 }, { slot: '午', count: 4 }, { slot: '晚', count: 0 }, { slot: '夜', count: 0 }] } });
  assert.equal(out.peakText, '中午到店最多');
});

test('M-24 供给卡写剩余份数(主题页与承接项目组件同一字段)', () => {
  for (const f of ['pages/topic/merchantinfo/merchantinfo.wxml', 'pages/topic/components/project-join/index.wxml']) {
    assert.match(read(f), /实际供给已生效<text wx:if="\{\{application\.quotaLeftText\}\}"> · \{\{application\.quotaLeftText\}\}<\/text>/, f);
  }
  const js = read('pages/topic/merchantinfo/merchantinfo.js');
  const expr = js.slice(js.indexOf('quotaLeftText:'), js.indexOf('circleSupplyCheckedAtText:', js.indexOf('quotaLeftText:')));
  const row = { offerQuotaTotal: 20, offerQuotaUsed: 5 };
  const value = new Function('row', 'return ' + expr.replace(/^quotaLeftText:\s*/, '').replace(/,\s*(\/\/[^\n]*\s*)?$/, ''))(row);
  assert.equal(value, '剩余 15 / 20');
  assert.equal(new Function('row', 'return ' + expr.replace(/^quotaLeftText:\s*/, '').replace(/,\s*(\/\/[^\n]*\s*)?$/, ''))({}), '');
});

test('M-27 核销与退款行：无左侧状态点、副行是顾客姓名(有才出)与时间、零现金右列不画破折号', () => {
  const wxml = read('pages/merchant/ledger/index.wxml');
  const row = wxml.slice(wxml.indexOf('wx:for="{{group.rows}}"'), wxml.indexOf('</view>\n            </view>\n          </view>\n        </block>'));
  assert.doesNotMatch(row, /fin-row__dot/);
  assert.doesNotMatch(row, /顾客信息受保护/);
  assert.match(row, /<text wx:if="\{\{item\.customerName\}\}">\{\{item\.customerName\}\}<\/text>\s*<text wx:if="\{\{item\.timeText\}\}">\{\{item\.timeText\}\}<\/text>/);
  const src = read('pages/merchant/ledger/index.js');
  assert.match(src, /displayState === 'NO_CASH_SETTLEMENT' \? null : '待定'/);
});

test('M-27 第4轮 核销行取服务端报名姓名,没有就空着;退款行不借用活动名当顾客', () => {
  const src = read('pages/merchant/ledger/index.js');
  const start = src.indexOf('  refundRow(row, canReadFinance) {');
  const end = src.indexOf('  entryRow(row) {');
  const money = (v) => (v == null || isNaN(Number(v)) ? null : Number(v).toFixed(2));
  const page = new Function('money', 'financeStateText', 'stateVariant', 'return ({' + src.slice(start, end) + '});')(money, () => '已入账', () => 'success');
  const named = page.redemptionRow({ topicName: '链', occurredAt: '2026-11-03 03:51:00', customerDisplayName: ' 林野 ', settlementAmount: 0, displayState: 'NO_CASH_SETTLEMENT' }, true);
  assert.equal(named.customerName, '林野');
  const nameless = page.redemptionRow({ topicName: '链', occurredAt: '2026-11-03 03:51:00' }, false);
  assert.equal(nameless.customerName, '');
  const refund = page.refundRow({ refundId: 1, titleText: '链', activityTitleText: '某活动' }, true);
  assert.ok(!refund.customerName);
});
