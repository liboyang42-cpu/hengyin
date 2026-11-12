const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

// x-fix7 R4-5/N-11：通关卡长标题不折行 → 排到卡外，两端被切成字影。根因是解码字 .cf__c 的 white-space:pre。
const css = fs.readFileSync(path.join(__dirname, '../../pages/play/components/chapter-flip/index.wxss'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')

function rule(selector) {
  const out = []
  css.replace(/([^{}]+)\{([^{}]*)\}/g, (m, sel, body) => {
    if (sel.split(',').map((x) => x.trim()).includes(selector)) out.push(body)
    return m
  })
  return out.join(';')
}

test('通关卡的解码字可折行（pre-wrap），不再整行 pre 排出卡外', () => {
  assert.match(rule('.cf__c'), /white-space:\s*pre\b(?!-)/, '开场门沿用 pre')
  assert.match(rule('.cf--finale .cf__c'), /white-space:\s*pre-wrap/)
  assert.match(rule('.cf--finale .cf__t'), /word-break:\s*break-all/)
})

test('通关卡文案区只裁横向，不恢复会切半底部标签的 overflow:hidden', () => {
  const body = rule('.cf--finale .cf__copy')
  assert.match(body, /overflow-x:\s*clip/)
  assert.doesNotMatch(body, /overflow:\s*hidden/)
})
