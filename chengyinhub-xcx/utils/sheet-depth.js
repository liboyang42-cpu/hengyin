'use strict'

/**
 * 叠层景深登记表(qx-u3,2026-11-10 业主参考 ref3「Stacked cards」)
 *
 * 一层弹层开在另一层**仍开着**的弹层上面时,下面那层缩到 .94、上移 10px、压暗。
 * 以前每页自己算(project-host 的 under="{{ a.show || b.show }}"、fabu 的 stacked="{{...}}"),
 * 新开一层就要回去改下面那层的表达式,漏一处就是「上面开了、下面没退」。
 * 现在由弹层宿主(behaviors/sheet-depth.js)按「打开顺序」统一登记:同一页里最后打开的是顶层,
 * 其余开着的都在下面。页面一行不用写。
 *
 * 只按页面分组:两个页面的弹层互不影响(页面栈里下面那页的弹层不该被上面那页的弹层压暗)。
 * 关闭一层立即出表:上一层在这一层退场的同时回到原位(同一时刻两层一起动,像 iOS 叠卡)。
 */

const pages = new Map()   // pageKey -> 打开顺序的实例数组

function keyOf(vm) {
  try { if (vm && typeof vm.getPageId === 'function') return String(vm.getPageId()) } catch (e) { /* 拿不到就归到同一组 */ }
  return 'page'
}

/** 纯函数:按打开顺序给出每一层是否「在下面」(最后一层是顶层)。 */
function underFlags(count) {
  return Array.from({ length: Math.max(0, count) }, (_, i) => i < count - 1)
}

function sync(list) {
  const flags = underFlags(list.length)
  list.forEach((vm, i) => { if (typeof vm._applySheetDepth === 'function') vm._applySheetDepth(flags[i]) })
}

function open(vm) {
  const key = keyOf(vm)
  const list = pages.get(key) || []
  const at = list.indexOf(vm)
  if (at >= 0) list.splice(at, 1)   // 重开 = 回到顶层
  list.push(vm)
  pages.set(key, list)
  sync(list)
}

function close(vm) {
  const key = keyOf(vm)
  const list = pages.get(key)
  if (typeof vm._applySheetDepth === 'function') vm._applySheetDepth(false)
  if (!list) return
  const at = list.indexOf(vm)
  if (at >= 0) list.splice(at, 1)
  if (!list.length) { pages.delete(key); return }
  sync(list)
}

module.exports = { open, close, underFlags }
// 单测用:清空登记表
module.exports._reset = function () { pages.clear() }
