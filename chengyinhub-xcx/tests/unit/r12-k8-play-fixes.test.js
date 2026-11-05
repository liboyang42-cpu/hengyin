/* 第十二轮 k8-play(j3 第二轮复查,k7 已修 N4 N1 N2 N3 的一部分;这里补余下的):
   N2 猜数字带结果反馈没过时,终局屏不给真值 —— 不带反馈的同一猜会显示「你猜 X · 实际 Y」。
   N7 结果反馈判定屏整屏饱和红 / 绿;第二屏多一行「原结果已保留…」说明,外加得分 0 / 已完成 0 项假零。 */
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '../..')
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')

const estimateView = (status, seg) => ({ sessionId: 9, version: 2, status, canRetry: true, plainOutcome: true,
  playKit: { estimate: Object.assign({ question: '桥有多长', unit: '米', min: 0, max: 1000 }, seg) } })

test('N2:猜数字没过的终局屏给出你猜的数和真值', () => {
  const kit = pickPlayKit(estimateView('FAILED', { submitted: true, tier: 'MISS', guess: 120, answer: 452.5 }), new Date())
  assert.equal(kit.terminal, true)
  assert.equal(kit.terminalDetail, '你猜 120 米 · 实际 452.5 米')
})

test('N2:没交过(服务端不给真值)就不出这一行', () => {
  const kit = pickPlayKit(estimateView('FAILED', { submitted: false, tier: '' }), new Date())
  assert.equal(kit.terminal, true)
  assert.ok(!kit.terminalDetail)
  const live = pickPlayKit(estimateView('ACTIVE', { submitted: false }), new Date())
  assert.equal(live.terminalDetail, undefined)
})

test('N7:终局屏不再有「原结果已保留」说明行,得分 / 完成项为 0 不显示', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit/index.wxml'), 'utf8')
  const tpl = wxml.slice(0, wxml.indexOf('</template>'))
  assert.ok(!/<text[^>]*>[^<]*原结果已保留/.test(tpl))
  assert.match(tpl, /kit\.sessionScore > 0/)
  assert.match(tpl, /kit\.completedUnits > 0/)
  assert.match(tpl, /kit\.terminalDetail/)
})

test('N7:独立弹出的结果反馈走暗底,判定颜色只落在对勾和两个字上', () => {
  const css = fs.readFileSync(path.join(ROOT, 'pages/play/components/play-verdict/index.wxss'), 'utf8')
  const rule = (sel) => { const i = css.indexOf(sel + ' {'); assert.ok(i >= 0, sel); return css.slice(i, css.indexOf('}', i)) }
  assert.match(rule('.pv--closable .pv__fill'), /--cy-color-bg-page/)
  assert.match(rule('.pv--closable .pv__word'), /--cy-color-status-danger/)
  assert.match(rule('.pv--closable .pv__cta'), /--cy-color-bg-elevated/)
})
