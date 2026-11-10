// qx-u1:在 Node 里跑 utils/wxs/gesture.wxs —— 给它一个假的视图层(ownerInstance / ComponentDescriptor),
// 记录 setStyle / addClass / callMethod,让单测按真实触摸序列驱动、断言面板最后去了哪。
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const WXS = path.resolve(__dirname, '../../../utils/wxs/gesture.wxs')

function loadWxs(mutate) {
  let src = fs.readFileSync(WXS, 'utf8')
  if (mutate) {
    const next = mutate(src)
    if (next === src) throw new Error('负控没有真的改到 WXS 源码 —— 注入点写错了')
    src = next
  }
  const module = { exports: {} }
  // WXS 运行时只有这些全局;不给 Date / require,写错了这里会直接抛
  vm.runInNewContext(src, { module, Math, String, Number, parseFloat }, { filename: WXS })
  return module.exports
}

function makeEl({ width = 390, height = 600 } = {}) {
  return {
    style: {},
    classes: new Set(),
    styles: [],
    setStyle(s) { this.style = Object.assign({}, s); this.styles.push(this.style) },
    addClass(c) { this.classes.add(c) },
    removeClass(c) { this.classes.delete(c) },
    getBoundingClientRect() { return { width, height } },
  }
}

function makeOwner(map) {
  const state = {}
  const calls = []
  return {
    calls,
    getState() { return state },
    selectComponent(sel) { return map[sel] || null },
    callMethod(name, detail) { calls.push({ name, detail }) },
  }
}

const SHEET_DS = { panel: '.sh__panel', root: '.sh', dragcls: 'sh--dragging', off: false, blocked: false, rm: false, wh: 800 }

/* 驱动一次纵向拖拽:steps 是每一帧的位移增量(px),stepMs 每帧耗时 */
function sheetDrag(gs, owner, { steps, stepMs = 16, ds = {}, y0 = 300 }) {
  const dataset = Object.assign({}, SHEET_DS, ds)
  let t = 1000
  let y = y0
  const ev = () => ({ currentTarget: { dataset }, touches: [{ clientX: 100, clientY: y }], timeStamp: t })
  gs.sheetStart(ev(), owner)
  for (const dy of steps) { y += dy; t += stepMs; gs.sheetMove(ev(), owner) }
  gs.sheetEnd({ currentTarget: { dataset }, touches: [], timeStamp: t }, owner)
}

/* 驱动一次横向侧滑 */
function edgeDrag(gs, owner, layer, { steps, stepMs = 16, x0 = 10, dyPerStep = 0, ds = {} }) {
  const dataset = Object.assign({ close: 'close', rm: false, glayer: '1' }, ds)
  let t = 1000
  let x = x0
  let y = 400
  const ev = () => ({ currentTarget: { dataset }, target: { dataset }, instance: layer, touches: [{ clientX: x, clientY: y }], timeStamp: t })
  gs.edgeStart(ev(), owner)
  for (const dx of steps) { x += dx; y += dyPerStep; t += stepMs; gs.edgeMove(ev(), owner) }
  gs.edgeEnd({ currentTarget: { dataset }, instance: layer, touches: [], timeStamp: t }, owner)
}

const offsetOf = (style) => {
  const m = /translate3d\(0,(-?[\d.]+)(px|%),0\)/.exec(style.transform || '')
  return m ? (m[2] === '%' ? m[1] + '%' : Number(m[1])) : null
}

module.exports = { loadWxs, makeEl, makeOwner, sheetDrag, edgeDrag, offsetOf, WXS }
