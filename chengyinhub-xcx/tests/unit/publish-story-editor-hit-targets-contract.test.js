'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const WXML = path.join(ROOT, 'pages/publish/fabu/index.wxml')
const WXSS = path.join(ROOT, 'pages/publish/fabu/index.wxss')

function rule(source, selector) {
  const clean = source.replace(/\/\*[\s\S]*?\*\//g, '')
  const bodies = []
  for (const match of clean.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
    const selectors = match[1].split(',').map((item) => item.trim())
    if (selectors.includes(selector)) bodies.push(match[2])
  }
  assert.ok(bodies.length, `缺少 ${selector}`)
  return bodies.join('\n')
}

function assertContract(overrides = {}) {
  const wxml = overrides.wxml === undefined ? fs.readFileSync(WXML, 'utf8') : overrides.wxml
  const wxss = overrides.wxss === undefined ? fs.readFileSync(WXSS, 'utf8') : overrides.wxss

  for (const selector of ['.story-editor__back', '.story-editor__done', '.story-insert', '.story-insert__action', '.node-pill', '.node-sheet__done']) {
    assert.match(rule(wxss, selector), /(min-)?height:\s*var\(--cy-btn-h\)/, `${selector} 高度不足 44px`)
  }
  for (const selector of ['.story-block-x', '.pd-round-back', '.modal-close']) {
    const cssRule = rule(wxss, selector)
    assert.match(cssRule, /width:\s*var\(--cy-btn-h\)/, `${selector} 宽度不足 44px`)
    assert.match(cssRule, /height:\s*var\(--cy-btn-h\)/, `${selector} 高度不足 44px`)
  }

  for (const label of [
    '退出故事流编辑器', '完成故事流编辑', '添加文字块', '添加图片块', '添加音频块', '添加地点',
    '删除文字块', '删除图片块', '删除节点块', '关闭叙事编辑器',
    // 10-28 原型 A e-node：地点、玩法模板两行改用 L1 cy-list-row，可读名称由组件按「名称 + 当前值」生成。
    '完成玩法模板选择', '完成节点编辑', '确认创作模式',
    '关闭类别选择',
    '添加章节音频', '移除章节音频',
  ]) {
    assert.match(wxml, new RegExp(`aria-label="${label}"`), `缺少可读名称：${label}`)
  }
}

test('发布故事流、节点弹层和叙事返回动作均为 44px 且语义明确', () => {
  assertContract()
})

test('负控：故事块删除退回 48rpx 会判红', () => {
  const wxss = fs.readFileSync(WXSS, 'utf8').replace(
    /(\.story-block-x\s*\{[^}]*?)width:\s*var\(--cy-btn-h\);\s*height:\s*var\(--cy-btn-h\);/s,
    '$1width:48rpx; height:48rpx;',
  )
  assert.throws(() => assertContract({ wxss }), /宽度不足/)
})

test('负控：章节音频移除键名称丢失会判红', () => {
  const wxml = fs.readFileSync(WXML, 'utf8').replace(' aria-label="移除章节音频"', '')
  assert.throws(() => assertContract({ wxml }), /移除章节音频/)
})
