'use strict';
// MER-08(2026-09-27 商家走查):招商截止后项目页底部主按钮仍写「申请承接」,点了才弹「已结束」。
const { chinaDayStart, chinaDayEnd } = require('./datetime.js');

/** 商家报名窗口已过或未到时的底部按钮说法;窗口内或缺日期返回空串(缺日期时点按仍按原逻辑提示)。 */
function signUpWindowText(info, now) {
  const start = info && info.merchantSignUpStartDate;
  const end = info && info.merchantSignUpEndDate;
  if (!start || !end) return '';
  const endAt = chinaDayEnd(end);
  const startAt = chinaDayStart(start);
  if (endAt && now > endAt) return '报名已结束';
  if (startAt && now < startAt) return '报名未开始';
  return '';
}

module.exports = { signUpWindowText };
