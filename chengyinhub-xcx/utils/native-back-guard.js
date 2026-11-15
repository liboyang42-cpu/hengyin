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
const changing = new Set() // 已发出但尚未收到渲染回调的 native 挂载/卸载
let suspended = false
let suspendTimer = null

function pageDepth() {
  try { return getCurrentPages().length } catch (_) { return 0 }
}

function setGuard(vm, on, cb) {
  if (vm._nbgWanted !== on) vm._nbgOwnership = (vm._nbgOwnership || 0) + 1
  vm._nbgWanted = on
  if (cb) (vm._nbgGuardCallbacks || (vm._nbgGuardCallbacks = [])).push({ on, cb })
  if (vm._nbgDead) {
    vm._nbgWriteRevision = (vm._nbgWriteRevision || 0) + 1
    vm._nbgWriting = false
    changing.delete(vm)
    const callbacks = vm._nbgGuardCallbacks || []
    vm._nbgGuardCallbacks = []
    callbacks.forEach(item => item.cb())
    return
  }
  if (vm._nbgWriting) return
  const callbacks = vm._nbgGuardCallbacks || []
  // 显式卸载的回调必须等 false 真正渲染；关闭重开不能把它改成 true 的确认。
  const target = callbacks.some(item => !item.on) ? false : vm._nbgWanted
  const settle = () => {
    const ready = (vm._nbgGuardCallbacks || []).filter(item => item.on === target)
    vm._nbgGuardCallbacks = (vm._nbgGuardCallbacks || []).filter(item => item.on !== target)
    ready.forEach(item => item.cb())
  }
  if (vm.data._backGuard === target) {
    settle()
    if (!vm._nbgDead && vm.data._backGuard !== vm._nbgWanted) setGuard(vm, vm._nbgWanted)
    return
  }
  const revision = vm._nbgWriteRevision = (vm._nbgWriteRevision || 0) + 1
  vm._nbgWriting = true
  changing.add(vm)
  vm.setData({ _backGuard: target }, () => {
    if (vm._nbgDead || revision !== vm._nbgWriteRevision) return
    vm._nbgWriting = false
    settle()
    if (vm._nbgWriting || revision !== vm._nbgWriteRevision) return
    changing.delete(vm)
    if (vm.data._backGuard !== vm._nbgWanted || (vm._nbgGuardCallbacks || []).length) setGuard(vm, vm._nbgWanted)
    sync()
  })
}

function sync() {
  const top = stack[stack.length - 1]
  const owner = !suspended && top && !top._nbgPageHidden && !top._nbgDead && pageDepth() <= top._nbgDepth ? top : null
  const instances = new Set([...stack, ...changing])
  instances.forEach(vm => { if (vm !== owner) setGuard(vm, false) })
  // 旧实例的卸载回调到达前不创建新的 page-container。
  if (owner && ![...changing].some(vm => vm !== owner)) setGuard(owner, true)
}

function register(vm) {
  if (stack.indexOf(vm) < 0) {
    vm._nbgDepth = pageDepth()
    stack.push(vm)
  }
  sync()
}

function unregister(vm) {
  vm._nbgOwnership = (vm._nbgOwnership || 0) + 1
  const i = stack.indexOf(vm)
  if (i >= 0) stack.splice(i, 1)
  setGuard(vm, false)
  sync()
}

function onLeave(vm) {
  if (!vm._nbgWanted || vm !== stack[stack.length - 1] || suspended || vm._nbgDead) return
  const ownership = vm._nbgOwnership
  const current = () => vm._nbgOwnership === ownership && vm === stack[stack.length - 1]
    && !vm._nbgDead && !suspended
  vm._nbgWanted = false
  setTimeout(() => {
    if (!current()) return
    // 离页/选图不消费返回；恢复仍由 pageLifetimes.show 负责。
    if (vm._nbgPageHidden || pageDepth() > vm._nbgDepth) { setGuard(vm, false); return }
    setGuard(vm, false, () => {
      if (!current() || vm._nbgPageHidden || pageDepth() > vm._nbgDepth) return
      if (typeof vm._onNativeBack === 'function') vm._onNativeBack()
      setTimeout(() => { if (stack.indexOf(vm) >= 0 && !vm._nbgDead) sync() }, 0)
    })
  }, LEAVE_SETTLE_MS)
}

/** 弹层仍开着时要 navigateBack:先把守卫全撤下(渲染层生效后)再执行 cb。 */
function releaseNativeBackGuards(cb) {
  suspended = true
  if (suspendTimer) clearTimeout(suspendTimer)
  suspendTimer = setTimeout(() => { suspendTimer = null; suspended = false; sync() }, SUSPEND_MS)
  const armed = [...new Set([...stack, ...changing])].filter(vm => vm.data._backGuard || vm._nbgWriting)
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
 *   <page-container wx:if="{{_backGuard}}" show="{{_backGuard}}" duration="{{0}}" overlay="{{false}}"
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
  pageLifetimes.hide = chain(pageLifetimes.hide, function () {
    this._nbgPageHidden = true
    this._nbgOwnership = (this._nbgOwnership || 0) + 1
    sync()
  })
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
  changing.forEach(vm => {
    vm._nbgWriteRevision = (vm._nbgWriteRevision || 0) + 1
    vm._nbgWriting = false
    vm._nbgGuardCallbacks = []
  })
  changing.clear()
  stack.splice(0)
  suspended = false
  if (suspendTimer) { clearTimeout(suspendTimer); suspendTimer = null }
}

module.exports = { LEAVE_SETTLE_MS, onBackGuardLeave: onLeave, withNativeBackGuard, releaseNativeBackGuards, _stack: stack, _reset }
