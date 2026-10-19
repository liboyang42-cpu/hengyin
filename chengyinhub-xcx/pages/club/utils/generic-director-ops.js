'use strict'
const { toTimestamp } = require('../../../utils/datetime.js')

const labels = {
  ACT_UNLOCK: '主持开放幕', ARRIVAL_CONFIRM: '核验到达凭证', ARRIVAL_ATTEST: '纸账到达核验', ARRIVAL_REVOKE: '撤销到达核验',
  RUN_PAUSE: '暂停本局', RUN_RESUME: '恢复本局', RUN_INTERRUPT: '中止本局', RUN_CANCEL: '取消本局',
  MANUAL_BEGIN: '进入人工账', STAFF_MANUAL_SIGN: '签署现场核验', MANUAL_IMPORT: '执行已签事件', MANUAL_REVIEW: '复核人工冲突',
  AUTHORITY_RESUME: '恢复在线权威', QUEST_GUIDE: '工作人员指导完成', LOCATION_REBIND: '迁移到批准备用站', STAFF_TEAM_EXIT: '工作人员确认队伍退出',
}
const irreversible = ['RUN_INTERRUPT', 'RUN_CANCEL', 'MANUAL_IMPORT', 'MANUAL_REVIEW', 'STAFF_TEAM_EXIT', 'QUEST_GUIDE', 'ACT_UNLOCK']
const fieldLabels = {
  actKey: '幕标识', teamId: '队伍 ID', questKey: '任务标识（或填写操作类型与标识）', operationKind: '操作类型（FACILITY 或 PLAN）', operationKey: '操作标识',
  nonce: '队长出示的到达凭证', reason: '实际操作原因（1–200 字）', nodeId: '已批准备用节点 ID',
  highWaterHash: '本局高水位指纹', configHash: '本局规则指纹', unknownRequests: '待核对请求清单（JSON 数组）', offlineStartedAt: '离线开始时间（北京时间，可选）',
  signatureId: '签名 ID', reviewKind: '复核对象', memberId: '原操作者会员 ID', requestId: '原请求号', acceptLateConflict: '明确接受迟到指纹冲突（不重放）',
  sequence: '当前连续序号', occurredAt: '实际事件时间（北京时间）', signedAction: '本人核验动作', originalRequestId: '原在线请求号（可选）', originalExpectedRevision: '原在线版本（与原请求号一起填）',
}
const targetFields = ['teamId', 'questKey', 'operationKind', 'operationKey']
const schemas = {
  ACT_UNLOCK: ['actKey', 'reason'], ARRIVAL_CONFIRM: targetFields.concat('nonce'), ARRIVAL_ATTEST: targetFields.concat('reason'), ARRIVAL_REVOKE: targetFields.concat('reason'),
  QUEST_GUIDE: ['teamId', 'questKey', 'reason'], LOCATION_REBIND: ['questKey', 'nodeId', 'reason'], STAFF_TEAM_EXIT: ['teamId', 'reason'],
  MANUAL_BEGIN: ['highWaterHash', 'configHash', 'unknownRequests', 'offlineStartedAt', 'reason'], MANUAL_IMPORT: ['signatureId'],
  STAFF_MANUAL_SIGN: ['sequence', 'occurredAt', 'signedAction'], MANUAL_REVIEW: ['reviewKind'],
}

// X028–X031 主持台：当前状态与每个动作的实际后果，只描述服务端已实现的行为。
const runStatusLabels = { RUNNING: '运行中', PAUSED: '已暂停', INTERRUPTED: '已中止', CANCELLED: '已取消' }
const runStatusNotes = {
  PAUSED: '玩家端只读，HP、资源、配给与倒计时冻结；恢复后倒计时顺延',
  INTERRUPTED: '已停止全部扣减与结算，不计结局；不可恢复',
  CANCELLED: '已停止计时与结算；不可恢复，原单退款不会自动发起',
}
const consequences = {
  RUN_PAUSE: '暂停后玩家端统一显示暂停，所有玩家提交被拒；HP、资源、配给与倒计时冻结，恢复后倒计时按暂停时长顺延。',
  RUN_RESUME: '恢复后玩家可继续提交，归零倒计时按暂停时长顺延。',
  RUN_INTERRUPT: '中止不可撤销、不可恢复：立即停止全部 HP、资源、配给扣减并释放未执行的占用；本局只记为运营中止，不计入任何剧情结局（含 END05）。',
  RUN_CANCEL: '取消不可撤销、不可恢复：停止计时与结算并释放未执行的占用。本操作不会自动发起原单退款，退款须按退款规则另行处理。',
  STAFF_TEAM_EXIT: '确认退出不可撤销：释放该队未执行的任务与协作占用，已持有物资保留、不被征用；该队之后不能再提交。',
  QUEST_GUIDE: '指导完成与正常完成同奖励，记为“工作人员指导”，并记录核验人与原因。',
  LOCATION_REBIND: '迁点后任务标识与领取槽不变，不扣 HP；该地点已有的到达核验作废，需重新核验。',
  ARRIVAL_REVOKE: '撤销后该队须重新核验到达；撤销原因与操作人留审计。',
  ARRIVAL_CONFIRM: '到达凭证一次有效，已核验或过期的凭证会被拒绝。',
}

function genericOpsStatusText(run) {
  if (!run || !Number.isSafeInteger(run.authorityEpoch)) return ''
  const parts = [(runStatusLabels[run.operationStatus] || '状态待确认') + (run.authorityMode === 'MANUAL' ? ' · 人工账接管' : '') + ' · 权威 ' + run.authorityEpoch]
  if (run.authorityMode !== 'MANUAL' && runStatusNotes[run.operationStatus]) parts.push(runStatusNotes[run.operationStatus])
  if (typeof run.operationReason === 'string' && run.operationReason.trim()) parts.push('原因：' + run.operationReason.trim())
  return parts.join('｜')
}

function genericOpsConsequence(action) {
  return consequences[action] || ''
}

function genericOpsActions(control) {
  if (!control || !control.run || !Number.isSafeInteger(control.run.authorityEpoch) || control.run.authorityEpoch < 1
    || !Number.isSafeInteger(control.revision) || !Array.isArray(control.availableActions)) return []
  const actions = control.availableActions.filter((action, index, all) => labels[action] && action !== 'ARRIVAL_ATTEST' && all.indexOf(action) === index)
    .map(action => ({ id: action, title: labels[action], irreversible: irreversible.includes(action) }))
  if (control.offlineAnchorAvailable === true) actions.unshift({ id: 'OFFLINE_ANCHOR', title: '开始离线记录', irreversible: false })
  return actions
}

function isGuide(action, draft) {
  return action === 'QUEST_GUIDE' || action === 'STAFF_MANUAL_SIGN' && draft.signedAction === 'QUEST_GUIDE'
}

function guideTask(draft, control) {
  return (control && Array.isArray(control.guideTasks) ? control.guideTasks : []).find(task => task.questKey === String(draft.questKey || '').trim())
}

function guideFields(task, draft) {
  if (!task) return []
  const fields = []
  function add(key, label, options, boolean) {
    options = options || []
    const value = draft[key] == null ? '' : draft[key]
    const selected = options.find(option => option.id === value)
    fields.push({ key, label, value, valueLabel: selected ? selected.label : value,
      options: options.map(option => option.id), optionLabels: options.map(option => option.label),
      optionIndex: options.findIndex(option => option.id === value), boolean: boolean === true, maxLength: 512 })
  }
  for (const kind of ['basic', 'qa']) {
    const question = task[kind]
    if (!question) continue
    const title = question.title || (kind === 'basic' ? '基本题' : '问答题')
    if (question.mode === 'TYPE') add('guide_' + kind, title + ' · 队伍填写的答案')
    else if (question.mode === 'PICK' && (kind === 'basic' || question.multi === true)) {
      for (const [index, option] of question.options.entries()) add('guide_' + kind + '_' + index, title + ' · ' + option.label, [], true)
    } else if (question.mode === 'PICK') add('guide_' + kind, title + ' · 队伍选择的选项', question.options)
  }
  if (task.sort) for (const [index] of task.sort.items.entries()) add('guide_sort_' + index,
    (task.sort.prompt || '排序题') + ' · 第 ' + (index + 1) + ' 项', task.sort.items)
  if (task.match) for (const [index, item] of task.match.left.entries()) add('guide_match_' + index,
    (task.match.prompt || '连线题') + ' · ' + item.label, task.match.right)
  if (task.classify) for (const [index, item] of task.classify.items.entries()) add('guide_classify_' + index,
    (task.classify.prompt || '分类题') + ' · ' + item.label, task.classify.bins)
  return fields
}

function guideAnswers(draft, control) {
  const task = guideTask(draft, control)
  if (!task) throw new Error('请先读取本局题型并选择指导任务')
  const fields = guideFields(task, draft)
  if (!fields.length) throw new Error('本任务没有可指导的题型')
  function selected(key) {
    const field = fields.find(item => item.key === key)
    const value = String(draft[key] == null ? '' : draft[key]).trim()
    if (!field || !value || value.length > field.maxLength || field.options.length && !field.options.includes(value)) throw new Error('请完整录入队伍实际作答')
    return value
  }
  function picked(kind, question) {
    const ids = question.options.filter((option, index) => draft['guide_' + kind + '_' + index] === true).map(option => option.id)
    if (!ids.length) throw new Error('请完整录入队伍实际作答')
    return ids
  }
  function distinct(values) {
    if (new Set(values).size !== values.length) throw new Error('排序或连线不能重复选择同一项')
    return values
  }
  const answers = {}
  if (task.basic) answers.answer = task.basic.mode === 'PICK' ? picked('basic', task.basic).join('') : selected('guide_basic')
  if (task.qa) answers.qa = task.qa.mode === 'TYPE' ? { input: selected('guide_qa') }
    : task.qa.multi === true ? { optionIds: picked('qa', task.qa) } : { optionId: selected('guide_qa') }
  if (task.sort) answers.sort = { order: distinct(task.sort.items.map((item, index) => selected('guide_sort_' + index))) }
  if (task.match) {
    const right = distinct(task.match.left.map((item, index) => selected('guide_match_' + index)))
    answers.match = { pairs: task.match.left.map((item, index) => [item.id, right[index]]) }
  }
  if (task.classify) answers.classify = { placement: Object.fromEntries(task.classify.items.map((item, index) => [item.id, selected('guide_classify_' + index)])) }
  return answers
}

function genericOpsFields(action, draft, control) {
  draft = draft || {}
  let keys = schemas[action] || (labels[action] ? ['reason'] : [])
  if (action === 'MANUAL_BEGIN') keys = ['unknownRequests', 'reason']
  if (action === 'STAFF_MANUAL_SIGN') keys = keys.concat(schemas[draft.signedAction] || [], ['originalRequestId', 'originalExpectedRevision'])
  if (action === 'MANUAL_REVIEW') keys = keys.concat(draft.reviewKind === 'SIGNATURE' ? ['signatureId', 'reason']
    : draft.reviewKind === 'REQUEST' ? ['memberId', 'requestId', 'acceptLateConflict', 'reason'] : ['reason'])
  const optionLabels = Object.assign({ HIGH_WATER: '核对当前高水位', SIGNATURE: '驳回已签事件', REQUEST: '复核未知在线请求' }, labels)
  const choices = { signedAction: ['ARRIVAL_CONFIRM', 'ARRIVAL_ATTEST', 'QUEST_GUIDE'], reviewKind: ['HIGH_WATER', 'SIGNATURE', 'REQUEST'] }
  if (isGuide(action, draft) && control && Array.isArray(control.guideTasks)) {
    choices.questKey = control.guideTasks.map(task => task.questKey)
    for (const key of choices.questKey) optionLabels[key] = key
  }
  return keys.map(key => ({ key, label: fieldLabels[key], value: draft[key] == null ? '' : draft[key], valueLabel: optionLabels[draft[key]] || draft[key] || '',
    multiline: key === 'reason' || key === 'unknownRequests', boolean: key === 'acceptLateConflict',
    options: choices[key] || [], optionIndex: (choices[key] || []).indexOf(draft[key]),
    optionLabels: key === 'signedAction' ? ['核验到达凭证', '纸账到达核验', '工作人员指导完成']
      : key === 'reviewKind' ? ['核对当前高水位', '驳回已签事件', '复核未知在线请求'] : key === 'questKey' ? choices.questKey || [] : [],
    maxLength: key === 'unknownRequests' ? 20000 : key === 'reason' ? 200 : 128 }))
    .concat(isGuide(action, draft) ? guideFields(guideTask(draft, control), draft) : [])
}

function text(draft, key, maximum, optional) {
  const value = String(draft[key] == null ? '' : draft[key]).trim()
  if (optional && !value) return null
  if (!value || value.length > maximum || /[\u0000-\u001f\u007f]/.test(value)) throw new Error('请核对' + fieldLabels[key])
  return value
}

function integer(draft, key, minimum) {
  const value = ['occurredAt', 'offlineStartedAt'].includes(key) ? toTimestamp(draft[key]) : Number(draft[key])
  if (draft[key] === '' || draft[key] == null || !Number.isSafeInteger(value) || value < minimum) throw new Error('请核对' + fieldLabels[key])
  return value
}

function target(draft) {
  const payload = { teamId: integer(draft, 'teamId', 1) }
  const quest = text(draft, 'questKey', 64, true)
  const kind = text(draft, 'operationKind', 64, true)
  const operation = text(draft, 'operationKey', 64, true)
  if (quest ? kind || operation : !['FACILITY', 'PLAN'].includes(kind) || !operation) throw new Error('请选择一个任务或操作目标，不能同时配置')
  if (quest) payload.questKey = quest
  else Object.assign(payload, { operationKind: kind, operationKey: operation })
  return payload
}

function genericOpsPayload(action, draft, control) {
  draft = draft || {}
  if (!labels[action]) throw new Error('本场未开放该运营动作')
  if (action === 'STAFF_MANUAL_SIGN') {
    if (!['ARRIVAL_CONFIRM', 'ARRIVAL_ATTEST', 'QUEST_GUIDE'].includes(draft.signedAction)) throw new Error('只能签署本人实际核验动作')
    const payload = { sequence: integer(draft, 'sequence', 1), occurredAt: integer(draft, 'occurredAt', 1),
      command: { action: draft.signedAction, payload: genericOpsPayload(draft.signedAction, draft, control) } }
    const original = text(draft, 'originalRequestId', 64, true)
    if (original) {
      payload.originalRequestId = original
      payload.originalExpectedRevision = integer(draft, 'originalExpectedRevision', 0)
    } else if (draft.originalExpectedRevision != null && draft.originalExpectedRevision !== '') throw new Error('原请求号与版本须一起填写')
    return payload
  }
  if (action === 'MANUAL_IMPORT') return { signatureId: text(draft, 'signatureId', 128) }
  const payload = action.startsWith('ARRIVAL_') ? target(draft) : {}
  if (action === 'ARRIVAL_CONFIRM') {
    payload.nonce = text(draft, 'nonce', 64)
    if (!/^[A-Za-z0-9_-]{43}$/.test(payload.nonce)) throw new Error('请核对队长出示的到达凭证')
    return payload
  }
  payload.reason = text(draft, 'reason', 200)
  if (action === 'ACT_UNLOCK') payload.actKey = text(draft, 'actKey', 64)
  if (action === 'QUEST_GUIDE' || action === 'STAFF_TEAM_EXIT') payload.teamId = integer(draft, 'teamId', 1)
  if (action === 'QUEST_GUIDE' || action === 'LOCATION_REBIND') payload.questKey = text(draft, 'questKey', 64)
  if (action === 'QUEST_GUIDE') payload.answers = guideAnswers(draft, control)
  if (action === 'LOCATION_REBIND') payload.nodeId = integer(draft, 'nodeId', 1)
  if (action === 'MANUAL_BEGIN') {
    const anchor = control && control.offlineAnchor
    if (anchor && (anchor.epoch !== control.run.authorityEpoch || !Number.isSafeInteger(anchor.at) || anchor.at < 1)) throw new Error('离线记录已失效，请重新读取')
    payload.highWaterHash = anchor ? anchor.highWaterHash : text(draft, 'highWaterHash', 64)
    payload.configHash = anchor ? anchor.configHash : text(draft, 'configHash', 64)
    if (![payload.highWaterHash, payload.configHash].every(value => /^[a-f0-9]{64}$/.test(value))) throw new Error('请先读取本局账本指纹')
    let unknown
    try { unknown = typeof draft.unknownRequests === 'string' ? JSON.parse(draft.unknownRequests) : draft.unknownRequests } catch (_) { throw new Error('待核对请求清单须是 JSON 数组') }
    if (!Array.isArray(unknown) || unknown.length > 100 || unknown.some(item => !item || Object.keys(item).sort().join(',') !== 'memberId,requestHash,requestId'
      || !Number.isSafeInteger(item.memberId) || item.memberId < 1 || typeof item.requestId !== 'string' || !item.requestId || item.requestId.length > 64
      || typeof item.requestHash !== 'string' || !/^[a-f0-9]{64}$/.test(item.requestHash))) throw new Error('待核对请求必须携带原操作者、原请求号和指纹')
    payload.unknownRequests = unknown
    if (anchor) payload.offlineStartedAt = anchor.at
    else if (draft.offlineStartedAt != null && draft.offlineStartedAt !== '') throw new Error('请先开始离线记录，确认成功后再继续')
  }
  if (action === 'MANUAL_REVIEW') {
    if (draft.reviewKind === 'HIGH_WATER') payload.acceptCurrentHighWater = true
    else if (draft.reviewKind === 'SIGNATURE') payload.signatureId = text(draft, 'signatureId', 128)
    else if (draft.reviewKind === 'REQUEST') {
      payload.memberId = integer(draft, 'memberId', 1)
      payload.requestId = text(draft, 'requestId', 64)
      if (draft.acceptLateConflict === true) payload.acceptLateConflict = true
    } else throw new Error('请选择一个明确复核对象')
  }
  return payload
}

module.exports = { genericOpsActions, genericOpsFields, genericOpsPayload, genericOpsStatusText, genericOpsConsequence }
