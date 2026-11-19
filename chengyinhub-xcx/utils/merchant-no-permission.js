'use strict';
// 商家页「当前身份不能…」原地无权限态的唯一动作(UI-sweep 1119 U3):
// 不是商家成员 → 申请入驻;是在营商家成员、只是岗位没这项权限 → 返回。
// 只管呈现与去处,不放行任何内容:调用方仍按 access/me 判定 fail-closed。
const APPLY_URL = '/pages/merchant/apply/index';
const MERCHANT_HOME = '/pages/merchant/index/index';

function denyCtaText(member) { return member === true ? '返回' : '申请入驻'; }

// owner = 发起的页面/组件实例,拿来上一次性导航锁,连点只走一次
function followDenyCta(owner, member, fallbackUrl) {
  const lock = owner || {};
  if (lock._cyDenyNavigating) return;
  lock._cyDenyNavigating = true;
  const done = () => { lock._cyDenyNavigating = false; };
  if (member !== true) {
    wx.navigateTo({ url: APPLY_URL, fail: () => wx.redirectTo({ url: APPLY_URL }), complete: done });
    return;
  }
  if (getCurrentPages().length > 1) wx.navigateBack({ complete: done });
  else wx.reLaunch({ url: fallbackUrl || MERCHANT_HOME, complete: done });
}

module.exports = { APPLY_URL, denyCtaText, followDenyCta };
