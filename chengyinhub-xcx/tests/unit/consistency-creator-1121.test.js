// 2026-11-21 一致性收口 · 创作域(主题编辑器 fabu / 玩法编辑器 temp / 活动发布 / 模板库与详情 / 我的节点玩法)
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '')
const rule = (css, selector) => {
  const at = css.indexOf(selector + ' {')
  assert.ok(at >= 0, selector + ' 规则不存在')
  return css.slice(at, css.indexOf('}', at) + 1)
}

test('故事编辑器「已添加对白 / 撤销」条贴安全区之上,滚动尾部留出撤销条高度', () => {
  const wxss = read('pages/publish/fabu/index.wxss')
  assert.match(wxss, /\.story-undo \{ z-index: 260; bottom: calc\(var\(--cy-space-3\) \+ env\(safe-area-inset-bottom\)\); \}/)
  assert.match(wxss, /\.story-editor__bottom \{ height: calc\(var\(--cy-btn-h\) \+ var\(--cy-space-3\) \* 2 \+ env\(safe-area-inset-bottom\)\); \}/)
  const wxml = noComments(read('pages/publish/fabu/index.wxml'))
  assert.match(wxml, /<view class="story-editor__bottom"><\/view>\s*<\/scroll-view>/, '尾部留白在章节结束卡之后、滚动区之内')
})

test('编辑页「节点已删除 / 撤销」条停在底栏上沿之上,出现时尾部再让出一条', () => {
  const wxss = read('pages/publish/fabu/index.wxss')
  const undo = rule(wxss, '.pending-undo')
  // 底栏 .slopes-tabbar min-height 210rpx(已含安全区):撤销条必须整条在它之上
  assert.match(rule(wxss, '.slopes-tabbar'), /min-height: 210rpx;/)
  assert.match(undo, /bottom: calc\(210rpx \+ var\(--cy-space-2\)\);/)
  assert.doesNotMatch(undo, /bottom: calc\(var\(--cy-btn-h\) \+ var\(--cy-space-6\)/)
  assert.match(wxss, /\.slopes-sheet-bottom--undo \{\s*height: calc\(160rpx \+ var\(--cy-btn-h\) \+ var\(--cy-space-2\) \+ env\(safe-area-inset-bottom\)\);/)
  const wxml = noComments(read('pages/publish/fabu/index.wxml'))
  assert.match(wxml, /<view class="slopes-sheet-bottom \{\{pendingUndo\.show \? 'slopes-sheet-bottom--undo' : ''\}\}"><\/view>/)
  // 两条撤销动作都是 88rpx 命中区的按钮语义
  assert.match(wxml, /bindtap="undoPendingRemoval" aria-role="button" aria-label="撤销"/)
  assert.match(wxml, /bindtap="undoStoryStep" aria-role="button" aria-label="撤销"/)
  assert.match(rule(wxss, '.pending-undo__action'), /min-height: var\(--cy-btn-h\);[\s\S]*padding: 0 var\(--cy-space-3\);/)
})

test('玩法编辑器失败说明条浮在底栏上方时,滚动尾部同步让出', () => {
  const wxml = noComments(read('pages/publish/temp/index.wxml'))
  assert.match(wxml, /<view class="cg-scroll-pad \{\{!previewMode && !previewVisible && submitError \? 'cg-scroll-pad--err' : ''\}\}"><\/view>/)
  const wxss = read('pages/publish/temp/index.wxss')
  assert.match(wxss, /\.cg-scroll-pad--err \{ height: calc\(260rpx \+ var\(--cy-space-8\) \+ env\(safe-area-inset-bottom\)\); \}/)
  // 空态不带解释性副标题;采用未确认的「查看我的玩法」进 cy-error 的第二出口,不再是一行裸文字
  assert.match(wxml, /<cy-empty title="还没有添加玩法模块" cta="添加模块" bind:cta="openAddSheet" \/>/)
  assert.match(wxml, /secondary="\{\{loadErrorIsOperation \? '查看我的玩法' : ''\}\}" bind:secondary="inspectAdoptionTemplates"/)
  assert.doesNotMatch(wxml, /<view wx:if="\{\{loadErrorIsOperation\}\}" bindtap="inspectAdoptionTemplates"/)
  assert.doesNotMatch(wxml, /cg-load-state__hint/)
})

test('PR #871 主人定稿保持不动:置灰按钮点了去第一处缺项', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  assert.match(wxml, /bindtap="openPreview" bind:disabledtap="onPreviewDisabledTap"/)
  assert.match(wxml, /bindtap="publishGame" bind:disabledtap="onPreviewDisabledTap"/)
  assert.match(read('pages/publish/temp/editor-layout.js'), /主人定稿\(改之前先读\)/)
})

test('活动发布:失败说明叠进底部 CTA 栏时,页尾同步加高', () => {
  const wxml = read('pages/publish/activity/index.wxml')
  assert.match(wxml, /\{\{submitError \? 'fabu--err' : ''\}\}/)
  const wxss = read('pages/publish/activity/index.wxss')
  assert.match(wxss, /\.fabu\.fabu--err \{\s*padding-bottom: calc\(250rpx \+ var\(--cy-space-8\)\);/)
  assert.match(rule(wxss, '.modal-mask'), /background: var\(--cy-color-overlay\);/)
})

test('置灰按钮会说明原因:开售发布 / 新建优惠券', () => {
  const fabuWxml = read('pages/publish/fabu/index.wxml')
  assert.match(fabuWxml, /bindtap="onFePublish" bind:disabledtap="onFePublishDisabledTap"/)
  assert.match(read('pages/publish/fabu/index.js'), /onFePublishDisabledTap\(\) \{[\s\S]*?cyToast\(panel\.hint \|\| '还有位置待承接'\);/)
  const rs = read('pages/publish/components/reward-selector/index.wxml')
  assert.match(rs, /bindtap="onCreateSubmit" bind:disabledtap="onCreateDisabledTap"/)
  assert.match(read('pages/publish/components/reward-selector/index.js'), /onCreateDisabledTap\(\) \{[\s\S]*?couponForm\.validate\(this\.data\.createForm\);\s*if \(!check\.ok\) cyToast\(check\.message\);/)
})

test('注册属性绑定:原生 <button> 换成 cy-btn,文案去掉内部用语', () => {
  const dir = 'pages/publish/components/attribute-binding-editor/'
  const wxml = read(dir + 'index.wxml')
  assert.doesNotMatch(wxml, /<button\b/)
  assert.match(wxml, /<cy-btn class="binding__act" wx:if="\{\{ready && !readOnly\}\}" variant="primary" bindtap="confirm">确认启用<\/cy-btn>/)
  assert.match(wxml, /variant="secondary" bindtap="refresh">重新读取<\/cy-btn>/)
  assert.match(wxml, /variant="ghost" bindtap="manage">调整定义<\/cy-btn>/)
  assert.equal(JSON.parse(read(dir + 'index.json')).usingComponents['cy-btn'], '/components/cy/btn/index')
  assert.doesNotMatch(read(dir + 'index.wxss'), /\b16rpx\b|80rpx/)
})

test('九宫格上传器只读设计 token,与主题编辑器节点弹窗同一套空槽', () => {
  const wxss = read('pages/publish/styles/photo-grid.wxss')
  const lines = wxss.split('\n').filter(l => !/ds-ok/.test(l) && !/^\s*(\/\*|\*|<)/.test(l))
  for (const l of lines) assert.doesNotMatch(l, /#[0-9a-fA-F]{3,8}\b|rgba?\(/, l)
  assert.match(rule(wxss, '.pgrid-add'), /border: 4rpx dashed var\(--cy-color-border-strong\);/)
  assert.match(rule(wxss, '.pgrid-add-plus'), /color: var\(--cy-color-text-tertiary\);/)
})

test('首屏加载走骨架,回包前不抢先露空态', () => {
  const my = read('subpackageMember/mytemplate/mytemplate.wxml')
  assert.match(my, /<cy-skeleton wx:if="\{\{\(loading \|\| !listLoaded\) && !errorMsg && list\.length === 0\}\}" type="card" count="3" \/>/)
  assert.match(my, /<cy-empty wx:if="\{\{!loading && listLoaded && !errorMsg && list\.length === 0\}\}"/)
  assert.doesNotMatch(my, /kind="loading"/)
  assert.equal(JSON.parse(read('subpackageMember/mytemplate/mytemplate.json')).usingComponents['cy-skeleton'], '/components/cy/skeleton/index')
  const js = read('subpackageMember/mytemplate/mytemplate.js')
  assert.match(js, /listLoaded: false,/)
  assert.match(js, /else that\.setData\(\{ loading: false, listLoaded: true \}\);/)
  const fabu = noComments(read('pages/publish/fabu/index.wxml'))
  assert.match(fabu, /<cy-skeleton wx:if="\{\{publicTempState === 'loading' && !publicTempList\.length\}\}" type="list" count="3" \/>/)
  assert.doesNotMatch(fabu, /加载中…<\/text>/)
  assert.doesNotMatch(fabu, /pd-edit-gate__hint/)
})

test('主题模板预览的错误态收进 cy-empty,一个动作', () => {
  const wxml = read('pages/templatedetail/topic-preview/index.wxml')
  assert.match(wxml, /<cy-empty wx:if="\{\{ errorRefused \}\}" kind="not-found" title="\{\{ errorMsg \}\}" cta="返回" bind:cta="goBack" \/>/)
  assert.match(wxml, /<cy-empty wx:else kind="error" title="\{\{ errorMsg \}\}" cta="重试" bind:cta="load" \/>/)
  assert.doesNotMatch(wxml, /tp-state__btn/)
  assert.doesNotMatch(read('pages/templatedetail/topic-preview/index.wxss'), /tp-state__(btn|t)\b/)
})

test('创作域所有可点 view/text 都带按压反馈与按钮语义', () => {
  const files = [
    'pages/publish/activity/index.wxml', 'pages/publish/components/ai-assist/index.wxml',
    'pages/publish/fabu/step3.wxml', 'pages/publish/fabu/topic-detail-sheet.wxml',
    'pages/publish/temp/index.wxml', 'pages/templatedetail/templatedetail.wxml',
    'pages/templatedetail/topic-detail/index.wxml', 'subpackageMember/mytemplate/mytemplate.wxml',
  ]
  for (const f of files) {
    const src = noComments(read(f))
    const tags = src.match(/<(view|text|image)\b(?:[^>"']|"[^"]*"|'[^']*')*>/g) || []
    for (const t of tags) {
      const tap = t.match(/\b(?:bind|catch):?tap="([^"]*)"/)
      if (!tap || tap[1] === 'noop' || tap[1] === '') continue
      assert.match(t, /hover-class=/, f + ' ' + t.slice(0, 120))
      assert.match(t, /aria-role=/, f + ' ' + t.slice(0, 120))
    }
  }
  const fabu = read('pages/publish/fabu/index.wxml')
  assert.match(fabu, /catchtap="selectNode" aria-role="button" aria-label="第 \{\{\(nodeIndex \+ 1\) \|\| 1\}\} 个节点"/)
  assert.match(read('pages/publish/temp/index.wxml'), /bindtap="uploadStoryBeatImg" aria-role="button" aria-label="添加图片"/)
})
