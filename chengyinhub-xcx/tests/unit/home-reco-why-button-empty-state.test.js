// S2(2026-10-24 上线前快修)曾钉「为什么看到」只在有精选卡时出现。
// 2026-10-27 真机走查 P-02:owner 明确要求删掉这个按钮(连同弹窗),本文件改为钉「已删除」,
// 并保留空态 / 失败态仍只在 !recoHero 时出现的互斥约束。
// 负控:把 <cy-btn ... showRecommendationSource>为什么看到</cy-btn> 塞回编辑精选 → 第一条用例变红。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const wxml = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8')
const section = wxml.slice(wxml.indexOf('id="sec-reco"'), wxml.indexOf('id="sec-nearby"'))

test('P-02:编辑精选区块不再有「为什么看到」按钮', () => {
  assert.ok(section.length > 0, '找不到编辑精选区块')
  assert.doesNotMatch(section, /为什么看到|showRecommendationSource/)
  const js = fs.readFileSync(path.join(ROOT, 'pages/index/index.js'), 'utf8')
  assert.doesNotMatch(js, /showRecommendationSource|为什么看到这些主题/)
})

test('推荐与附近主题保留标题，空推荐、未定位和失败态必须明确区分', () => {
  assert.doesNotMatch(wxml, /暂时没有精选主题|你附近暂时没有可点亮的节点/)
  const head = wxml.match(/<view id="sec-reco"[^>]*>/)[0]
  assert.doesNotMatch(head, /wx:if=/, '推荐为空时仍显示区块与诚实空态')
  assert.match(section, /listLoaded\.recommendedTopicList && !listLoading\.recommendedTopicList && !listErr\.recommendedTopicList && !recoHero/)
  assert.match(section, /title="暂无推荐主题"/)
  const nearby = wxml.match(/<view id="sec-nearby"[^>]*>/)[0]
  assert.doesNotMatch(nearby, /wx:if=/, '未定位时仍提供定位入口')
  assert.match(wxml, /title="定位后查看附近主题" cta="定位查找" bind:cta="locateNearbyThemes"/)
  assert.match(wxml, /title="3 公里内暂无主题"/)
  const failed = (section.match(/<view[^>]*>\s*<cy-error load-failure title="推荐主题加载失败"/) || [])[0]
  assert.ok(failed && /!recoHero/.test(failed), '失败态门控应含 !recoHero')
})

test('_buildRecoHero:精选为空时 recoHero 置空', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/index/index.js'), 'utf8')
  const body = js.slice(js.indexOf('  _buildRecoHero()'), js.indexOf('  onHeroBannerTap(e)'))
  assert.match(body, /if \(!items\.length\) \{\s*this\.setData\(\{ recoHero: null/)
})
