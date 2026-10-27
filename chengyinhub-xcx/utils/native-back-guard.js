'use strict'

/**
 * 原生返回守卫(G-2,业主 2026-10-27 m694:「所有页面的弹窗:手机返回会直接退回一级页面」)
 *
 * 病:共享弹层(cy-sheet / cy-scene-sheet / cy-modal / cy-date-sheet / cy-publish-sheet)只拦了点遮罩和点 ✕,
 * 安卓物理返回与 iOS 侧滑直接把整页弹出去。
 * 做法与 #589 玩法预览、play hero 同一套:弹层开着时挂一个 0 尺寸、无遮罩的 page-container,
 * 返回手势被它吃掉后落到 afterleave,由弹层自己走「关闭/上一步」出口。
 * 在共享组件里实现一次 —— 凡用这些组件的页面不改一行就接入。
 *
 * 三条规矩:
 *   ① 一页同时只能有一个 page-container:模块级登记表只让**最上层**弹层挂守卫,
 *      叠层(脏数据确认盖在编辑弹层上)关一层退一层。
 *   ② 程序收起守卫(换顶 / 关闭 / 让路)也会触发 afterleave,靠 _nbgWanted 区分,不当成返回键。
 *   ③ 离页(navigateTo、选图)同样会让守卫 leave(见 merchant-decor-store-info-l04):
 *      afterleave 后等一拍,页面若已 hide 或页栈变深就不是返回键,回到页面(pageLifetimes.show)再挂。
 *
 * page-container 显示时 wx.navigateBack 会被它吃掉:宿主要在弹层仍开着时退页,先 releaseNativeBackGuards(cb)。
 * 页面自己已有 page-container 守卫并覆盖了某个弹层时,给那个弹层传 back-guard="{{false}}" 让位。
 */
const LEAVE_SETTLE_MS = 60
const SUSPEND_MS = 800

const stack = []          // 开着且启用守卫的弹层实例,后开的在后
let suspended = false
let suspendTimer = null

function pageDepth() {
  try { return getCurrentPages().length } catch (_) { return 0 }
}

function setGuard(vm, on, cb) {
  vm._nbgWanted = on
  if (vm._nbgDead || vm.data._backGuard === on) { if (cb) cb(); return }
  vm.setData({ _backGuard: on }, cb)
}

function sync() {
  const top = stack[stack.length - 1]
  stack.forEach((vm) => setGuard(vm, !suspended && vm === top && !vm._nbgPageHidden))
}

function register(vm) {
  if (stack.indexOf(vm) < 0) {
    vm._nbgDepth = pageDepth()
    stack.push(vm)
  }
  sync()
}

function unregister(vm) {
  const i = stack.indexOf(vm)
  if (i >= 0) stack.splice(i, 1)
  setGuard(vm, false)
  sync()
}

function onLeave(vm) {
  if (!vm._nbgWanted) return          // ② 程序收起
  vm._nbgWanted = false
  setTimeout(() => {
    if (stack.indexOf(vm) < 0 || vm._nbgDead) return
    // ③ 离页 / 选图:不是返回键。守卫记成已撤,页面回来时 show 再挂
    if (vm._nbgPageHidden || pageDepth() > vm._nbgDepth) { setGuard(vm, false); return }
    setGuard(vm, false, () => {
      if (typeof vm._onNativeBack === 'function') vm._onNativeBack()
      // 宿主没关掉它(脏数据确认、表单不许关)⇒ 重新挂上,下一次返回仍先落在弹层上
      setTimeout(() => { if (stack.indexOf(vm) >= 0) sync() }, 0)
    })
  }, LEAVE_SETTLE_MS)
}

/** 弹层仍开着时要 navigateBack:先把守卫全撤下(渲染层生效后)再执行 cb。 */
function releaseNativeBackGuards(cb) {
  suspended = true
  if (suspendTimer) clearTimeout(suspendTimer)
  suspendTimer = setTimeout(() => { suspendTimer = null; suspended = false; sync() }, SUSPEND_MS)
  const armed = stack.filter((vm) => vm.data._backGuard)
  if (!armed.length) { if (cb) cb(); return }
  let left = armed.length
  armed.forEach((vm) => setGuard(vm, false, () => { left -= 1; if (!left && cb) cb() }))
}

function chain(a, b) {
  if (!a) return b
  return function chained(...args) { a.apply(this, args); return b.apply(this, args) }
}

/**
 * 把守卫混进组件定义。onBack(this) = 返回键落下时的出口(关闭或上一步)。
 * 组件 wxml 里放:
 *   <page-container show="{{_backGuard}}" duration="{{0}}" overlay="{{false}}"
 *     custom-style="width:0;height:0;background:transparent;pointer-events:none" bind:afterleave="_onBackGuardLeave" />
 */
function withNativeBackGuard(definition, onBack) {
  const def = Object.assign({}, definition)
  // 组件应在自己的 properties 里显式声明 backGuard(属性契约门禁按源码静态核对);没声明时这里补上
  def.properties = Object.assign({
    backGuard: { type: Boolean, value: true },
  }, def.properties)
  def.data = Object.assign({}, def.data, { _backGuard: false })
  def.observers = Object.assign({}, def.observers, {
    'show, backGuard'(show, enabled) {
      if (show && enabled) register(this)
      else if (stack.indexOf(this) >= 0 || this.data._backGuard) unregister(this)
    },
  })
  const lifetimes = Object.assign({}, def.lifetimes)
  lifetimes.attached = chain(lifetimes.attached, function () {
    this._nbgDead = false
    if (this.data.show && this.data.backGuard) register(this)
  })
  lifetimes.detached = chain(lifetimes.detached, function () {
    this._nbgDead = true
    unregister(this)
  })
  def.lifetimes = lifetimes
  const pageLifetimes = Object.assign({}, def.pageLifetimes)
  pageLifetimes.hide = chain(pageLifetimes.hide, function () { this._nbgPageHidden = true })
  pageLifetimes.show = chain(pageLifetimes.show, function () {
    if (!this._nbgPageHidden) return
    this._nbgPageHidden = false
    if (stack.indexOf(this) >= 0) { this._nbgDepth = pageDepth(); sync() }
  })
  def.pageLifetimes = pageLifetimes
  // 组件可以自己声明 _onBackGuardLeave(wxml 绑定的 handler 要能在组件源码里定位,见 action-ledger)
  def.methods = Object.assign({ _onBackGuardLeave() { onLeave(this) } }, def.methods, {
    _onNativeBack() { if (typeof onBack === 'function') onBack(this) },
  })
  return def
}

// 单测用
function _reset() {
  stack.splice(0)
  suspended = false
  if (suspendTimer) { clearTimeout(suspendTimer); suspendTimer = null }
}

module.exports = { LEAVE_SETTLE_MS, onBackGuardLeave: onLeave, withNativeBackGuard, releaseNativeBackGuards, _stack: stack, _reset }
