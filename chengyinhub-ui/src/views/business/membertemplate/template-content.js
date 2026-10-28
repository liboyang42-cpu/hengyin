// Presentation of the existing CmsMemberTemplate contract, not a gameplay registry or validator.
// JSON is checked but never parsed/stringified into a replacement save payload: IDs, unknown
// sections, resourceSlots, secret answer fields and the author's original bytes must survive.
export const validationMethods = [
  { value: 0, label: '无需基础验证（可叠加高级玩法）' },
  { value: 1, label: '文字验证' }, { value: 2, label: '拍照打卡' },
  { value: 3, label: '选项验证' }, { value: 4, label: '扫码打卡' },
  { value: 5, label: 'GPS 到达' }, { value: 6, label: '偏好题组' },
  { value: 7, label: 'App 传感器挑战' }, { value: 8, label: '猜数字（estimate）' },
  { value: 9, label: '猜图（pricePair）' }, { value: 10, label: '找东西（hiddenObject）' }
]

export const contentGroups = [
  { key: 'game', label: '完整玩法配置与资源槽', fields: [
    { key: 'advancedConfigJson', label: '高级玩法配置', json: 'object', help: '沿用 schemaVersion=1 的原配置；互动规则与资源槽保存在 interactionRules.resourceSlots 内。这里不生成、删减或重新解释玩法段。' },
    { key: 'preferenceJson', label: '偏好题组配置', json: 'object', help: '原 dimensions / steps / results / tiebreak 等配置整体保存，规则由服务端验证。' },
    { key: 'sensorType', label: '感应器类型', help: '原 still / steps / audio_clip，或拍照方式的 filter_shot；App 专属能力不代表小程序可用。' },
    { key: 'sensorConfig', label: '感应器参数', json: 'object' },
    { key: 'interactionType', label: '据点互动类型', help: '与完成方式独立；沿用原 hidden_menu / city_story_card / photo_spot / qr_checkin。' }
  ] },
  { key: 'question', label: '题面、选项与素材', fields: [
    { key: 'questionA', label: '选项 A' }, { key: 'questionB', label: '选项 B' },
    { key: 'questionC', label: '选项 C' }, { key: 'questionD', label: '选项 D' },
    { key: 'correctAnswer', label: '正确选项' },
    { key: 'questionImg', label: '题干图片' }, { key: 'questionAudio', label: '题干音频' },
    { key: 'questionOptionMediaJson', label: '选项图片与音频', json: 'object', nullableJson: true },
    { key: 'photoRequireDesc', label: '拍照要求', multiline: true },
    { key: 'photoReview', label: '照片人工审核', number: true }
  ] },
  { key: 'story', label: '剧情、提示与语音', fields: [
    { key: 'storyText', label: '剧情文字', multiline: true }, { key: 'storyImg', label: '剧情配图' },
    { key: 'storyJson', label: '剧情段落', json: 'array' },
    { key: 'hint1', label: '一级提示', multiline: true }, { key: 'hint2', label: '二级提示', multiline: true },
    { key: 'answerReveal', label: '答案解析', multiline: true },
    { key: 'audioUrl', label: '语音讲解' }, { key: 'audioDuration', label: '语音时长（秒）', number: true }
  ] },
  { key: 'feedback', label: '反馈与原奖励配置', fields: [
    { key: 'feedbackText', label: '完成反馈', multiline: true },
    { key: 'merchantGuide', label: '商家执行说明', multiline: true, help: '商家专用说明；不能复制到玩家可见的规则说明中。' },
    { key: 'couponId', label: '原优惠券 ID', help: '仅维护原关联；填写编号不授予优惠券归属或发放权限。' },
    { key: 'medalName', label: '勋章名称' }, { key: 'medalImg', label: '勋章图片' },
    { key: 'medalStyle', label: '勋章样式' }, { key: 'xpValue', label: '节点探索值', number: true },
    { key: 'rewardRulesJson', label: '条件奖励配置', json: 'array' }
  ] }
]

export function validationLabel(value) {
  if (value === null || value === undefined || value === '') return '未设置基础完成方式'
  const item = validationMethods.find(item => String(item.value) === String(value))
  return item ? item.label : `原完成方式 ${value}（保留原值）`
}

export function contentText(value) {
  if (value === null || value === undefined || value === '') return ''
  return typeof value === 'string' ? value : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value)
}

export function contentSummary(template = {}) {
  const configured = []
  if (template.advancedConfigJson) {
    try {
      const config = JSON.parse(template.advancedConfigJson)
      const enabled = config && typeof config === 'object' ? Object.keys(config).filter(key =>
        config[key] && typeof config[key] === 'object' && config[key].enabled === true) : []
      configured.push(enabled.length ? `高级配置段：${enabled.join('、')}` : '含高级配置')
    } catch (error) { configured.push('高级配置格式待核对') }
  }
  if (template.preferenceJson) configured.push('含偏好题组')
  if (template.sensorType) configured.push(`感应器：${template.sensorType}`)
  return [validationLabel(template.validationMethod)].concat(configured).join(' · ')
}

// Only JSON syntax/root shape is checked here. Gameplay, scope, media ownership, coupon,
// preference, sensor and review/publication rules remain with the existing server owners.
export function contentErrors(template) {
  const errors = {}
  for (const group of contentGroups) for (const field of group.fields) {
    if (!field.json) continue
    const raw = template[field.key]
    if (raw === null || raw === undefined || raw === '') continue
    try {
      if (typeof raw !== 'string') throw new Error('必须使用原 JSON 文本')
      const parsed = JSON.parse(raw)
      if (parsed === null && field.nullableJson) continue
      if (field.json === 'array' ? !Array.isArray(parsed) : !parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
        throw new Error(field.json === 'array' ? '必须是 JSON 数组' : '必须是 JSON 对象')
      }
    } catch (error) { errors[field.key] = `${field.label}：${error.message}` }
  }
  return errors
}

export function buildTemplatePayload(form, categoryIds) {
  const errors = contentErrors(form)
  if (Object.keys(errors).length) throw new Error(errors[Object.keys(errors)[0]])
  // Full detail, including fields not rendered by this admin version, is the save source.
  // No defaults for advanced fields, no key allowlist, and no translation via public-library DTO.
  return Object.assign({}, form, { activityCategoryids: categoryIds.join(',') })
}
