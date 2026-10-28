'use strict'
// BL-43 商家角色声音清除:二次确认 → POST /api/merchant/npc/voice/reset → 回读 voice/status。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '..', '..')
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8')

test('BL-43: 声音可用时出现「清除声音」，走危险确认', () => {
  const wxml = read('pages/merchant/decor/ai-npc/index.wxml')
  const ready = wxml.slice(wxml.indexOf("<block wx:elif=\"{{voiceStatus === 2}}\">"), wxml.indexOf('<block wx:else>', wxml.indexOf("voiceStatus === 2}}\">")))
  assert.match(ready, /bindtap="clearVoice"[^>]*>清除声音<\/cy-btn>/)
  const js = read('pages/merchant/decor/ai-npc/index.js')
  const body = js.slice(js.indexOf('  clearVoice() {'), js.indexOf('  resetVoiceRecording() {'))
  assert.match(body, /modal\.show\(\{[\s\S]*danger: true,[\s\S]*success: \(res\) => \{ if \(res && res\.confirm && context\.current\(\) && current\(\)\) this\._doClearVoice\(context\); \},/)
  assert.match(body, /const current = this\.voiceClearCurrent\(\);/)
  assert.match(js, /onHide\(\) \{\n    this\._voiceClearEpoch = \(this\._voiceClearEpoch \|\| 0\) \+ 1;/)
  assert.match(js, /onUnload\(\) \{\n    this\._voiceClearEpoch = \(this\._voiceClearEpoch \|\| 0\) \+ 1;/)
})

test('BL-43: 清除后回读状态，只有回读为 0 才提示成功', () => {
  const js = read('pages/merchant/decor/ai-npc/index.js')
  const body = js.slice(js.indexOf('  _doClearVoice(context) {'), js.indexOf('  resetVoiceRecording() {'))
  assert.match(body, /this\._request\('\/api\/merchant\/npc\/voice\/reset'\)\n      \.then\(\(\) => context\.current\(\) && current\(\) \? this\.refreshVoice\(current\) : null\)/)
  assert.match(body, /if \(this\._voiceClearing \|\| this\._hidden \|\| this\._destroyed \|\| !context \|\| !context\.current\(\)\) return;/)
  assert.equal((body.match(/if \(!context\.current\(\) \|\| !current\(\)\) return;/g) || []).length, 2)
  assert.match(js, /if \(this\._voiceClearing\) \{\n      this\._voiceClearing = false;\n      this\.setData\(\{ voiceBusy: false \}\);\n      \/\/ Reentry only queries state; a missing reset response never triggers another reset\.\n      this\.refreshVoice\(this\.voiceClearCurrent\(\)\);/)
  assert.match(body, /if \(this\.data\.voiceStatus === 0 && !this\.data\.voiceError\) \{/)
  assert.match(body, /toast\('角色声音已清除'\)/)
  assert.doesNotMatch(body, /toast\.success\(/, 'BL-162: 声音行回到未录音即是结果,不再加成功 toast(棘轮上限 101)')
  assert.match(body, /\.catch\(\(e\) => \{/)
})
