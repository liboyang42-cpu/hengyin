/* 2026-10-29 真机走查 lane l3-template-editor-club:V-04 / V-05 / V-11 / V-12 / V-13 / V-14 */
'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function rule(source, selector) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, '')
  const bodies = []
  for (const m of clean.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
    if (m[1].split(',').map((x) => x.trim()).includes(selector)) bodies.push(m[2])
  }
  assert.ok(bodies.length, `缺少规则 ${selector}`)
  return bodies.join(';')
}

test('V-04 俱乐部发帖入口第一枚是图片图标,不是空方块', () => {
  const wxml = read('pages/club/detail/index.wxml')
  const tools = wxml.slice(wxml.indexOf('class="post-composer-tools"'))
  const icons = Array.from(tools.slice(0, 600).matchAll(/<cy-icon class="post-composer-ico" name="([a-z-]+)"/g), (m) => m[1])
  assert.deepEqual(icons, ['image', 'pin-solid', 'flag'])
  assert.doesNotMatch(read('pages/club/detail/index.wxss'), /post-composer-sq/)
  assert.match(read('components/cy/icon/icons.wxss'), /\.cyi--image \{/, 'image 图标必须真实存在于图标集')
})

test('V-05 活动 tab 空态不套卡片,下移到与帖子 tab 空态同高', () => {
  const body = rule(read('pages/club/detail/index.wxss'), '.ev-empty')
  assert.doesNotMatch(body, /background|border-radius|padding/)
  assert.match(body, /margin-top:\s*calc\(var\(--cy-space-8\) \* 2 \+ var\(--cy-space-5\)\)/)
  assert.match(read('pages/club/detail/index.wxml'), /<cy-empty wx:else class="ev-empty" title="暂时俱乐部还没有活动"/)
})

test('V-11 模板详情:主按钮幽灵样式、文字「应用」,分享键保留', () => {
  const wxml = read('pages/templatedetail/templatedetail.wxml')
  assert.match(wxml, /<cy-btn class="xb-cta-btn \{\{ isMyScope \? '' : 'xb-cta-btn--share' \}\}" variant="\{\{ isMyScope \? 'primary' : 'ghost' \}\}" bindtap="goPrimary">\{\{ primaryLabel \}\}<\/cy-btn>/)
  assert.match(wxml, /<button class="xb-share" open-type="share"/)
  assert.doesNotMatch(wxml, /开始应用/)
  const js = read('pages/templatedetail/templatedetail.js')
  assert.match(js, /primaryLabel: '应用',/)
  assert.match(js, /primaryLabel: this\.data\.scope !== 'my' \? '应用' :/)
  assert.doesNotMatch(js, /'开始应用'/)
  assert.match(js, /function buildTplTabs\(\) \{\s*return \[TAB_DETAILS, TAB_CAPABILITIES\];/)
})

test('V-12 规则:一行里的 1. 2. 3. 拆成多条,运营提示不给玩家看', () => {
  const { splitRuleSteps } = require('../../pages/templatedetail/utils/templateDetail.js')
  const seed = '1. 找到店内指定拍照点。2. 拍下符合要求的照片。3. 上传照片后完成任务,生成分享素材。(运营提示:拍照要求必须具体,例如「包含店铺招牌」或「拍下今日限定甜品」,不要写「拍一张好看的照片」)'
  assert.deepEqual(splitRuleSteps(seed), ['找到店内指定拍照点。', '拍下符合要求的照片。', '上传照片后完成任务,生成分享素材。'])
  assert.deepEqual(splitRuleSteps('系统抽取三项任务；不要求额外消费。'), ['系统抽取三项任务；不要求额外消费。'])
  assert.deepEqual(splitRuleSteps('第一步\r\n第二步'), ['第一步', '第二步'])
  assert.deepEqual(splitRuleSteps('（运营提示：只有备注）'), [], '只剩备注时不出空规则卡')
  assert.deepEqual(splitRuleSteps('走 2.5 公里到第 3 站'), ['走 2.5 公里到第 3 站'], '不是从 1 起的数字不拆')

  const { stripOperatorNotes } = require('../../utils/rule-steps.js')
  assert.equal(stripOperatorNotes('a。(运营提示:x)'), 'a。')
  assert.equal(stripOperatorNotes('a。（运营备注：没闭合'), 'a。')
  assert.equal(stripOperatorNotes('(提示:保留)'), '(提示:保留)', '只去运营备注,别的括注原样')

  // 玩家面:节点卡规则同样去掉运营备注
  assert.match(read('pages/play/index.js'), /rule: stripOperatorNotes\(n\.ruleInstructions \|\| ''\)/)
  // 编辑器与详情页共用同一份拆分规则(主包 utils),不在分包里各写一份
  assert.match(read('pages/publish/temp/index.js'), /require\('\.\.\/\.\.\/\.\.\/utils\/rule-steps\.js'\)/)
  assert.ok(!fs.existsSync(path.join(ROOT, 'pages/publish/utils/publish/rule-steps.js')))
})

test('V-13 采用被媒体类拒绝:只有回包点名封面才清封面', () => {
  const { rejectionNamesCover } = require('../../pages/publish/utils/publish/cover-rejection.js')
  const cover = 'https://cdn.example/official/cover.jpg'
  const msg = '媒体来源未经审核，请重新上传'
  assert.equal(rejectionNamesCover({ code: 422, msg }, cover), false, '没点名 = 封面保留')
  assert.equal(rejectionNamesCover({ code: 422, msg, data: { field: 'storyImg' } }, cover), false)
  assert.equal(rejectionNamesCover({ code: 422, msg, data: { mediaUrl: 'https://cdn.example/other.jpg' } }, cover), false)
  assert.equal(rejectionNamesCover({ code: 422, msg, data: { field: 'imgUrl' } }, cover), true)
  assert.equal(rejectionNamesCover({ code: 422, msg, data: { mediaUrl: cover } }, cover), true)
  assert.equal(rejectionNamesCover({ code: 422, msg, data: { mediaUrl: cover } }, ''), false)

  const js = read('pages/publish/temp/index.js')
  const fn = js.slice(js.indexOf('_openWithUnusableMedia: function'), js.indexOf('inspectAdoptionTemplates()'))
  assert.match(fn, /if \(rejectionNamesCover\(res, this\.data\.formData\.imgUrl\)\) \{\s*this\.setData\(\{ 'formData\.imgUrl': '', coverMediaUnusable: true \}\);/)
  assert.match(fn, /if \(msg\) toast\(String\(msg\)\);/, '后端原话提示,不改写')
  assert.match(js, /this\._openWithUnusableMedia\(sourceData,res\)/)
  assert.match(read('pages/publish/temp/index.wxml'), /class="cg-cover-unusable" wx:if="\{\{coverMediaUnusable\}\}"/, '错误路径保留')
})

test('V-14 剧情故事时间节点:正文随内容撑高,时刻标签输入框不压扁', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  assert.match(wxml, /<textarea class="cg-beat-text"[^>]*\bauto-height\b/)
  const wxss = read('pages/publish/temp/index.wxss')
  const text = rule(wxss, '.cg-beat-text')
  assert.match(text, /min-height:\s*152rpx/)
  assert.doesNotMatch(text, /(^|[;\s])height:/, '固定高度会裁掉正文')
  const tag = rule(wxss, '.cg-beat-tagin')
  assert.match(tag, /height:\s*var\(--cy-btn-h\)/)
  assert.match(tag, /line-height:\s*var\(--cy-btn-h\)/)
  assert.match(tag, /padding:\s*0 var\(--cy-space-3\)/)
})

// ---- Round 2(2026-10-29 业主二审)----

test('V-11 R2 分享键是圆:两级选择器压过内置 button 宽度,宽高同为 --cy-btn-h', () => {
  const wxss = read('pages/templatedetail/templatedetail.wxss')
  const body = rule(wxss, '.xb-cta .xb-share')
  assert.match(body, /width:\s*var\(--cy-btn-h\)/)
  assert.match(body, /height:\s*var\(--cy-btn-h\)/)
  assert.match(body, /border-radius:\s*50%/)
  assert.match(body, /background:\s*var\(--xb-share-bg\)/)
  assert.match(rule(wxss, '.xb-cta .xb-share::after'), /border:\s*0/)
  // 负控:单类选择器会被 wx-button:not([size=mini]) 的 184px 压过,不许留着
  const clean = wxss.replace(/\/\*[\s\S]*?\*\//g, '')
  assert.doesNotMatch(clean, /(^|\})\s*\.xb-share\s*\{/)
})

test('V-24 「应用」与分享键同底色 / 文字色 / 无描边,只是更宽(玩法与主题模板同一页)', () => {
  const wxss = read('pages/templatedetail/templatedetail.wxss')
  assert.match(rule(wxss, '.xb-cta'), /--xb-share-bg:\s*rgba\(255, 255, 255, 0\.1\)/)
  const body = rule(wxss, '.xb-cta .xb-cta-btn--share')
  assert.match(body, /--cy-bg-glass:\s*var\(--xb-share-bg\)/)
  assert.match(body, /--cy-text-title:\s*var\(--xb-text\)/)
  assert.match(rule(wxss, '.xb-cta .xb-share'), /color:\s*var\(--xb-text\)/)
  // cy-btn 幽灵态正是用这两个变量上色,且没有描边 —— 变量下传才真的生效
  const btn = read('components/cy/btn/index.wxss')
  const ghost = rule(btn, '.btn--ghost')
  assert.match(ghost, /background:\s*var\(--cy-bg-glass\)/)
  assert.match(ghost, /color:\s*var\(--cy-text-title\)/)
  assert.doesNotMatch(ghost, /border/)
  assert.match(rule(wxss, '.xb-cta-btn'), /flex:\s*1/, '主按钮占满分享键以外的宽度')
})

test('V-11 R2 分节标题用 Fluent Emoji 3D 图标,文字不变', () => {
  const wxml = read('pages/templatedetail/templatedetail.wxml')
  const want = [
    ["{{ isTopicTemplate ? '剧情' : '简介' }}", '/pages/templatedetail/art/3d/book.png'],
    ['规则', '/images/publish-quick/interaction.png'],
    ['怎么验证', '/pages/templatedetail/art/3d/magnifier.png'],
    ['分类与适用场景', '/images/publish-quick/city.png'],
    ['所需物料', '/pages/templatedetail/art/3d/flashlight.png'],
    ["{{ isTopicTemplate ? '节点' : '包含内容' }}", '/images/publish-quick/explore.png'],
    ['创作者说', '/pages/templatedetail/art/3d/bulb.png'],
  ]
  for (const [title, src] of want) {
    const esc = (x) => x.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    assert.match(wxml, new RegExp('<image class="xb-section-ico" src="' + esc(src) + '" mode="aspectFit" aria-hidden="true" />\\s*<text>' + esc(title) + '</text>'), title)
    assert.ok(fs.existsSync(path.join(ROOT, src)), src + ' 不存在')
    assert.equal(fs.readFileSync(path.join(ROOT, src)).subarray(1, 4).toString(), 'PNG')
  }
  for (const old of ['info', 'route', 'check', 'gift', 'image']) {
    assert.doesNotMatch(wxml, new RegExp('<cy-icon name="' + old + '" size="30" />'), '旧线性图标 ' + old + ' 应已换掉')
  }
  assert.match(read('pages/templatedetail/art/3d/LICENSE'), /MIT License[\s\S]*Microsoft Corporation/)
  assert.match(read('pages/templatedetail/art/3d/README.md'), /fluentui-emoji/)
  assert.match(rule(read('pages/templatedetail/templatedetail.wxss'), '.xb-section-ico'), /width:\s*40rpx/)
})
