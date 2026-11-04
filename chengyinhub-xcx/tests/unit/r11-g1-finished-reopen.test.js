/* R11 g1(j3 F5):已通关的主题重开时直接回到结局与通关卡,不再从「开始游戏」开新局 */
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const js = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.js'), 'utf8')

test('首屏分派:城市定向且 allDone ⇒ openFinish,否则故事流', () => {
  const at = js.indexOf("} else if (String(that.data.mode) !== '2') {\n          // 城市定向:打开就是故事流")
  assert.ok(at > 0, '找不到首屏分派')
  const block = js.slice(at, at + 600)
  assert.match(block, /if \(that\.data\.allDone\) \{ that\.setData\(\{ screen: '' \}\); that\.openFinish\(\); \}\s*\n\s*else that\._openStory\(\);/)
  // 分派前必须先 rebuild(allDone 由它按服务端路线态算出)
  const before = js.slice(at - 900, at)
  assert.ok(before.includes('that.rebuild();'), 'allDone 必须先由 rebuild 算出')
})

test('关闭通关卡后已通关的主题回详情,不落回开局', () => {
  assert.match(js, /closeFinish\(\) \{\s*\n\s*this\._hideFinish\(\(\) => \{\s*\n\s*if \(this\.data\.allDone && !this\.data\.isPreview && !this\.data\.isMock\) this\.goBackDetail\(\);/)
})
