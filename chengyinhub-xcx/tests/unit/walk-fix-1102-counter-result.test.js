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

test('M-05/M-23 结果面板：绿色对勾、站点、权益、主按钮「继续扫码」', () => {
  const wxml = read('pages/merchant/index/index.wxml');
  const sheet = wxml.slice(wxml.indexOf("'merchant-verification-result'"), wxml.indexOf('<tabBar'));
  assert.match(sheet, /verify-result__badge[\s\S]*name="\{\{verificationResult\.state === 'success' \? 'check' : 'close'\}\}"/);
  assert.match(sheet, /\{\{verificationResult\.stop\}\}/);
  assert.match(sheet, /请提供：\{\{verificationResult\.perk\}\}/);
  assert.match(sheet, /\{\{verificationResult\.warn\}\}/);
  assert.match(sheet, /bindtap="continueScan"[^>]*>继续扫码</);
  assert.doesNotMatch(sheet, />知道了</);
  const wxss = read('pages/merchant/index/index.wxss');
  assert.match(wxss, /\.verify-result__badge \{[^}]*background: var\(--cy-success\)/);
  assert.match(wxss, /\.verify-result--rejected \.verify-result__badge \{ background: var\(--cy-danger\)/);
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
  const page = new Function('wx', 'return ({' + pick('continueScan') + ',' + pick('showRejectedScan') + '});')(wx);
  page.closeScene = () => calls.push('close');
  page.goScanQR = () => calls.push('scan');
  page.openScene = id => calls.push('open:' + id);
  page.setData = d => calls.push(d);
  page.continueScan();
  assert.deepEqual(calls, ['close', 'scan']);
  calls.length = 0;
  page.showRejectedScan('这不是城瘾的核销码');
  assert.deepEqual(calls, [{ verificationResult: { state: 'rejected', title: '未核销', message: '这不是城瘾的核销码', show: true } }, 'open:merchant-verification-result']);
  const src = read('pages/merchant/index/index.js');
  assert.match(src, /that\.showRejectedScan\(scan\.message === '二维码格式错误' \? '这不是城瘾的核销码' : scan\.message\)/);
});

test('M-04 承接卡在开场当天写「今天 几点 · 俱乐部 · 几人已报名」并收起「今日无待办」', () => {
  const { attachVisitTexts } = require('../../utils/merchant-workbench.js');
  const now = Date.parse('2026-11-02T05:00:00.000Z'); // 上海 13:00
  const out = attachVisitTexts([
    { role: 'join', ownerId: 990014, todoText: '今日无待办' },
    { role: 'join', ownerId: 5, todoText: '今日无待办' },
  ], [
    { topicId: 990014, clubName: '执行俱乐部', paidCount: 4, startTime: '2026-11-02 13:23:00' },
    { topicId: 5, clubName: '夜跑团', paidCount: 2, startTime: '2026-11-12 10:00:00' },
  ], now);
  assert.equal(out[0].visitText, '今天 13:23 · 执行俱乐部 · 4 人已报名');
  assert.equal(out[0].todoText, '');
  assert.equal(out[1].visitText, '夜跑团 · 11月12日 周四 10:00 到店');
  assert.equal(out[1].todoText, '今日无待办');
});
