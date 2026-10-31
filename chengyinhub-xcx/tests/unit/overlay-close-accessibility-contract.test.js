'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function read(relative) {
  return fs.readFileSync(path.join(ROOT, relative), 'utf8')
}

function rule(source, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = source.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  assert.ok(match, `缺少样式 ${selector}`)
  return match[1]
}

function assertContract(overrides = {}) {
  const source = (file) => overrides[file] === undefined ? read(file) : overrides[file]
  const publishWxml = source('components/cy/publish-sheet/index.wxml')
  const publishWxss = source('components/cy/publish-sheet/index.wxss')
  assert.match(publishWxml, /ps__quick-action--return"[^>]*aria-role="button"[^>]*aria-label="返回发布类型"/)
  assert.match(publishWxml, /ps__quick-action--return"[^>]*aria-role="button"[^>]*aria-label="返回"/)
  assert.match(rule(publishWxss, '.ps__quick-action'), /min-height:\s*var\(--cy-comp-publish-trigger\)/)

  const successWxss = source('pages/publish/components/creation-success/index.wxss')
  assert.match(rule(successWxss, '.cs__close'), /width:\s*var\(--cy-btn-h\)/)
  assert.match(rule(successWxss, '.cs__close'), /height:\s*var\(--cy-btn-h\)/)

  const drawerWxml = source('pages/topic/components/project-drawer/index.wxml')
  const drawerWxss = source('pages/topic/components/project-drawer/index.wxss')
  const drawerJs = source('pages/topic/components/project-drawer/index.js')
  const hostWxml = source('pages/topic/components/project-host/index.wxml')
  assert.match(drawerWxml, /class="dw-close"[^>]*aria-role="button"[^>]*aria-label="关闭"/)
  assert.match(rule(drawerWxss, '.dw-close'), /width:\s*var\(--cy-btn-h\)/)
  assert.match(rule(drawerWxss, '.dw-close'), /height:\s*var\(--cy-btn-h\)/)
  // CU-C-126:「承接商家」原来用 headerVariant="action" 的「取消/完成」头,两颗都只执行 onClose,
  // 让人误以为招商已经完成。现在抽屉只剩「标题 + 右上 ✕」一种头,退出通道唯一。
  assert.doesNotMatch(drawerWxml, /dw-act|dw-head--action/, '抽屉里不该再有取消/完成那一对假动作')
  assert.doesNotMatch(drawerJs, /headerVariant:\s*\{\s*type/, 'headerVariant 属性已经废弃，不该留在组件接口上')
  // 锚在 <project-drawer ...> 开标签上,不要用裸 headerVariant="action" 全文匹配:
  // host 文件里有一行注释正解释「原来 headerVariant=action 为什么废」,全文匹配会命中注释假红。
  assert.doesNotMatch(hostWxml, /<project-drawer[^>]*headerVariant="action"/, '承接商家必须回到默认标题头')

  // 2026-10-27 真机走查 P-12(owner 新说法，取代 CU-M-157「遮罩从胶囊下方开始」):
  // 遮罩要盖住状态栏和导航栏、铺满整屏,否则顶部漏出一行没被模糊的列表(p85)。
  // 2026-10-22 业主裁决(L09):右上角 ✕ 撤掉,退出只走首屏底部「返回」与点遮罩。
  assert.match(publishWxml, /class="ps__mask"[^>]*bindtap="onMask"/)
  assert.doesNotMatch(publishWxml, /class="ps__mask"[^>]*style="[^"]*top:/, 'ps__mask:遮罩不得再从胶囊下方开始(P-12 铺满整屏)')
  assert.match(rule(publishWxss, '.ps__mask'), /inset:\s*0/, 'ps__mask:遮罩必须 inset:0 铺满')
  assert.doesNotMatch(publishWxml, /class="ps__close"|name="close-sm"/, 'ps__close:发布菜单不该再有右上角 ✕(业主裁决 2026-10-22)')
  assert.match(publishWxml, /ps__quick-action--return"[^>]*bindtap="onClose"[^>]*aria-label="返回"/, '首屏「返回」必须仍能关闭菜单')
}

test('复用弹层的返回/关闭/完成动作具有 44px 热区和按钮语义', () => {
  assertContract()
})

test('负控：任一关闭动作缩回 64rpx 会判红', () => {
  const file = 'components/cy/publish-sheet/index.wxss'
  const broken = read(file).replace('min-height: var(--cy-comp-publish-trigger);', 'min-height: 64rpx;')
  assert.throws(() => assertContract({ [file]: broken }), /min-height/)
})

test('负控：移除项目抽屉关闭语义会判红', () => {
  const file = 'pages/topic/components/project-drawer/index.wxml'
  const broken = read(file).replace(
    /(<view class="dw-close"[^>]*?) aria-role="button" aria-label="关闭"/,
    '$1',
  )
  assert.throws(() => assertContract({ [file]: broken }), /dw-close/)
})

test('负控：CU-C-126 把「取消/完成」那对假动作头塞回抽屉必须判红', () => {
  const file = 'pages/topic/components/project-drawer/index.wxml'
  const head = '  <view class="dw-head">'
  const broken = read(file).replace(head, head + '\n    <text class="dw-act dw-act--cancel" catchtap="onClose">取消</text>')
  assert.notEqual(broken, read(file), '负控变异注入失败:抽屉标题头锚点要先同步')
  assert.throws(() => assertContract({ [file]: broken }), /假动作/)
})

test('负控：CU-C-126 承接商家重新挂上 headerVariant="action" 必须判红', () => {
  const file = 'pages/topic/components/project-host/index.wxml'
  const broken = read(file).replace(
    '<project-drawer show="{{ merchantSheet.show }}" title="招商" actions=',
    '<project-drawer show="{{ merchantSheet.show }}" title="招商" headerVariant="action" actions=',
  )
  assert.notEqual(broken, read(file), '负控变异注入失败:承接商家开标签要先同步')
  assert.throws(() => assertContract({ [file]: broken }), /默认标题头/)
})

const PUBLISH_WXML = 'components/cy/publish-sheet/index.wxml'
const PUBLISH_WXSS = 'components/cy/publish-sheet/index.wxss'

test('负控：把右上角 ✕ 塞回发布菜单会判红(业主裁决 2026-10-22)', () => {
  const broken = read(PUBLISH_WXML).replace(
    '  <view class="ps__quick" catchtap',
    '  <view class="ps__close" bindtap="onClose"><cy-icon name="close-sm" size="44" /></view>\n  <view class="ps__quick" catchtap',
  )
  assert.notEqual(broken, read(PUBLISH_WXML), '负控未命中 ps__quick 锚点')
  assert.throws(() => assertContract({ [PUBLISH_WXML]: broken }), /ps__close/)
})

test('负控：P-12 遮罩重新截在胶囊下方会判红', () => {
  const broken = read(PUBLISH_WXML).replace('class="ps__mask" catchtouchmove', 'class="ps__mask" style="top:{{chrome.contentTop}}px" catchtouchmove')
  assert.notEqual(broken, read(PUBLISH_WXML), '负控未命中遮罩锚点')
  assert.throws(() => assertContract({ [PUBLISH_WXML]: broken }), /ps__mask/)
})
