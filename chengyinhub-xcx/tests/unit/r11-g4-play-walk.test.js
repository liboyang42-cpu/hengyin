// 第十一轮 g4:rig 走查发现的游玩页问题。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')

test('预测「不选，继续」之后:卡面不再说「转到想选的那张，点它」,也不出「就选这个」', () => {
  const wxml = read('pages/play/components/playkit-predict/index.wxml')
  assert.match(wxml, /class="pd__hint" wx:if="\{\{!revealKind && !skipped\}\}"/)
  assert.match(wxml, /wx:elif="\{\{!skipped\}\}" class="g-btn/)
  const js = read('pages/play/components/playkit-predict/index.js')
  assert.match(js, /skipped: \{ type: Boolean, value: false \}/)
  assert.match(js, /if \(this\.data\.committed \|\| this\.data\.closed \|\| this\.data\.skipped\) return;/)
  assert.match(read('pages/play/components/playkit/index.wxml'), /skipped="\{\{kit\.skipped\}\}"/)
  const view = require(path.join(ROOT, 'pages/play/utils/playkit-view.js'))
  const src = read('pages/play/utils/playkit-view.js')
  assert.match(src, /shopAnswers: seg\.shopAnswers !== false,\n\s*\/\/[^\n]*\n\s*skipped: seg\.skipped === true,/)
  assert.ok(view)
})

test('预测故事流内嵌:提示行落到卡和按钮之间,不压在卡底', () => {
  assert.match(read('pages/play/components/playkit-predict/index.wxss'), /bottom: calc\(env\(safe-area-inset-bottom\) \+ var\(--pk-hint-bottom, 190rpx\)\)/)
  assert.match(read('pages/play/components/playkit/index.wxml'), /--pk-btn-bottom:40rpx;--pk-hint-bottom:140rpx/)
})

test('j3 F15:结局页「这一生」只给有角色建档的主题,其它主题说「本局」', () => {
  const wxml = read('pages/play/index.wxml')
  // 2026-11-10 l-play:真局夺到勋章时眉头写「本局完成 · 获得勋章」,其余仍按 F15:有角色建档说「这一生」,没有说「本局」
  assert.match(wxml, /previewEnding\.real \? \(previewEnding\.reward \? '本局完成 · 获得勋章' : \(previewEnding\.archive\.identity\.length \? '这一生的结局' : '本局完成'\)\)/)
  assert.match(wxml, /previewEnding\.archive\.identity\.length \? '这一生的档案' : '本局档案'/)
})

test('j4 F8:路线卡屏上预览按高度收、保持 3:4,首屏章节标题不被固定「继续」压住;状态栏下有深色带', () => {
  const wxss = read('pages/play/index.wxss')
  assert.match(wxss, /\.finsheet__routecv\{ --rc-w:min\(calc\(100vw - 2 \* var\(--cy-page-x\)\), calc\(\(100vh - 900rpx\) \* 3 \/ 4\)\); display:block; width:var\(--rc-w\); height:calc\(var\(--rc-w\) \* 4 \/ 3\);/)
  assert.match(wxss, /\.finsheet__shade\{ position:sticky; top:0;/)
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<view class="finsheet__shade" wx:if="\{\{routeCard\.on && !newLifeOS\.show\}\}" style="height:\{\{chrome\.contentTop\}\}px"><\/view><view class="finsheet__card"/)
  // 导出图仍由画布像素尺寸决定(routeCard.W × H),与屏上 CSS 尺寸无关
  assert.match(read('pages/play/index.js'), /cv\.width = routeCard\.W \* dpr; cv\.height = routeCard\.H \* dpr;/)
})
