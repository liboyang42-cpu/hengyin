'use strict'
// D-77（业主 2026-11-11）：游玩中音频切后台继续播放，局结束/离开游玩才停。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.join(__dirname, '../..')
const appJson = fs.readFileSync(path.join(root, 'app.json'), 'utf8')
const playJs = fs.readFileSync(path.join(root, 'pages/play/index.js'), 'utf8')

test('D-77 小程序声明了 audio 后台能力', () => {
  const json = JSON.parse(appJson.replace(/^\s*\/\/.*$/gm, ''))
  assert.ok(Array.isArray(json.requiredBackgroundModes))
  assert.ok(json.requiredBackgroundModes.includes('audio'), 'requiredBackgroundModes 必须含 audio')
})

test('D-77 切后台（局中）不暂停音频、也不掐局内语音', () => {
  assert.match(playJs, /if \(!keepsRunningInBackground\) \{ this\.pauseAudio\(\); this\._stopShopNpcVoice\(\); \}/)
  // 不能再出现无条件 pauseAll-on-hide / 无条件 stopShopNpcVoice 的旧写法
  assert.doesNotMatch(playJs, /audioSession\.releaseAwake\(\); this\._stopShopNpcVoice\(\);/)
})

test('D-77 局结束与离开游玩仍然收音频', () => {
  assert.match(playJs, /_endRunSession\(\) \{\s*\n\s*audioSession\.pauseAll\(false\)/)
  assert.match(playJs, /audioSession\.unregister\(this\._kitAudio\)/)
  assert.match(playJs, /audioSession\.unregister\(this\._chapAudio\)/)
})
