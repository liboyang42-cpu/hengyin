'use strict';

// 自玩票虚拟支付(XPAY)客户端。是否走虚拟支付由服务端决定(订单带 payChannel:'XPAY'),客户端不能改选 JSAPI。
// 「已付」只认服务端:requestVirtualPayment 的成功回调不算数,支付后先让服务端回查再核账。
// 设备不支持 requestVirtualPayment 时如实说明,绝不静默改走微信支付。

var UNSUPPORTED_MESSAGE = '当前微信版本暂不支持购买，请升级微信后重试';

function isIntent(d) { return !!(d && d.payChannel === 'XPAY'); }

function hasVirtualPayment(d) {
  var v = d && d.virtualPayment;
  return !!(v && v.mode && v.signData && v.paySig && v.signature);
}

/** 服务端只告知渠道、尚未签名(需要 wx.login 的 jsCode)。 */
function needsSigning(d) { return isIntent(d) && !hasVirtualPayment(d); }

function isPaid(res) {
  return !!(res && Number(res.paymentStatus) === 2 && Number(res.registrationStatus) === 2);
}

function ok200(res) { return !!(res && (res.code == '200' || res.code == 200)); }

/** wx.login → POST /api/registration/pay {id, jsCode}。cb({ ok, data } | { ok:false, msg, expired }) */
function signIntent(app, wxApi, registrationId, cb) {
  wxApi.login({
    success: function (r) {
      if (!r || !r.code) { cb({ ok: false, msg: '请重新登录后再试' }); return; }
      app.sendRequest({
        url: '/api/registration/pay',
        method: 'POST',
        data: { id: registrationId, jsCode: r.code },
        success: function (res) {
          if (ok200(res) && hasVirtualPayment(res.data)) { cb({ ok: true, data: res.data }); return; }
          cb({ ok: false, expired: !!(res && res.code == 410), msg: (res && res.msg) || '支付信息不完整，请重试' });
        },
        fail: function () { cb({ ok: false, msg: '网络错误，请重试' }); }
      });
    },
    fail: function () { cb({ ok: false, msg: '请重新登录后再试' }); }
  });
}

/** 让服务端向微信回查并开通;尽力而为,cb(paid:boolean)。 */
function confirm(app, registrationId, cb) {
  if (!registrationId) { cb(false); return; }
  app.sendRequest({
    url: '/api/registration/xpay/confirm',
    method: 'POST',
    data: { id: registrationId },
    hideLoading: true,
    autoErrorToast: false,
    success: function (res) { cb(ok200(res) && isPaid(res.data)); },
    fail: function () { cb(false); }
  });
}

/**
 * 拉起虚拟支付。cb({ ok, cancelled, errMsg })。
 * 非取消的失败也先回查一次:SDK 失败不等于没扣款,服务端已付则按成功处理。
 */
function pay(app, wxApi, orderData, cb) {
  if (typeof wxApi.requestVirtualPayment !== 'function') {
    cb({ ok: false, unsupported: true, errMsg: UNSUPPORTED_MESSAGE });
    return;
  }
  var v = orderData.virtualPayment;
  wxApi.requestVirtualPayment({
    mode: v.mode, signData: v.signData, paySig: v.paySig, signature: v.signature,
    success: function () { cb({ ok: true }); },
    fail: function (e) {
      var cancelled = !!(e && (Number(e.errCode) === -2 || /cancel/.test(String(e.errMsg || ''))));
      if (cancelled) { cb({ ok: false, cancelled: true, errMsg: e && e.errMsg }); return; }
      confirm(app, orderData.registrationId, function (paid) {
        if (paid) cb({ ok: true });
        else cb({ ok: false, errMsg: '支付未完成，请稍后在订单中查看' });
      });
    }
  });
}

/** 支付后核账:先让服务端回查开通,再走原来的订单状态核账。返回可 abort 的控制柄。 */
function verify(app, verifier, data, cb) {
  var regId = data.registrationId || data.id;
  var control = null;
  var aborted = false;
  confirm(app, regId, function () {
    if (!aborted) control = verifier({ registrationId: regId }, cb);
  });
  return {
    abort: function () {
      aborted = true;
      if (control && typeof control.abort === 'function') control.abort();
    }
  };
}

module.exports = {
  UNSUPPORTED_MESSAGE: UNSUPPORTED_MESSAGE,
  isIntent: isIntent,
  hasVirtualPayment: hasVirtualPayment,
  needsSigning: needsSigning,
  signIntent: signIntent,
  confirm: confirm,
  pay: pay,
  verify: verify
};
