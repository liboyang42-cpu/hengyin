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

function assertCloseFinishContract(source) {
  assert.match(source, /closeFinish\(\) \{\s*\n\s*this\._hideFinish\(\(\) => \{\s*\n\s*if \(this\.data\.allDone && !this\._canContinueMerchantGames\(\) && !this\.data\.isPreview && !this\.data\.isMock && !this\._reviewAfterFinish\(\)\) this\.goBackDetail\(\);/)
  // 2026-11-11 f-play V3-07(业主:通关后能往上滑回看整个故事):有已完成章节的城市定向先回故事流只读回看,回看打不开才回详情;仍不落回开局
}

test('关闭通关卡后无可继续商家玩法的已通关主题回详情,不落回开局', () => {
  assertCloseFinishContract(js)
})

test('mutation 负控:移除未完成商家玩法保留条件必须变红', () => {
  assert.throws(() => assertCloseFinishContract(js.replace(' && !this._canContinueMerchantGames()', '')), assert.AssertionError)
})
