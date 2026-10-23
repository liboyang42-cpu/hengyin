// 编辑页试玩的判定屏(照「城瘾玩法试玩台」:玩完一整屏涌上来,成功绿、失败红,反馈就写在这一屏)。
// 只在作者自己的手机上算:答案来自他刚填的配置,不经过任何下发通道;正式游玩仍由服务端判定。

const pickIds = detail => (Array.isArray(detail.optionIds) ? detail.optionIds : [detail.optionId]).filter(Boolean);

/** 本地判一次对错:'win' / 'fail';判不了(估数、比价这类答案在玩法自己那儿)返回 ''。
    台面自己判过的(verdict)照它的;抽卡、扫码玩完就算成功。 */
function judge(advanced, kit, action, detail) {
  const d = detail || {};
  if (!kit) return '';
  if (action === 'verdict') return d.kind === 'fail' ? 'fail' : 'win';
  if (action === 'drawn' || action === 'scanned') return 'win';
  if (kit.type === 'typein') return kit.passed ? 'win' : 'fail';
  const qa = advanced && advanced.qa;
  if (kit.type !== 'qa' || !qa) return '';
  if (action === 'shoot') return 'win';
  if (d.mode === 'type') {
    // 与服务端 AdvancedGameRuntimeServiceImpl 同一条:去空格、不分大小写,多个答案用顿号或半角逗号隔开
    const got = String(d.input || '').trim().toLowerCase();
    return String(qa.answerText || '').split(/[、,]/).some(one => one.trim() && one.trim().toLowerCase() === got) ? 'win' : 'fail';
  }
  if (d.mode === 'pick') {
    const options = Array.isArray(qa.options) ? qa.options : [];
    const ids = pickIds(d);
    const chosen = ids.length ? options.filter(o => ids.indexOf(o.id) >= 0) : [options[d.index]].filter(Boolean);
    if (!chosen.length) return 'fail';
    if (qa.multi) {
      const right = options.filter(o => o.correct).map(o => o.id).sort().join('|');
      return chosen.map(o => o.id).sort().join('|') === right ? 'win' : 'fail';
    }
    return chosen[0].correct ? 'win' : 'fail';
  }
  return 'win';
}

/** 判定屏上那句话和配图:作者在「结果反馈」里打开了哪一边就带哪一边,没打开就只有对勾 / 两个字。 */
function feedback(advanced, kind) {
  const rules = (advanced && advanced.outcomeRules) || {};
  const row = (kind === 'win' ? rules.pass : rules.fail) || {};
  return {
    text: typeof row.reply === 'string' ? row.reply.trim() : '',
    images: (Array.isArray(row.images) ? row.images : []).filter(url => typeof url === 'string' && url).slice(0, 3),
  };
}

module.exports = { judge, feedback };
