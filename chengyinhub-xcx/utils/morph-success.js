'use strict'

/**
 * 成功 morph 的页面侧节拍(qx-u3,ref3「Morph」)
 *
 * cy-btn morph:loading 时药丸收成圆转圈;服务端确认成功后,页面调 settle():
 *   ① 按钮进 done —— 圆里弹出勾、一圈脉冲散开(纯 transition,见 style/morph-mark.wxss);
 *   ② 停 HOLD_MS 让人看清这一下;
 *   ③ 执行 then() 写入页面原本的成功状态(新标题 / 新按钮 / 打开结果面板 / 跳页),再撤掉 done,
 *      药丸带着新文字从圆长回去。
 *      成功文案本来就由页面渲染,这里不造任何新文字。
 *
 * 只在**服务端确认成功**之后调;失败、结果未知都不进 done(那是另一种状态,不能长成勾)。
 * 减动效:不停留,直接 then()。页面离开(onUnload / detached)时调 cancel(),定时器不再回调。
 */
const { readReducedMotion } = require('./motion-preference.js')
const { hapticConfirm } = require('./motion.js')

// 勾的弹出(--cy-motion-slow 350)+ 脉冲圈散开(--cy-motion-celebrate 600)看完再换,略多一拍
const HOLD_MS = 720

/**
 * @param {object} vm      Page / Component 实例(要有 setData)
 * @param {string} key     控制按钮 done 的 data 路径,如 'claimDone'
 * @param {Function} then  停留结束后执行:写页面原本的成功状态
 * @param {object} [opts]  { reduced?: boolean, hold?: number, with?: object }
 *   with:与 done 同一次 setData 写入的字段(如 busy:false)—— 分两次写的话药丸会先弹回一帧再收成勾。
 *   减动效时 with 同样会写入(和 then 之前)。
 *   haptics:震动源(单测注入);默认 wx。勾出现的那一刻给一次 medium(MOTION.md M7 / M14 hapticConfirm),
 *   转圈时不震;减动效下没有勾,也就没有这一下。
 */
/** 本次成功要停多久:减动效为 0(不停留、不出勾)。给不走 cy-btn 的宿主(如提示阶梯行)自己排节拍用。 */
function holdFor(opts) {
  const o = opts || {}
  const reduced = typeof o.reduced === 'boolean' ? o.reduced : readReducedMotion()
  return reduced ? 0 : (typeof o.hold === 'number' ? o.hold : HOLD_MS)
}

function settle(vm, key, then, opts) {
  const o = opts || {}
  const reduced = holdFor(o) === 0
  cancel(vm)
  const patch = Object.assign({}, o.with)
  if (reduced) {
    if (Object.keys(patch).length) vm.setData(patch)
    if (typeof then === 'function') then()
    return false
  }
  patch[key] = true
  vm.setData(patch)
  hapticConfirm({ haptics: o.haptics })   // 勾弹出来的这一帧:一次 medium,不在转圈时震
  vm._morphTimer = setTimeout(() => {
    vm._morphTimer = null
    // 先写页面的成功状态(圆还收着、文字透明,换字看不见),再撤 done —— 药丸带着新文字长回来
    if (typeof then === 'function') then()
    vm.setData({ [key]: false })
  }, holdFor(o))
  return true
}

function cancel(vm) {
  if (vm && vm._morphTimer) { clearTimeout(vm._morphTimer); vm._morphTimer = null }
}

module.exports = { settle, cancel, holdFor, HOLD_MS }
