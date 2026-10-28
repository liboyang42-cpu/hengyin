const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const uiRoot = path.resolve(__dirname, '../..')
const read = file => fs.readFileSync(path.join(uiRoot, file), 'utf8')
const modelSource = read('src/views/business/membertemplate/template-content.js')
const model = new Function(modelSource.replace(/^export /gm, '') + '\nreturn { validationMethods, validationLabel, contentGroups, contentText, contentSummary, contentErrors, buildTemplatePayload }')()
const centerSource = read('src/views/business/membertemplate/index.vue')
const fieldSource = read('src/views/business/membertemplate/TemplateContentFields.vue')

function center(overrides = {}) {
  const dependencies = Object.assign({
    listMembertemplate: () => Promise.resolve({ rows: [], total: 0 }),
    getMembertemplate: () => Promise.resolve({ data: content() }),
    updateMembertemplate: () => Promise.resolve({ code: 200 }),
    delMembertemplate: () => Promise.resolve(), submitMembertemplateReview: () => Promise.resolve(),
    listCategory: () => Promise.resolve({ rows: [] }), parseTime: () => '', checkPermi: () => true
  }, overrides)
  const script = centerSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ ([^}]+) \} from '@\/api\/business\/membertemplate'$/m, 'const { $1 } = dependencies')
    .replace(/^import \{ listCategory \} from .+$/m, 'const { listCategory } = dependencies')
    .replace(/^import \{ parseTime \} from .+$/m, 'const { parseTime } = dependencies')
    .replace(/^import \{ checkPermi \} from .+$/m, 'const { checkPermi } = dependencies')
    .replace(/^import \{ ([^}]+) \} from '\.\/template-content'$/m, 'const { $1 } = model')
    .replace(/^import (?:Cy\w+|TemplateContentFields) from .+$/gm, line => 'const ' + line.split(' ')[1] + ' = {}')
  const component = new Function('dependencies', 'model', script.replace(/export\s+default\s*/, 'return '))(dependencies, model)
  const vm = Object.assign(component.data(), {
    $refs: { editForm: { validate: callback => callback(true) } },
    $modal: { msgSuccess() {}, msgError() {} }
  })
  for (const [name, method] of Object.entries(component.methods)) vm[name] = method.bind(vm)
  return { component, vm }
}

// UI-only fixtures: server gameplay, ownership, rewards and publication admission are not mocked
// as verified. In particular the opaque strings below are only checked for exact round-trip.
function content(id = '9007199254740993') {
  return {
    id, memberId: '9007199254740995', originalTemplateId: '9007199254740997',
    title: '完整游戏', validationMethod: 7, activityCategoryids: '9007199254740999,5',
    advancedConfigJson: '{ "schemaVersion":1, "diceRoll":{"enabled":true,"future":0}, "interactionRules":{"resourceSlots":[{"key":"npc","id":"9007199254740993"}]}, "future":{"preserve":true} }',
    preferenceJson: '{ "dimensions":["A"], "steps":[], "results":{} }',
    sensorType: 'steps', sensorConfig: '{ "sensorType":"steps", "targetSteps":20, "windowSec":60 }',
    interactionType: 'hidden_menu', questionName: '题面', questionAnswer: '原秘密答案',
    questionA: 'A', questionB: 'B', questionC: '', questionD: null, correctAnswer: 'A',
    questionImg: 'media-asset:11', questionAudio: 'media-asset:12', questionOptionMediaJson: '{ "A":{"img":"media-asset:13"} }',
    photoReview: 0, photoRequireDesc: '现场拍照', storyText: '剧情', storyImg: 'media-asset:14',
    storyJson: '[ {"text":"剧情","tag":"原tag","imgs":["media-asset:14"]} ]',
    merchantGuide: '仅店员可见的执行说明', feedbackText: '原反馈', rewardRulesJson: '[ {"outcomeCode":"A","xp":0} ]',
    couponId: '9007199254740996', xpValue: 0, medalName: '原勋章', medalStyle: 'enamel', medalImg: 'media-asset:15',
    audioUrl: 'media-asset:16', audioDuration: 0, hint1: '提示1', hint2: '', answerReveal: '原解析',
    unpublishedFutureField: { keep: false, code: 'original' }, status: 1, publishStatus: 1, isSync: 1
  }
}

test('完整内容往返保留JSON原字节、资源槽、秘密、0/null及当前UI未知字段，不改个人/库身份', () => {
  const original = content(), before = JSON.stringify(original)
  const payload = model.buildTemplatePayload(original, ['9007199254740999', '5'])
  assert.deepEqual(payload, original)
  assert.notEqual(payload, original)
  assert.equal(JSON.stringify(original), before)
  for (const key of ['advancedConfigJson', 'preferenceJson', 'sensorConfig', 'storyJson', 'questionOptionMediaJson', 'rewardRulesJson'])
    assert.equal(payload[key], original[key], key)
  assert.equal(payload.memberId, '9007199254740995')
  assert.equal(payload.originalTemplateId, '9007199254740997')
  assert.equal(payload.couponId, '9007199254740996')
})

test('空值不被编辑器默认值覆盖；只改标题不清高级段、感应器、剧情或反馈', async () => {
  let sent
  const { vm } = center({ updateMembertemplate: payload => { sent = payload; return Promise.resolve({ code: 200 }) } })
  await vm.handleEdit({ id: content().id })
  const raw = vm.editForm.advancedConfigJson
  vm.editForm.title = '只修改标题'
  await vm.submitEdit()
  assert.equal(sent.title, '只修改标题')
  assert.equal(sent.advancedConfigJson, raw)
  assert.equal(sent.questionD, null)
  assert.equal(sent.photoReview, 0)
  assert.equal(sent.activityCategoryids, content().activityCategoryids)
  assert.equal(sent.unpublishedFutureField.keep, false)
  assert.equal(vm.editSaving, false)
  assert.equal(vm.editOpen, false)
})

test('非法JSON和错误根类型在写请求前拒绝且输入原样保留，不执行脚本或自动修复', async () => {
  let writes = 0
  const { vm } = center({ updateMembertemplate: () => { writes++; return Promise.resolve() } })
  await vm.handleEdit({ id: content().id })
  vm.editForm.advancedConfigJson = '({run: () => paidProvider()})'
  await vm.submitEdit()
  assert.equal(writes, 0)
  assert.match(vm.editError, /高级玩法配置/)
  assert.equal(vm.editForm.advancedConfigJson, '({run: () => paidProvider()})')
  for (const [key, value] of [['advancedConfigJson', '[]'], ['sensorConfig', 'false'], ['storyJson', '{}'], ['preferenceJson', 'null']])
    assert.throws(() => model.buildTemplatePayload(Object.assign(content(), { [key]: value }), []))
  assert.deepEqual(model.contentErrors(Object.assign(content(), { questionOptionMediaJson: 'null' })), {})
  assert.doesNotMatch(modelSource + fieldSource, /\beval\s*\(|new Function|v-html|<iframe|<script src/)
})

test('保存异常不报成功、不清输入、不自动二发；在途重复保存只发一次', async () => {
  let reject, writes = 0, successes = 0
  const { vm } = center({ updateMembertemplate: () => { writes++; return new Promise((resolve, fail) => { reject = fail }) } })
  vm.$modal.msgSuccess = () => { successes++ }
  await vm.handleEdit({ id: content().id })
  const form = JSON.stringify(vm.editForm)
  const first = vm.submitEdit()
  vm.submitEdit()
  assert.equal(writes, 1)
  assert.equal(vm.editSaving, true)
  reject(new Error('response lost'))
  await first
  assert.equal(writes, 1)
  assert.equal(successes, 0)
  assert.equal(JSON.stringify(vm.editForm), form)
  assert.equal(vm.editOpen, true)
  assert.match(vm.editError, /未取得保存成功结果.*重新读取/)
})

test('较早模板详情、取消后的详情和错误身份不覆盖当前编辑内容', async () => {
  const pending = [], messages = []
  const { vm } = center({ getMembertemplate: id => new Promise(resolve => pending.push({ id, resolve })) })
  vm.$modal.msgError = message => messages.push(message)
  const first = vm.handleEdit({ id: '1' }), second = vm.handleEdit({ id: '2' })
  pending[1].resolve({ data: content('2') }); await second
  pending[0].resolve({ data: content('1') }); await first
  assert.equal(vm.editForm.id, '2')
  const third = vm.handleEdit({ id: '3' }); vm.closeEdit()
  pending[2].resolve({ data: content('3') }); await third
  assert.equal(vm.editOpen, false)
  assert.equal(vm.editForm.id, '2')
  const wrong = vm.handleEdit({ id: '4' })
  pending[3].resolve({ data: content('5') }); await wrong
  assert.equal(vm.editForm.id, '2')
  assert.equal(messages.length, 1)
})

test('原基础方式0到10可见且未知值不回落三题型；高级内容摘要来自实际配置而不是另一张玩法表', () => {
  assert.deepEqual(model.validationMethods.map(item => item.value), Array.from({ length: 11 }, (_, i) => i))
  for (const method of model.validationMethods) assert.equal(model.validationLabel(method.value), method.label)
  assert.match(model.validationLabel(99), /99.*保留原值/)
  assert.match(model.contentSummary(content()), /diceRoll/)
  assert.match(model.contentSummary(content()), /steps/)
  assert.match(model.contentSummary(Object.assign(content(), { advancedConfigJson: 'broken' })), /格式待核对/)
  const { component, vm } = center()
  vm.editForm.validationMethod = 99
  assert.equal(component.computed.hasUnknownValidation.call(vm), true)
})

test('同一字段组件展示和编辑原实体全部内容字段；只读不能发修改，编辑只替换已登记单字段', () => {
  const script = fieldSource.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ ([^}]+) \} from '\.\/template-content'$/m, 'const { $1 } = model')
  const component = new Function('model', script.replace(/export\s+default\s*/, 'return '))(model)
  const fields = model.contentGroups.flatMap(group => group.fields.map(field => field.key))
  for (const key of ['advancedConfigJson','preferenceJson','sensorType','sensorConfig','interactionType',
    'questionImg','questionAudio','questionOptionMediaJson','storyText','storyImg','storyJson','hint1','hint2',
    'answerReveal','photoRequireDesc','photoReview','audioUrl','audioDuration','feedbackText','merchantGuide',
    'couponId','xpValue','medalName','medalImg','medalStyle','rewardRulesJson']) assert.ok(fields.includes(key), key)
  const emitted = [], original = content()
  const vm = { value: original, readOnly: true, disabled: false, $emit: (name, value) => emitted.push({ name, value }) }
  component.methods.setField.call(vm, 'advancedConfigJson', '{}')
  assert.equal(emitted.length, 0)
  vm.readOnly = false; vm.disabled = true
  component.methods.setField.call(vm, 'advancedConfigJson', '{}')
  assert.equal(emitted.length, 0)
  vm.disabled = false
  component.methods.setField.call(vm, 'memberId', 'another-owner')
  assert.equal(emitted.length, 0)
  component.methods.setField.call(vm, 'feedbackText', '新反馈')
  assert.deepEqual(emitted, [{ name: 'input', value: Object.assign({}, original, { feedbackText: '新反馈' }) }])
  assert.equal(original.feedbackText, '原反馈')
})

test('详情和中心共用内容投影，保存和审核权限/原API不变，旧库仍只跳转真源', () => {
  const detail = read('src/views/business/membertemplate/detail.vue')
  assert.match(centerSource, /<template-content-fields v-model="editForm"/)
  assert.match(detail, /<template-content-fields :value="templateDetail" read-only/)
  assert.match(centerSource, /business:membertemplate:query/)
  assert.match(centerSource, /business:membertemplate:edit/)
  assert.match(centerSource, /buildTemplatePayload\(this.editForm, this.editCategoryIds\)/)
  const api = read('src/api/business/membertemplate.js')
  assert.match(api, /url: '\/business\/membertemplate',[\s\S]*?method: 'put'/)
  assert.match(api, /url: '\/business\/membertemplate\/' \+ id \+ '\/review'/)
  const legacy = read('src/views/business/templatelibrary/index.vue')
  assert.match(legacy, /name: 'Membertemplate'/)
  assert.doesNotMatch(legacy, /addTemplatelibrary|updateTemplatelibrary|submitForm/)
})
