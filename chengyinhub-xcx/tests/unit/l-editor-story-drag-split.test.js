// l-editor(2026-11-10):渲染层拖拽内核的「拖进段落中间」。在 node 里按原文件求值 WXS,
// 钉住:手指落在可拆段的中间 → 回调 onStoryDragSplit(就近句子边界);离开 → index:-1 复原;
// 松手把 split 一起交回;靠边(上下 12px)与一行高的段不拆;没有 kind 的旧几何照旧只让位。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

function loadWxs(file) {
  const module = { exports: {} }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../..', file), 'utf8'), { module, exports: module.exports, Math, JSON, Number })
  return module.exports
}
const sd = loadWxs('pages/publish/utils/wxs/story-drag.wxs')

function fakeOwner(geo, count) {
  const state = {}
  const blocks = Array.from({ length: count }, () => ({ style: null, setStyle(s) { this.style = s } }))
  const calls = []
  const frames = []
  return {
    blocks, calls, frames,
    getState: () => state,
    selectComponent: (sel) => sel === '.story-article' ? { getDataset: () => ({ geo: geo ? JSON.stringify(geo) : '' }) } : null,
    selectAllComponents: (sel) => sel === '.story-block' ? blocks : [],
    callMethod: (name, arg) => calls.push([name, JSON.parse(JSON.stringify(arg))]),
    requestAnimationFrame: (cb) => frames.push(cb),
  }
}
const touch = (y) => ({ touches: [{ clientY: y }], changedTouches: [{ clientY: y }] })

// 三块:0 图片(被拖,高 100)、1 正文段(高 200,20 字,句子边界 [6, 13])、2 正文段(高 30,一行)
function geo() {
  return {
    key: 'img', from: 0, y: 150, st: 0, vt: 0, vb: 800, max: 0,
    top: [100, 220, 440], h: [100, 200, 30],
    kind: ['', 'p', 'p'], len: [0, 20, 8], bounds: [[], [6, 13], [4]],
  }
}

test('手指进入段落中间:按高度比例估字数、就近取句子边界,只在落点变化时回调一次', () => {
  const owner = fakeOwner(geo(), 3)
  sd.start(touch(150), owner)
  sd.move(touch(260), owner) // 手指 260 → 段 1 顶 220 + 40:40/200*20 = 4 字 → 边界 6
  assert.deepEqual(owner.calls, [['onStoryDragSplit', { index: 1, at: 6, h: 100 }]])
  sd.move(touch(262), owner) // 还是 6:不重复回调
  assert.equal(owner.calls.length, 1)
  // 拆开后段落里多了一个 116px 的槽(100 + 16),槽下方的手指要把槽高扣掉再折算
  sd.move(touch(220 + 60 + 116 + 70), owner) // 槽顶 ≈ 200*6/20 = 60;手指在槽下 70px → y=130 → 13 字 → 边界 13
  assert.deepEqual(owner.calls[1], ['onStoryDragSplit', { index: 1, at: 13, h: 100 }])
  // 拆段态下其余块不再用 transform 让位(槽已经把后面的块顶下去了)
  assert.equal(owner.blocks[2].style.transform, 'translate3d(0,0px,0)')
})

test('靠段落上下沿 12px 内、一行高的段、没有句子边界的段:都不拆,回到普通让位', () => {
  const owner = fakeOwner(geo(), 3)
  sd.start(touch(150), owner)
  sd.move(touch(228), owner) // 段 1 顶 220 + 8 < 12:不算中间
  assert.ok(!owner.calls.some((c) => c[0] === 'onStoryDragSplit'))
  sd.move(touch(455), owner) // 段 2 只有 30 高:不拆
  assert.ok(!owner.calls.some((c) => c[0] === 'onStoryDragSplit'))
  const noBounds = geo(); noBounds.bounds[1] = []
  const owner2 = fakeOwner(noBounds, 3)
  sd.start(touch(150), owner2)
  sd.move(touch(300), owner2)
  assert.ok(!owner2.calls.some((c) => c[0] === 'onStoryDragSplit'))
})

test('离开段落中间:回调 index:-1 复原,随后恢复按中线让位', () => {
  const owner = fakeOwner(geo(), 3)
  sd.start(touch(150), owner)
  sd.move(touch(300), owner)
  assert.equal(owner.calls[0][0], 'onStoryDragSplit')
  sd.move(touch(700), owner) // 到了列表末尾
  assert.deepEqual(owner.calls[1], ['onStoryDragSplit', { index: -1 }])
  assert.deepEqual(owner.calls[2], ['onStoryDragSlot', { target: 2 }])
  assert.equal(owner.blocks[1].style.transform, 'translate3d(0,-120px,0)', '段 1 让位(槽 = 100 + 间距 20)')
})

test('在段落中间松手:drop 带 split;在缝里松手:drop 不带 split(旧测试的形状不变)', () => {
  const owner = fakeOwner(geo(), 3)
  sd.start(touch(150), owner)
  sd.move(touch(300), owner)
  sd.end(touch(300), owner)
  assert.deepEqual(owner.calls.at(-1), ['onStoryDragDrop', { key: 'img', from: 0, to: 0, scrollTop: 0, split: { index: 1, at: 6 } }])
  const owner2 = fakeOwner(geo(), 3)
  sd.start(touch(150), owner2)
  sd.move(touch(700), owner2)
  sd.end(touch(700), owner2)
  assert.deepEqual(owner2.calls.at(-1), ['onStoryDragDrop', { key: 'img', from: 0, to: 2, scrollTop: 0 }])
})

test('旧几何(没有 kind / bounds)照旧只让位,不会回调 split', () => {
  const g = geo(); delete g.kind; delete g.len; delete g.bounds
  const owner = fakeOwner(g, 3)
  sd.start(touch(150), owner)
  sd.move(touch(350), owner) // 被拖块中线 350 越过段 1 中线 320:只让位
  assert.deepEqual(owner.calls, [['onStoryDragSlot', { target: 1 }]])
})

test('被拖的块自己不是落点;贴边自动滚屏时仍按滚动量折算手指位置', () => {
  const g = geo(); g.from = 1; g.key = 'p1'; g.y = 300; g.kind = ['', 'p', 'p']
  const owner = fakeOwner(g, 3)
  sd.start(touch(300), owner)
  sd.move(touch(320), owner) // 手指还在自己身上:不拆
  assert.ok(!owner.calls.some((c) => c[0] === 'onStoryDragSplit'))
  const g2 = geo(); g2.vb = 300; g2.max = 400 // 滚动区很矮,手指贴近下沿就开始滚
  const owner2 = fakeOwner(g2, 3)
  sd.start(touch(150), owner2)
  sd.move(touch(290), owner2)
  assert.equal(owner2.frames.length, 1, '贴边起了滚动帧')
  owner2.frames.shift()()
  assert.ok(owner2.getState().scroll > 0, '滚过了')
})
