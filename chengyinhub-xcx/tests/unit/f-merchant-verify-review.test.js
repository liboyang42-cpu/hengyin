'use strict';
// f-merchant(P0-42 / P1-016,2026-11-11):核销台 409(CONFLICT)进「复核」态,提示是中文。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const v = require(path.join(ROOT, 'utils/merchant-verification.js'));
const { classifyCityNodeRedeem } = require(path.join(ROOT, 'pages/merchant/utils/citynode-redeem-outcome.js'));

const ticket = { url: '/api/registration/scan_qr_code', data: { code: 'x' } };

test('HTTP 409 与业务 code 409 都进复核态,不是失败也不是待确认', () => {
  const http = v.classifyVerificationOutcome({ code: 409, msg: 'CONFLICT' }, 'abnormal', 409, ticket);
  assert.equal(http.status, 'review');
  assert.equal(http.response.code, 409);
  assert.equal(http.response.msg, v.CONFLICT_MESSAGE, '英文码换成中文句');
  const biz = v.classifyVerificationOutcome({ code: 409, msg: '单票核销状态不明确，请人工核对' }, 'success', undefined, ticket);
  assert.equal(biz.status, 'review');
  assert.equal(biz.response.msg, '单票核销状态不明确，请人工核对', '后端中文原话不改写');
  const failed = v.classifyVerificationOutcome(null, 'fail', 409, ticket);
  assert.equal(failed.status, 'review');
  assert.equal(failed.response.msg, v.CONFLICT_MESSAGE);
});

test('复核态面板:标题「待复核」、原因写中文、带 review 标记', () => {
  const r = v.resolveVerificationResult({ code: 409, msg: '' }, '验票');
  assert.equal(r.state, 'review');
  assert.equal(r.title, '待复核');
  assert.match(r.message, /复核/);
  assert.doesNotMatch(r.message, /CONFLICT|409/);
  const view = v.verificationSheetView(r);
  assert.equal(view.review, true);
  assert.equal(view.kind, 'fail');
  assert.equal(view.pill, '', '复核态不得出现「请提供」');
  assert.equal(view.why, r.message);
});

test('其他结论不受影响:500 仍待确认、403 仍是拒绝、200 仍成功', () => {
  assert.equal(v.classifyVerificationOutcome({ code: 500, msg: 'x' }, 'success', undefined, ticket).status, 'unknown');
  assert.equal(v.classifyVerificationOutcome({ code: 403 }, 'abnormal', 403, ticket).status, 'failed');
  assert.equal(v.classifyVerificationOutcome({ code: 200, msg: '核销成功', data: null }, 'success', undefined, ticket).status, 'success');
  assert.equal(v.resolveVerificationResult({ code: 200, msg: '核销成功' }).review, undefined);
});

test('据点核销同口径:409 进复核态,中文提示', () => {
  const r = classifyCityNodeRedeem({ code: 409, msg: 'VERIFICATION_CONFLICT' }, 'abnormal', 409, 'v1.1.citynode_2.0.n.s');
  assert.equal(r.status, 'review');
  assert.match(r.response.msg, /^[^A-Z]*复核/);
});

test('工作台:复核态主按钮去核销记录,据点页弹「待复核」', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.wxml'), 'utf8');
  assert.match(wxml, /verificationResult\.review \? '查看核销记录'/);
  const js = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.js'), 'utf8');
  assert.match(js, /verificationResult\.review\)[\s\S]{0,120}\/pages\/merchant\/verify-records\/index/);
  const city = fs.readFileSync(path.join(ROOT, 'pages/merchant/citynode/index.js'), 'utf8');
  assert.match(city, /result\.status === 'review'[\s\S]{0,80}title: '待复核'/);
});
