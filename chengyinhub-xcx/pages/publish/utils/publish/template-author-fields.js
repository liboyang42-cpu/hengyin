'use strict';

const CLEARABLE = ['rewardRulesJson', 'merchantGuide', 'sensorType', 'sensorConfig', 'storyImg'];
const COPY_FIELDS = [
  'originalTemplateId', 'categoryId', 'title', 'description', 'imgUrl', 'players',
  'usageLocation', 'requiredMaterials', 'duration', 'difficulty', 'isSync',
  'ruleInstructions', 'merchantGuide', 'rewardRulesJson', 'validationMethod', 'interactionType',
  'sensorType', 'sensorConfig', 'questionName', 'questionAnswer', 'feedbackText',
  'couponId', 'medalImg', 'medalName', 'medalStyle', 'questionA', 'questionB',
  'questionC', 'questionD', 'correctAnswer', 'questionImg', 'questionAudio',
  'questionOptionMediaJson', 'preferenceJson', 'advancedConfigJson',
  'activityCategoryids', 'storyText', 'storyImg', 'storyJson', 'hint1', 'hint2',
  'answerReveal', 'photoRequireDesc', 'photoReview', 'audioUrl', 'audioDuration'
];

function clearFields(original, current) {
  return CLEARABLE.filter(key => original && original[key] != null && original[key] !== ''
    && (current[key] == null || current[key] === ''));
}

function sensorClearError(fields, current, original) {
  if (!fields.some(key => key === 'sensorType' || key === 'sensorConfig')) return '';
  const method = Number(current.validationMethod);
  if (method === 7 || (method === 2 && (current.sensorType === 'filter_shot'
    || original && original.sensorType === 'filter_shot')))
    return '当前验证方式需要传感器配置，请先切换为非传感器验证方式再清空';
  return '';
}

function copyPayload(template, scope) {
  const payload = {};
  COPY_FIELDS.forEach(key => {
    if (Object.prototype.hasOwnProperty.call(template, key)) payload[key] = template[key];
  });
  payload.scope = scope || '';
  payload.isSync = 0;
  return payload;
}

module.exports = { CLEARABLE, clearFields, sensorClearError, copyPayload };
