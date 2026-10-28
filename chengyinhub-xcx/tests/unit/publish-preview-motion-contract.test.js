const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function hasPreviewMotionContract(wxml, wxss) {
  const panels = (wxml.match(/class="[^"]*\bcg-pv-panel\b[^"]*"/g) || []).length
  /* 2026-09-11 玩法模块改成单选之后,这一页不再有 .cg-tile(完成方式那串 tile
     与问答的「怎么答」三选一一起退场),按压反馈的承载换成选择玩法那张半屏里的行。
     判据跟着换,坏掉的形态照样红:见下面 hover-class 那条。 */
  const oneShotRules = ['.cg-pv-panel', '.cg-rw-cfg']
    .every((selector) => {
      const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const rule = wxss.match(new RegExp(`${escaped}\\s*\\{[^}]*animation:[^;}]+;`, 'm'))
      return Boolean(rule && /var\(--cy-motion-/.test(rule[0]) && /\bboth\b/.test(rule[0]) && !/\binfinite\b/.test(rule[0]))
    })
  // 2026-10-28 p81 业主「整体页面重新设计」:顶部圆点换成分段进度条(走到的段都亮)
  return /class="cg-pv-seg \{\{ pvStep >= idx \? 'on' : '' \}\}"/.test(wxml)
    && panels >= 5
    && /class="cg-pv-medal-ico"/.test(wxml)
    && !/cg-pv-roadmap|cg-pv-medal-coin|cg-pv-medal-face--back/.test(wxml)
    // 2026-10-22 选择玩法改成半屏标签条(Figma 胶囊):按压反馈的承载换成玩法标签
    && /class="cg-tag-pill[^"]*"/.test(wxml)
    && /bindtap="pickR5GameTag"[^>]*hover-class="cy-pressed"/.test(wxml)
    && /\.cg-pv-seg\s*\{[^}]*transition:[^;}]*var\(--cy-motion-/s.test(wxss)
    && /\.cg--reduced-motion[^}]*animation:\s*none\s*!important/s.test(wxss)
    && oneShotRules
}

test('玩家预览分段进度与勋章 UI，只增加一次性步骤和选择反馈', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  const wxss = read('pages/publish/temp/index.wxss')
  assert.equal(hasPreviewMotionContract(wxml, wxss), true)
})

test('负控：把一次性预览动效改回 infinite 时契约必须判红', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  const wxss = read('pages/publish/temp/index.wxss')
  const mutated = wxss.replace(/\bboth\b/g, 'infinite')
  assert.notEqual(mutated, wxss, '负控必须真实改动 animation iteration/fill')
  assert.equal(hasPreviewMotionContract(wxml, mutated), false)
})
