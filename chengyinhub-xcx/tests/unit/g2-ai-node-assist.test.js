// G2 / GAM-14:节点编辑页真正渲染 cy-ai-assist,调用既有 /api/ai/node/generate;只填空字段,结果标注 AI 起草。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('fs')
const path = require('path')
const assist = require('../../pages/publish/temp/ai-node-assist.js')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('registered component is rendered and wired', () => {
  const wxml = read('pages/publish/temp/index.wxml')
  assert.match(wxml, /<cy-ai-assist [^>]*bind:generate="onAiAssistGenerate"/)
  assert.ok(read('pages/publish/temp/index.json').includes('"cy-ai-assist"'))
  const js = read('pages/publish/temp/index.js')
  assert.match(js, /onAiAssistGenerate: function \(e\) \{ aiNodeAssist\.generate\(this, app, e\); \}/)
  assert.ok(read('pages/publish/temp/ai-node-assist.js').includes("url: '/api/ai/node/generate'"))
})

test('only empty fields are filled and rules only when none written', () => {
  const node = { description: 'AI 看点', questionName: 'AI 问题', hint1: '提示一', ruleInstructions: '走到门口\n拍一张招牌' }
  const out = assist.fillPatch({ description: '作者写的', questionName: '' }, [''], node)
  assert.equal(out.patch['formData.description'], undefined)
  assert.equal(out.patch['formData.questionName'], 'AI 问题')
  assert.equal(out.patch['formData.hint1'], '提示一')
  assert.deepEqual(out.steps, ['走到门口', '拍一张招牌'])
  const kept = assist.fillPatch({}, ['作者的第一步'], node)
  assert.equal(kept.steps, null)
  assert.match(assist.SOURCE_NOTE, /AI 起草/)
})

test('request carries the node title and the prompt, bounded', () => {
  const req = assist.buildRequest({ title: '断桥', description: 'x'.repeat(500) }, '拍照打卡')
  assert.equal(req.nodeName, '断桥')
  assert.equal(req.purpose, '拍照打卡')
  assert.equal(req.themeContext.length, 300)
})

test('generate fills, labels and ignores stale answers', () => {
  const calls = []
  const page = {
    data: { formData: { title: '断桥' }, ruleSteps: [''] },
    setData(p) { Object.assign(this.data, p) },
    _setFormState(p) { calls.push(p) },
    _syncRuleSteps(s) { calls.push({ steps: s }) }
  }
  let handlers
  const app = { sendRequest(r) { handlers = r } }
  assist.generate(page, app, { detail: { prompt: '答题' } })
  assert.equal(page.data.aiAssistState, 'generating')
  handlers.success({ code: 200, data: { node: { questionName: 'Q', ruleInstructions: '一' } } })
  assert.equal(page.data.aiAssistState, 'done')
  assert.match(page.data.aiAssistNote, /AI 起草/)
  assert.equal(calls.length, 2)
  assist.generate(page, app, { detail: { prompt: '再来' } })
  const stale = handlers
  assist.cancel(page)
  stale.success({ code: 200, data: { node: { questionName: 'late' } } })
  assert.equal(page.data.aiAssistState, 'idle')
  assist.generate(page, app, { detail: { prompt: '玩家' } })
  handlers.success({ code: 500, msg: '当前身份暂不支持AI创作' })
  assert.equal(page.data.aiAssistState, 'error')
  assert.equal(page.data.aiAssistError, '当前身份暂不支持AI创作')
})
