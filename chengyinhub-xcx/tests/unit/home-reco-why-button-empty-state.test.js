// S2(2026-10-24 上线前快修):首页「编辑精选」区块为空 / 加载失败时,标题右侧「为什么看到」不得出现
// (owner 规则:空内容上方不挂标题动作)。master 复核:按钮已由 recoHero 门控,空态/失败态只在 !recoHero 时出,
// 两者互斥 —— 本测试把这条互斥钉住,防止以后有人把按钮改成常驻。
// 负控:把 index.wxml 里按钮的 wx:if="{{recoHero}}" 删掉 → 第一条用例变红。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const wxml = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8')
const section = wxml.slice(wxml.indexOf('id="sec-reco"'), wxml.indexOf('<cy-roam-resume-bar'))

test('「为什么看到」只在有精选卡(recoHero)时渲染', () => {
  const btn = (section.match(/<cy-btn[^>]*showRecommendationSource[^>]*>/) || [])[0]
  assert.ok(btn, '编辑精选区块里找不到「为什么看到」按钮')
  assert.match(btn, /wx:if="\{\{recoHero\}\}"/)
})

test('空态与失败态都只在 !recoHero 时出现,与按钮互斥', () => {
  const empty = (section.match(/<view[^>]*>\s*<cy-empty title="暂时没有精选主题"/) || [])[0]
  const failed = (section.match(/<view[^>]*>\s*<cy-error load-failure title="编辑精选加载失败"/) || [])[0]
  assert.ok(empty && /!recoHero/.test(empty), '空态门控应含 !recoHero')
  assert.ok(failed && /!recoHero/.test(failed), '失败态门控应含 !recoHero')
})

test('_buildRecoHero:精选为空时 recoHero 置空(按钮随之隐藏)', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/index/index.js'), 'utf8')
  const body = js.slice(js.indexOf('  _buildRecoHero()'), js.indexOf('  showRecommendationSource()'))
  assert.match(body, /if \(!items\.length\) \{\s*this\.setData\(\{ recoHero: null/)
})
