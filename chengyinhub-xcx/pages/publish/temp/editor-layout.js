/**
 * 玩法模板编辑器 · 版面辅助(2026-11-19 走查对照 Kahoot / Typeform / Questo 创作流,主人规则:逐元素改、不重做、不加说明文字)。
 *
 * 主人定稿(改之前先读):
 *  - 排序:玩法 → 参与方式 → 玩法自己的核心设置(题干 / 选项 / 正确答案)→ 结果反馈 → 玩法规则 → 封面 / 玩法看点;
 *    其余(作答规则里的次数上限 / 次数用完后 / 奖励分、效果作用于、提示、完成奖励、互动工具、所需材料)
 *    收进**一个**「高级设置」折叠组,记住展开状态。带星号的必填项永远不进折叠组;
 *    折叠组里的项报错时(如完成奖励),定位前先自动展开。
 *  - 玩法规则按所选玩法预填(与结果反馈的 1-2-3 步同一份文字),作者可改;改过就不再覆盖。
 *  - 输入问题 = 字段下方红字(cy-inline-error)。「查看预览 / 发布玩法」置灰时点它:
 *    滚到第一处缺项,红字落在那个字段下面。
 */

const ADVANCED_OPEN_KEY = 'cy_temp_editor_advanced_open';

/* 作答规则里折进「高级设置」的选填项。必填的(如拍照审核的次数上限)留在玩法设置里不动。
   kind: number = 数字输入框;qaTries / qaReveal = 选项问答原有的两个下拉。 */
const XP = { field: 'xp', label: '奖励分', kind: 'number', placeholder: '0 表示不给分' };
const XP200 = { field: 'xp', label: '奖励分', kind: 'number', placeholder: '0 至 200' };
const TRIES = field => ({ field, label: '次数上限', kind: 'number', placeholder: '0 表示不限' });
const RULES_BY_SECTION = {
  steps: [XP],
  estimate: [XP],
  pricePair: [XP200, TRIES('maxTries')],
  hiddenObject: [TRIES('maxTries'), XP],
  predict: [XP],
  compass: [XP],
  shout: [XP],
  stopwatch: [TRIES('tries')],
  profile: [XP],
  photoCheck: [XP],
  note: [XP],
  typeIn: [TRIES('tries'), XP],
};

function qaRules(qa) {
  if (qa.mode === 'PICK' && qa.judge !== false) {
    return [
      { field: 'maxTries', label: '次数上限', kind: 'qaTries' },
      { field: 'reveal', label: '次数用完后', kind: 'qaReveal' },
      XP200,
    ];
  }
  if (qa.mode === 'PICK') return [XP200];
  return [TRIES('maxTries'), XP200];
}

/** 当前玩法折进「高级设置 · 作答规则」的行。section 用来回写 advanced[section][field]。 */
function answerRuleRows(advanced, gameSection, segmentOn) {
  const adv = advanced || {};
  const rows = [];
  const push = (section, list) => list.forEach(row => rows.push(Object.assign({ section, key: section + '.' + row.field }, row)));
  if (gameSection === 'qa' || (segmentOn && segmentOn.qa)) push('qa', qaRules(adv.qa || {}));
  if (RULES_BY_SECTION[gameSection]) push(gameSection, RULES_BY_SECTION[gameSection]);
  if (adv.blindTaste && adv.blindTaste.enabled) push('blindTaste', [XP]);
  return rows;
}

/* 每种问答形式自己的示例文字(前置说明原先三种形式共用「先闭上眼睛尝一口」,那是闭眼盲品的)。 */
const QA_PLACEHOLDERS = {
  TYPE: { title: '例如 这座桥建于哪一年？', lead: '例如 桥头石碑上刻着答案' },
  PICK: { title: '例如 店门口挂的是哪种灯笼？', lead: '例如 先看看门口的招牌' },
  SHOT: { title: '例如 和店门口的石狮子合个影', lead: '例如 走到古树正前方' },
};

/* 玩法规则预填:步骤还空着,或仍是上次自动填的那份,才用新玩法的步骤覆盖;作者动过就不碰。 */
function clean(steps) { return (steps || []).map(one => String(one || '').trim()); }
function shouldPrefillRules(current, lastAuto) {
  const now = clean(current);
  if (!now.some(Boolean)) return true;
  return !!lastAuto && JSON.stringify(now.filter(Boolean)) === JSON.stringify(clean(lastAuto).filter(Boolean));
}

/* 高级设置里的错误键:定位到它们之前先展开折叠组。 */
const ADVANCED_GROUP_ERRORS = ['reward'];

/* 玩法设置的校验文字 → 落在哪个字段下面(其余仍显示在玩法设置顶部)。 */
function advancedErrorField(message, gameSection) {
  const text = String(message || '');
  if (!text || gameSection !== 'qa') return '';
  if (/题干/.test(text)) return 'qa.title';
  if (/正确答案/.test(text) && !/选项/.test(text)) return 'qa.answerText';
  if (/选项/.test(text)) return 'qa.options';
  return '';
}

/* 折叠组里动态绑定(data-section="{{item.section}}")可能出现的全部字段,给「绑定必须真存在于默认配置」那条门禁逐个核对 */
function allAnswerRuleFields() {
  const out = [];
  const add = (section, list) => list.forEach(row => { if (!out.some(r => r.section === section && r.field === row.field)) out.push({ section, field: row.field }); });
  add('qa', qaRules({ mode: 'PICK' }).concat(qaRules({ mode: 'TYPE' })));
  Object.keys(RULES_BY_SECTION).forEach(section => add(section, RULES_BY_SECTION[section]));
  add('blindTaste', [XP]);
  return out;
}

function readAdvancedOpen(store) {
  try { return !!(store && store.getStorageSync(ADVANCED_OPEN_KEY)); } catch (e) { return false; }
}
function writeAdvancedOpen(store, open) {
  try { if (store) store.setStorageSync(ADVANCED_OPEN_KEY, open ? 1 : 0); } catch (e) { /* 存不住只是下次默认收起 */ }
}

module.exports = {
  ADVANCED_OPEN_KEY,
  ADVANCED_GROUP_ERRORS,
  QA_PLACEHOLDERS,
  answerRuleRows,
  allAnswerRuleFields,
  shouldPrefillRules,
  advancedErrorField,
  readAdvancedOpen,
  writeAdvancedOpen,
};
