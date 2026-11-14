const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const pops = require('../../pages/play/utils/play-stage-pops.js')
const { keepNumerals, tidyText } = require('../../pages/play/utils/keep-numerals.js')
const chapterTrail = require('../../pages/play/utils/chapter-trail.js')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('站名只去掉开头序号,不再只剩「1」', () => {
  assert.equal(pops.stationLabel('1 · 老门'), '老门')
  assert.equal(pops.stationLabel('02. 外滩'), '外滩')
  assert.equal(pops.stationLabel('老门'), '老门')
  assert.equal(pops.stationLabel('3'), '3')
  const src = read('pages/play/index.js')
  assert.match(src, /'首达 · ' \+ \(stationLabel\(ordered\[0\]\.name\)/)
  assert.match(src, /const cut = tag\.indexOf\(' · '\)/)
})

test('完成页成就行只在第一个「 · 」处拆开', () => {
  const src = read('pages/play/index.js')
  const fn = src.match(/function achievementRows\(tags, ordered\) \{[\s\S]*?\n\}/)[0]
  const rows = new Function(fn + '; return achievementRows;')()({ 7: '首达 · 老门' }, [{ nodeId: 7, name: '1 · 老门' }])
  assert.equal(rows[0].sub, '老门')
})

test('判定文字:汉字数字不断行、句间双空格并成一个', () => {
  const out = tidyText('拍摄于一九一四年。  门牌清晰')
  assert.equal(out.replace(/⁠/g, ''), '拍摄于一九一四年。门牌清晰')   // 1113h:中文句号后的空格一并去掉
  assert.notEqual(out, '拍摄于一九一四年。门牌清晰')
  assert.equal(keepNumerals('一个人'), '一个人')
  assert.match(read('pages/play/components/playkit-photocheck/index.js'), /tidyText\(String\(lastReason/)
  assert.match(read('pages/play/utils/playkit-view.js'), /answerText: seg\.finished \? tidyText\(/)
})

test('答完只留一个「继续」:内嵌时只留故事流那一颗「继续」', () => {
  assert.match(read('pages/play/components/playkit-hangman/index.wxml'), /wx:if="\{\{solved && !inline\}\}"/)
  assert.match(read('pages/play/components/playkit-qa/index.wxml'), /inline && \(passed \|\| finished \|\| ctaLabel === '继续'\)/)
})

test('结算「继续」不去空故事页:只回看有正文的已完成章', () => {
  assert.equal(chapterTrail.hasReadableStory([{ type: 'node', nodeId: 1 }]), false)
  assert.equal(chapterTrail.hasReadableStory([{ type: 'node', nodeId: 1 }, { type: 'text', content: '后来' }]), true)
  assert.match(read('pages/play/utils/play-stage-pops.js'), /chapterTrail\.hasReadableStory\(/)
})

test('逐字猜词没有题面时用站名', () => {
  assert.match(read('pages/play/index.js'), /picked\.type === 'hangman' && !picked\.title && placeNode\) picked\.title = stationLabel\(/)
})

test('章节门:说明超长补省略号,章节印等文字解码完再量', () => {
  const src = read('pages/play/index.js')
  const fn = src.match(/function introLine\(desc\) \{[\s\S]*?\n\}/)[0]
  const introLine = new Function(fn + '; return introLine;')()
  const long = '这是一段很长很长的说明文字它一直写一直写一直写一直写一直写一直写一直写一直写一直写一直写一直写完了吗'
  assert.equal(introLine(long), long.slice(0, 40) + '…')
  assert.equal(introLine('一句话。'), '一句话。')
  const flip = read('pages/play/components/chapter-flip/index.js')
  assert.match(flip, /const decodeEnd = this\._decode\(/)
  assert.match(flip, /Math\.max\(fromEnd \? 800 : 600, decodeEnd \+ 60\)/)
})

test('浅色纸面:退出叉、打字、答对回执条读外观墨色,暗色外观不变', () => {
  const tokens = read('pages/play/look-tokens.wxss')
  assert.match(tokens, /look--paper\.look-palette--white[^{]*\{ --pk-embed-back:var\(--lk-ink\); --pk-embed-typed:var\(--lk-ink\); --pk-embed-fb-bg:/)
  assert.equal(read('pages/publish/fabu/look-tokens.wxss'), tokens)
  assert.match(read('pages/play/components/play-stage/index.wxss'), /\.skin-qadark:not\(\.st--verdict\) \.st__back \{ color: var\(--pk-embed-back, var\(--cy-color-play-accent\)\)/)
  const qa = read('pages/play/components/playkit-qa/index.wxss')
  assert.match(qa, /color: var\(--pk-embed-typed, var\(--ink\)\)/)
  assert.match(qa, /\.qa__fb--ok \{ background: var\(--pk-embed-fb-bg, var\(--cy-color-status-success-soft\)\)/)
})

test('拍照检查:新一次尝试与通过都清掉旧的上传失败', () => {
  const pc = read('pages/play/components/playkit-photocheck/index.js')
  assert.match(pc, /uploadFail: passed \? '' : this\.data\.uploadFail/)
  assert.match(pc, /busy: false, framing: false, uploadFail: ''/)
  assert.match(read('pages/play/components/playkit-photocheck/index.wxml'), /uploadFail && !verdict && !passed/)
})

test('本局档案照片说明单行省略,不再孤字换行', () => {
  assert.match(read('pages/play/index.wxss'), /\.life-arc__cap\{[^}]*text-overflow:ellipsis; white-space:nowrap/)
})
