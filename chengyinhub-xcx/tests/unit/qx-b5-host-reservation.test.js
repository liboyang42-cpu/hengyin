// qx-b5 §27.6 主持预约:只按服务端 reservationState 显示,主持未接受前不开放付款。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { hostReservationView } = require('../../pages/activity/utils/host-reservation.js');

const ROOT = path.join(__dirname, '..', '..');

test('服务端 reservationState 逐态显示;CONFIRMED 才写已确认,待处置不写已退/已确认', () => {
  const v = (rs) => hostReservationView({ state: 'REQUESTED', eligibilityState: 'ELIGIBLE', waitlistJoinAllowed: false, reservationState: rs });
  assert.equal(v('REQUESTED').text, '主持申请中');
  assert.equal(v('REQUESTED').canCancel, true);
  assert.equal(v('ACCEPTED_AWAITING_PAYMENT').awaitingPayment, true);
  assert.equal(v('CONFIRMED').text, '主持预约已确认');
  assert.equal(v('PAYMENT_REVIEW_REQUIRED').text, '付款待处置');
  assert.equal(v('ORDER_REVIEW_REQUIRED').text, '订单待处置');
  assert.equal(v('REJECTED').canRequest, true);
  assert.equal(v('EXPIRED').text, '主持预约已过期');
  assert.equal(v('CANCELLED').canRequest, true);
});

test('无条目:ELIGIBLE + 不允许加入候补 + NONE ⇒ 需先请求主持;普通候补系列不出主持卡', () => {
  const need = hostReservationView({ state: 'NONE', eligibilityState: 'ELIGIBLE', waitlistJoinAllowed: false }, false);
  assert.equal(need.show, true);
  assert.equal(need.canRequest, true);
  assert.equal(hostReservationView({ state: 'NONE', eligibilityState: 'ELIGIBLE', waitlistJoinAllowed: true }, true).show, false);
  assert.equal(hostReservationView({ state: 'NONE', eligibilityState: 'NO_SERIES', waitlistJoinAllowed: false }, false).show, false);
  assert.equal(hostReservationView({ state: 'NONE', eligibilityState: 'ELIGIBLE', waitlistJoinAllowed: false }, true).show, false,
    '售罄走原候补卡');
});

test('结算页接线:主持未接受前按钮关闭;候补读包接受 ELIGIBLE 且不允许加入的合法形态', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/activity/baoming/baoming.js'), 'utf8');
  assert.match(js, /const hostBlocked = !!\(data\.hostResv && data\.hostResv\.required\) && !activeOffer;/);
  assert.match(js, /&& !signupClosed && !hostBlocked/);
  assert.match(js, /if \(joinAllowed && eligibility !== 'ELIGIBLE'\) return null;/);
  assert.match(js, /'REQUESTED', 'REJECTED'/);
  assert.match(js, /url: '\/api\/club\/event-ops\/host\/request'/);
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/activity/baoming/baoming.wxml'), 'utf8');
  assert.match(wxml, /hostResv\.required && !waitlistOfferId \? '请先预约主持'/);
});
