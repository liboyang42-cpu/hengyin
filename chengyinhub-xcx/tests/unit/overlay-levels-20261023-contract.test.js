// 2026-10-23 业主定的弹层规范(原型 https://claude.ai/artifact/2yxBJV1mrTE7wjBuw6yRTg):
//   ① 底部弹窗三档 peek / half / full = 30% / 60% / 80%:贴内容高、上限 full,任何一档不越过胶囊;
//      打开后内容会变的弹窗 fixed 在所属档;大编辑器与筛选弹窗 fixed="full"。
//   ② 确认弹窗最高 60%,正文 scroll-view 内滚、按钮固定。
//   ③ 动作菜单里的破坏性动作标红。
//   ④ 可撤销提示条:取消置顶 / 删草稿 / 移出成员 —— 撤销窗口内不提交,到期 / 被顶掉 / 卸载才提交。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '')

function fresh(mod) { delete require.cache[mod]; return require(mod) }
function withGlobals(globals, fn) {
  const prev = {}
  for (const k of Object.keys(globals)) { prev[k] = global[k]; global[k] = globals[k] }
  try { return fn() } finally { for (const k of Object.keys(globals)) { if (prev[k] === undefined) delete global[k]; else global[k] = prev[k] } }
}
function loadComponent(rel) {
  let captured = null
  withGlobals({ Component: (o) => { captured = o }, Behavior: (o) => o, getApp: () => ({ globalData: {} }), wx: {} }, () => fresh(path.join(ROOT, rel)))
  const vm = Object.assign({}, captured.methods, {
    data: Object.assign({ reducedMotion: true }, captured.data),
    setData(p) { Object.assign(this.data, p) },
    triggerEvent() {},
  })
  return { vm, def: captured }
}

test('① 三档 token:30 / 60 / 80vh', () => {
  const tokens = read('style/tokens.wxss')
  assert.match(tokens, /--cy-comp-sheet-peek:\s*30vh;/)
  assert.match(tokens, /--cy-comp-sheet-half:\s*60vh;/)
  assert.match(tokens, /--cy-comp-sheet-full:\s*80vh;/)
})

test('① 每个弹层的高度上限都先过 full(80%)再过胶囊,不再有 70vh / 直贴胶囊的口径', () => {
  const files = [
    'components/cy/sheet/index.wxss', 'components/cy/scene-sheet/index.wxss', 'style/proto-sheet.wxss',
    'components/cy/date-sheet/index.wxss', 'pages/play/index.wxss', 'pages/play/components/advanced-game/index.wxss',
    'pages/publish/temp/index.wxss', 'subpackageP3/pages/badge-wall/index/index.wxss',
  ]
  for (const f of files) {
    const code = strip(read(f))
    // 胶囊通道只能出现在 min(var(--cy-comp-sheet-full), …) 里
    for (const m of code.matchAll(/var\(--cy-(?:comp-sheet-max-h|psheet-max-h),/g)) {
      const before = code.slice(Math.max(0, m.index - 40), m.index)
      assert.match(before, /min\(var\(--cy-comp-sheet-(?:full|half|peek)\),\s*$/, `${f}: 胶囊上限前面必须先过三档之一`)
    }
    assert.doesNotMatch(code, /min\(70vh/, `${f}: 70vh 半屏口径已并入三档`)
  }
})

test('① cy-sheet fixed 档:peek / half / full 三条规则,正文撑满剩余高度', () => {
  const { def } = loadComponent('components/cy/sheet/index.js')
  assert.deepEqual(def.properties.fixed, { type: String, value: '' })
  const wxml = read('components/cy/sheet/index.wxml')
  assert.match(wxml, /\{\{fixed \? 'sh--fixed sh--fixed-' \+ fixed : ''\}\}/)
  const wxss = strip(read('components/cy/sheet/index.wxss'))
  for (const lv of ['peek', 'half', 'full']) {
    assert.match(wxss, new RegExp(`\\.sh--fixed-${lv} \\.sh__panel \\{ height: min\\(var\\(--cy-comp-sheet-${lv}\\), var\\(--cy-comp-sheet-max-h, var\\(--cy-comp-sheet-max-height\\)\\)\\); \\}`))
  }
  assert.match(wxss, /\.sh--fixed \.sh__body \{ flex: 1 1 auto; min-height: 0; \}/)
})

test('① 大编辑器与筛选弹窗固定在 full', () => {
  const sites = [
    ['pages/publish/fabu/survival-editor.wxml', 'survivalEditor.show'],
    ['pages/publish/fabu/runtime-editor.wxml', 'runtimeEditor.show'],
    ['pages/publish/fabu/zero-hp-editor.wxml', 'runtimeZeroEditor.show'],
    ['pages/publish/fabu/runtime-structure-editor.wxml', 'structureEditor.show'],
    ['pages/play/theme-backpack.wxml', 'themeBackpackOpen'],
    ['pages/club/detail/index.wxml', 'settingsShow'],
    ['pages/searchmap/index.wxml', 'popVisible'],
    ['pages/search2/index.wxml', 'popVisible'],
    ['pages/merchant/customer/index.wxml', 'filterOpen'],
    ['pages/merchant/components/merchant-claim-sheet/index.wxml', 'show'],
  ]
  for (const [file, show] of sites) {
    const src = read(file)
    const tag = src.match(new RegExp(`<cy-sheet show="\\{\\{${show.replace(/\./g, '\\.')}[^>]*>`))
    assert.ok(tag, `${file}: 找不到 ${show} 那个 cy-sheet`)
    assert.match(tag[0], /\bfixed="full"/, `${file}: 内容多的弹窗必须 fixed="full"`)
  }
})

test('② 确认弹窗:最高 half 档,卡片不滚,正文是唯一滚动区', () => {
  const wxss = strip(read('components/cy/modal/index.wxss'))
  const card = wxss.match(/\.mo__card\s*\{[^}]*\}/)[0]
  assert.match(card, /max-height:\s*min\(var\(--cy-comp-sheet-half\), var\(--cy-comp-sheet-max-height\)\)/)
  assert.match(card, /display:\s*flex;[\s\S]*flex-direction:\s*column;[\s\S]*overflow:\s*hidden;/)
  assert.doesNotMatch(card, /overflow-y:\s*auto/, '卡片自己滚会把按钮一起滚走')
  assert.match(read('components/cy/modal/index.wxml'), /<scroll-view class="mo__content" scroll-y/)
  assert.match(wxss, /\.mo__close, \.mo__title, \.mo__consq, \.mo__btns \{ flex: none; \}/)
})

test('② 调 modal.show 的页面都挂了宿主,不再回落原生弹窗', () => {
  for (const p of ['subpackageTools/exploration-goals/index', 'pages/club/checkin-detail/index']) {
    assert.match(read(p + '.wxml'), /<cy-modal-host id="cy-modal-host" \/>/, p)
    assert.match(read(p + '.json'), /"cy-modal-host":/, p)
  }
})

test('③ 动作菜单:破坏性动作自动标红,一次选择列表不标', () => {
  const { vm, def } = loadComponent('components/cy/option-sheet/index.js')
  vm.data.immediate = true
  def.observers.items.call(vm, ['举报这条消息', '拉黑', '隐藏会话', { label: '删除', danger: false }])
  assert.deepEqual(vm.data.rows.map((r) => r.danger), [true, true, false, false])
  vm.data.immediate = false
  def.observers.items.call(vm, ['取消报名', '删除记录'])
  assert.deepEqual(vm.data.rows.map((r) => r.danger), [false, false], '待选项不是马上执行的动作')
  assert.match(strip(read('components/cy/option-sheet/index.wxss')), /\.os__row--danger \.os__label \{ color: var\(--cy-color-status-danger-on-interactive\); \}/)
})

test('④ cy-toast 撤销条:点撤销不提交;到期 / 被顶掉 / 卸载都提交一次', () => {
  const { vm, def } = loadComponent('components/cy/toast/index.js')
  const log = []
  const open = (n) => vm.show({ title: 't' + n, actionText: '撤销', onAction: () => log.push('undo' + n), onCommit: () => log.push('commit' + n) })
  open(1); assert.equal(vm.data.actionText, '撤销')
  vm.onAction()
  open(2); open(3)            // 2 被 3 顶掉 → 2 立即提交
  vm.hide()                   // 3 到期 → 提交
  open(4); def.lifetimes.detached.call(vm)   // 页面卸载 → 提交
  assert.deepEqual(log, ['undo1', 'commit2', 'commit3', 'commit4'])
  vm.show({ title: '普通提示' })
  assert.equal(vm.data.actionText, '', '普通 toast 没有按钮')
  vm._clearTimers()
})

test('④ toast.undo:没宿主时直接提交并给普通提示', () => {
  const native = []
  let committed = 0
  withGlobals({ wx: { showToast: (o) => native.push(o.title) }, getCurrentPages: () => [] }, () => {
    const toast = fresh(path.join(ROOT, 'utils/toast.js'))
    assert.equal(toast.undo('已取消置顶', { onCommit: () => { committed += 1 } }), false)
  })
  assert.equal(committed, 1)
  assert.deepEqual(native, ['已取消置顶'])
})

test('④ 三个可撤销动作都走 toast.undo,请求只在 onCommit 里', () => {
  const detail = read('pages/club/detail/index.js')
  const rm = detail.slice(detail.indexOf('  removeMember(e) {'), detail.indexOf('  _doRemoveMember('))
  assert.match(rm, /cyToast\.undo\(/)
  assert.doesNotMatch(rm, /remove-member/, '撤销窗口内不许发请求')
  assert.match(rm, /onCommit\(\) \{ that\._doRemoveMember\(memberId\); \}/)
  assert.doesNotMatch(read('pages/club/detail/index.wxml'), /id="dcMember"/)

  const talent = read('pages/talent/list/index.js')
  const trm = talent.slice(talent.indexOf('  removeClubMember(e) {'), talent.indexOf('  _doRemoveClubMember:'))
  assert.match(trm, /toast\.undo\(/)
  assert.doesNotMatch(trm, /remove-member/)

  const pin = detail.slice(detail.indexOf('  onTogglePin(e, forcePinned) {'), detail.indexOf('  onViewPostHistory('))
  assert.match(pin, /cyToast\.undo\('已取消置顶'/)
  assert.match(pin, /that\.onTogglePin\(\{ currentTarget: \{ dataset: \{ id: post\.id \} \} \}, true\)/, '撤销 = 按原样置顶回去')

  const compose = read('pages/square/components/cy/post-compose/index.js')
  const del = compose.slice(compose.indexOf('    onDeleteDraft(e) {'), compose.indexOf('    onPickImage() {'))
  const commitAt = del.indexOf('onCommit:')
  assert.ok(commitAt > 0)
  assert.equal(del.indexOf('removeDraft(') > commitAt, true, '删 storage 只能发生在 onCommit 里')
})
