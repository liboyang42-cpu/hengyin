// 作者本地试玩：模板和主题预览共用，不上传资料、不创建玩家会话。
// F0457：本地预览一律标为沙盒，结果与正式服务端判定显著分开；加值只按作者静态配置计算。
const SANDBOX_LABEL = '本地沙盒预览：不联网判定、不扣资源、不发奖励，结果不代表正式判定';
function previewD20Result(kit) {
  const values = kit.rollMode === 'normal' ? [12] : [12, 7];
  const kept = kit.rollMode === 'disadvantage' ? Math.min(...values) : Math.max(...values);
  const total = kept + kit.modifier;
  const success = total >= kit.dc;
  return { values, kept, total, success, text: success ? kit.successText : kit.failText, sandbox: true, modifierSource: 'STATIC' };
}

function applyPreviewAction(source, event, startedAt, now = Date.now()) {
  const kit = Object.assign({}, source);
  const { action, detail = {} } = event || {};
  const result = { kit, startedAt: startedAt || 0, ready: false, message: '', sandbox: true, sandboxLabel: SANDBOX_LABEL };
  if (kit.mode === 'd20' && action === 'roll') {
    kit.result = previewD20Result(kit);
    result.ready = true;
  } else if (kit.type === 'profile' && action === 'avatar') {
    kit.avatarUrl = detail.tempFilePath || '';
  } else if (kit.type === 'profile' && action === 'submit') {
    const answers = Object.assign({}, detail.answers);
    const complete = (kit.questions || []).every(question => {
      const value = String(answers[question.key] == null ? '' : answers[question.key]).trim();
      if (!value) return false;
      if (question.kind === 'pick') return (question.options || []).some(option => option.key === value);
      return value.length <= (Number(question.maxLength) || 12);
    });
    if (!complete || (kit.avatar && kit.avatar.enabled && kit.avatar.required && !kit.avatarUrl)) return null;
    kit.answers = answers;
    // 咔哒:试玩没有本局状态可问,一律按「条件已满足」演给作者看
    const labelOf = (q, key) => ((q.options || []).find(o => o.key === key) || {}).label || key;
    kit.overrides = (kit.questions || []).filter(q => q.kaccha && kit.answers[q.key] && kit.answers[q.key] !== q.kaccha.option)
      .map(q => {
        const hit = { key: q.key, picked: labelOf(q, kit.answers[q.key]), assigned: labelOf(q, q.kaccha.option), voice: q.kaccha.voice };
        kit.answers[q.key] = q.kaccha.option;
        return hit;
      });
    if (kit.overrides.length) result.message = '预览:假定触发条件已满足';
    kit.done = true;
    result.ready = true;
  } else if (kit.type === 'note' && action === 'submit') {
    kit.mine = String(detail.text || '');
    kit.done = true;
    result.ready = true;
    result.message = '预览留言，仅自己可见';
  } else if (kit.type === 'photocheck' && action === 'shoot') {
    kit.lastUrl = detail.tempFilePath || '';
    result.message = '照片仅供预览，未上传或识别';
  } else if (kit.type === 'typein' && action === 'start') {
    if (kit.passed || (kit.tries > 0 && kit.attempts >= kit.tries)) return null;
    result.startedAt = now;
    kit.startConfirmed = true;
  } else if (kit.type === 'typein' && action === 'submit') {
    if (!startedAt || kit.passed || (kit.tries > 0 && kit.attempts >= kit.tries)) return null;
    const normalize = value => kit.caseSensitive ? String(value).trim() : String(value).trim().toLowerCase();
    kit.passed = normalize(detail.text || '') === normalize(kit.target) && now - startedAt <= kit.seconds * 1000;
    kit.attempts += 1;
    kit.startConfirmed = false;
    result.startedAt = 0;
    result.ready = kit.passed || (kit.tries > 0 && kit.attempts >= kit.tries);
    result.message = kit.passed ? '预览：挑战完成' : '预览：未通过';
  } else return null;
  return result;
}

module.exports = { applyPreviewAction, previewD20Result, SANDBOX_LABEL };
