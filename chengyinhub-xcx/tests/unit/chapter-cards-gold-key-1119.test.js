// 2026-11-19 业主两屏:
// A) 主题详情「节点介绍」改成一章一张卡(章节自己的图 + 含 N 个玩法 · 约 M 分钟),点开原地展开本章介绍,结局章不出卡;
// B) 通行证钥匙开场改金色、整屏铺满(背景铺到状态栏与透明导航后面,钥匙画框按机型居中且完整可见)。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const cards = require('../../pages/topic/utils/chapter-cards.js')
const { stageBox } = require('../../subpackagePass/reveal/layout.js')

const resolve = (src) => (src.startsWith('http://evil') ? '' : 'https://cdn.trusted/' + src.replace(/^\/+/, ''))

test('章节卡图:img_arr 取第一张(逗号串 / JSON 数组 / 真数组),空时退回主题封面,再退回占位图', () => {
  assert.equal(cards.firstImage('a.jpg, b.jpg'), 'a.jpg')
  assert.equal(cards.firstImage('["x.png","y.png"]'), 'x.png')
  assert.equal(cards.firstImage('[{"url":"u.png"}]'), 'u.png')
  assert.equal(cards.firstImage(['', 'z.png']), 'z.png')
  assert.equal(cards.firstImage(null), '')
  assert.equal(cards.firstImage('null'), '')
  const list = cards.buildChapterCards([
    { id: 1, name: '序章', templateCount: 2, totalTime: 600, imgArr: 'ch1.jpg,ch1b.jpg' },
    { id: 2, name: '第二章', templateCount: 1, totalTime: 600, imgArr: '' },
    { id: 3, name: '第三章', templateCount: 1, totalTime: 600, imgArr: 'http://evil/x.png' },
  ], 'cover.jpg,cover2.jpg', resolve)
  assert.deepEqual(list.map((c) => c.img), ['https://cdn.trusted/ch1.jpg', 'https://cdn.trusted/cover.jpg', 'https://cdn.trusted/cover.jpg'])
  const bare = cards.buildChapterCards([{ id: 9, name: 'a', templateCount: 1, totalTime: 60 }], '', resolve)
  assert.equal(bare[0].img, cards.FALLBACK_COVER)
})

test('时长:total_time 是秒按分钟四舍五入;为 0 时用本章玩法时长求和;都没有就不写时长', () => {
  assert.equal(cards.chapterMinutes({ totalTime: 1800 }), 30)
  assert.equal(cards.chapterMinutes({ totalTime: 1649 }), 27)
  assert.equal(cards.chapterMinutes({ totalTime: 1650 }), 28)
  assert.equal(cards.chapterMinutes({ totalTime: 20 }), 1, '不足半分钟也不写成 0 分钟')
  assert.equal(cards.chapterMinutes({ totalTime: 0, nodes: [{ cmsMemberTemplate: { duration: 10 } }, { cmsMemberTemplate: { duration: 15 } }, {}] }), 25)
  assert.equal(cards.chapterMinutes({ totalTime: null, nodes: [{}] }), 0)
  assert.equal(cards.metaText({ templateCount: 3, totalTime: 1800 }), '含 3 个玩法 · 约 30 分钟')
  assert.equal(cards.metaText({ templateCount: 3, totalTime: 0 }), '含 3 个玩法')
  assert.equal(cards.metaText({ templateCount: 0, totalTime: 600 }), '约 10 分钟')
})

test('结局章不出卡:名字以「结局」开头,或玩法数与时长都为 0;其余按 sort_id 排序', () => {
  assert.equal(cards.isEnding({ name: '结局 · 黎明', templateCount: 2, totalTime: 600 }), true)
  assert.equal(cards.isEnding({ name: '尾声', templateCount: 0, totalTime: 0 }), true)
  assert.equal(cards.isEnding({ name: '尾声', templateCount: 0 }), true)
  assert.equal(cards.isEnding({ name: '第一章', templateCount: 0, totalTime: 300 }), false)
  const list = cards.buildChapterCards([
    { id: 3, sortId: 3, name: '结局A', templateCount: 1, totalTime: 60 },
    { id: 2, sortId: 2, name: '第二章', templateCount: 2, totalTime: 1200, description: '  去码头  ' },
    { id: 1, sortId: 1, name: '第一章', templateCount: 1, totalTime: 600, description: 'null' },
    { id: 4, sortId: 4, name: '彩蛋', templateCount: 0, totalTime: 0 },
  ], 'cover.jpg', resolve)
  assert.deepEqual(list.map((c) => c.key), ['1', '2'])
  assert.deepEqual(list.map((c) => c.description), ['', '去码头'])
  assert.equal(list[1].meta, '含 2 个玩法 · 约 20 分钟')
})

test('展开开关:点一张开一张,再点收起,点别的换过去', () => {
  assert.equal(cards.toggleOpen('', '1'), '1')
  assert.equal(cards.toggleOpen('1', '1'), '')
  assert.equal(cards.toggleOpen('1', '2'), '2')
  assert.equal(cards.toggleOpen('1', ''), '')
})

test('详情页节点介绍按章节卡渲染,点卡原地展开,展开动效读 motion token 并尊重减弱动态', () => {
  const wxml = read('pages/topic/index/index.wxml')
  const sec = wxml.slice(wxml.indexOf('<view class="det4" wx:if="{{ info.chapterCards.length }}">'), wxml.indexOf('<view class="det3">'))
  assert.ok(sec.length > 0)
  assert.match(sec, /wx:for="\{\{ info\.chapterCards \}\}" wx:key="key"/)
  assert.match(sec, /bindtap="onChapterCardTap"/)
  assert.match(sec, /mode="aspectFill" class="pic"/)
  assert.doesNotMatch(sec, /cmsMemberTemplate/, '不再按站点的玩法封面出卡')
  const js = read('pages/topic/index/index.js')
  assert.match(js, /chapterCards\.buildChapterCards\(res\.data\['chaptersList'\], res\.data\['imgUrl'\], \(src\) => app\.getImgUrl\(src\)\)/)
  assert.match(js, /onChapterCardTap\(e\) \{/)
  const css = read('pages/topic/index/index.wxss')
  assert.match(css, /\.chap-card\.is-open \.chap-card__desc \{[^}]*var\(--cy-motion-expand\) var\(--cy-ease-emphasized-decelerate\)/)
  assert.match(css, /\.chap-card__desc \{[^}]*var\(--cy-motion-collapse\) var\(--cy-ease-accelerate\)/)
  assert.doesNotMatch(css.slice(css.indexOf('.topic .chap-card__desc')), /transition:[^;}]*max-height/, '展开只动合成层,不动画布局属性')
  assert.match(css, /\.topic--reduced \.chap-card__desc[^{]*\{ transition: none; \}/)
  assert.match(css, /\.chap-card \.pic \{ background-color: var\(--cy-color-play-surface-solid\); \}/)
})

test('钥匙开场金色:渐变是深铜到暖金,不再是暖橙', () => {
  const css = read('subpackagePass/reveal/index.wxss')
  const grad = /\.ky__grad \{[^}]*\}/.exec(css)[0]
  for (const c of ['#2A1E08', '#8A6414', '#D4A72C', '#F2CF63']) assert.ok(grad.includes(c), c)
  for (const c of ['#3A1408', '#9A3310', '#E0541C', '#FF6F2D']) assert.ok(!css.includes(c), c)
  assert.match(css, /\.ky__btn \{[^}]*background: #FFFFFF; color: #111111;/)
})

test('钥匙开场全屏:舞台铺满视口,导航透明叠在上面,画框按机型居中且完整', () => {
  const css = read('subpackagePass/reveal/index.wxss')
  assert.match(css, /\.ky \{ position: relative; min-height: 100vh; height: 100vh; overflow: hidden;/)
  assert.match(css, /\.ky__stage \{ position: absolute; left: 0; top: 0; right: 0; bottom: 0;/)
  assert.doesNotMatch(css, /height: 1000rpx/)
  const wxml = read('subpackagePass/reveal/index.wxml')
  assert.match(wxml, /<cy-nav-bar overlay="\{\{!merchant\}\}" plain="\{\{merchant\}\}" custom-back bind:back="goBack" \/>/)
  assert.equal((wxml.match(/w="\{\{box\.w\}\}" h="\{\{box\.h\}\}"/g) || []).length, 2)
  assert.match(read('subpackagePass/reveal/index.js'), /box: stageBox\(win\)/)
  for (const win of [{ windowWidth: 375, windowHeight: 667 }, { windowWidth: 393, windowHeight: 852 }, { windowWidth: 430, windowHeight: 932 }, { windowWidth: 360, windowHeight: 560 }]) {
    const b = stageBox(win)
    assert.equal(b.w * 4, b.h * 3, '保持烤帧 3:4')
    assert.ok(b.w <= 750 && b.h <= 1000, '不放大到糊')
    assert.ok(b.top + b.h <= Math.ceil(b.vh * 0.78), '画框在文字区上方,完整可见')
    assert.equal(b.left * 2 + b.w, 750, '水平居中')
  }
  const tall = stageBox({ windowWidth: 393, windowHeight: 852 })
  assert.ok(tall.top > 100, '高屏把钥匙放到钥匙区中间,不贴顶')
  const short = stageBox({ windowWidth: 360, windowHeight: 560 })
  assert.ok(short.h < 1000 && short.top < 8, '矮屏缩小画框,贴着钥匙区')
})
