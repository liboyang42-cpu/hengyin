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
  assert.match(body, /modal\.show\(\{[\s\S]*danger: true,[\s\S]*success: \(res\) => \{ if \(res && res\.confirm && current\(\)\) this\._doClearVoice\(\); \},/)
})

test('BL-43: 清除后回读状态，只有回读为 0 才提示成功', () => {
  const js = read('pages/merchant/decor/ai-npc/index.js')
  const body = js.slice(js.indexOf('  _doClearVoice() {'), js.indexOf('  resetVoiceRecording() {'))
  assert.match(body, /this\._request\('\/api\/merchant\/npc\/voice\/reset'\)\n      \.then\(\(\) => \{ if \(current\(\)\) return this\.refreshVoice\(current\); \}\)/)
  assert.match(body, /if \(this\.data\.voiceStatus === 0 && !this\.data\.voiceError\) \{/)
  assert.match(body, /toast\('角色声音已清除'\)/)
  assert.doesNotMatch(body, /toast\.success\(/, 'BL-162: 声音行回到未录音即是结果,不再加成功 toast(棘轮上限 101)')
  assert.match(body, /\.catch\(\(e\) => \{/)
})
