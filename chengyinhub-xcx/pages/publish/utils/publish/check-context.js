/**
 * 玩法模板 · 检定段(原型 A e-temp / e-temp-mod,2026-10-28)的纯函数。
 *
 * 模板编辑页本身不读主题;从主题节点进来时,fabu 把主题 journeyRules 里检定要用的几样
 * (d20 开关、属性、魔鬼交易开关、情境加值上限、进度钟、特质)压成一小段 checkCtx 带过来。
 * 键名(counter.* / 特质 key)只在这里和存盘的配置里出现,界面只显示名字。
 */
const LUCK_PRESET_LABELS = ['幸运高获得多1分', '幸运低少一分'];
const HP_LADDER_VARS = ['sys.hp'];
const MAX_BARGAIN_EFFECTS = 4;
const DEFAULT_CAP = 6;

const text = v => String(v == null ? '' : v).trim();
const list = v => Array.isArray(v) ? v : [];

/** fabu → temp:从主题 journeyRules 原文压出检定上下文。读不出来就返回 { error: true }。 */
function fromRules(raw) {
  let rules = raw;
  if (typeof raw === 'string') {
    if (!raw.trim()) return { on: false };
    try { rules = JSON.parse(raw); } catch (e) { return { error: true }; }
  }
  if (!rules || typeof rules !== 'object') return { on: false };
  const rpg = rules.rpg && typeof rules.rpg === 'object' ? rules.rpg : {};
  const cap = Number.isInteger(rules.modCap) ? rules.modCap : DEFAULT_CAP;
  return {
    on: rules.checkEnabled === true,
    attrs: list(rules.attributes).filter(a => a && /^counter\./.test(a.key || '') && text(a.label))
      .map(a => ({ key: a.key, label: text(a.label) })),
    bargain: !!(rpg.bargain && rpg.bargain.enabled === true),
    cap,
    clocks: list(rpg.clocks).filter(c => c && /^counter\./.test(c.var || '') && text(c.label))
      .map(c => ({ key: c.var, label: text(c.label), max: Number(c.segments) || 4 })),
    aspects: list(rpg.aspects).filter(a => a && text(a.key) && text(a.label))
      .map(a => ({ key: a.key, label: text(a.label) })),
  };
}

/** 查询串:fabu 跳转时带上;主题没开 d20 检定就不带(编辑页按「d20 检定关」显示,技能只填名字)。 */
function query(raw) {
  if (raw == null || (typeof raw === 'string' && !raw.trim())) return '';
  const ctx = fromRules(raw);
  return ctx.on || ctx.error ? '&checkCtx=' + encodeURIComponent(JSON.stringify(ctx)) : '';
}

/** temp 读查询串。没带(不是从主题进来) = null。 */
function parse(encoded) {
  if (encoded == null || encoded === '') return null;
  for (const read of [v => JSON.parse(v), v => JSON.parse(decodeURIComponent(v))]) {
    try {
      const ctx = read(String(encoded));
      if (ctx && typeof ctx === 'object') return ctx;
    } catch (e) { /* 下一种读法 */ }
  }
  return { error: true };
}

/**
 * 「加上属性」那一行的状态:ok / empty(主题没有属性)/ error(没读到)/ off(d20 检定关,或不是从主题进来)。
 */
function skillState(ctx) {
  if (!ctx) return 'off';
  if (ctx.error) return 'error';
  if (!ctx.on) return 'off';
  return list(ctx.attrs).length ? 'ok' : 'empty';
}

function attrLabel(ctx, key) {
  const hit = list(ctx && ctx.attrs).find(a => a.key === key);
  return hit ? hit.label : '';
}

/** 勾选行:主题的属性 + 「不加属性」(skill 留空)。 */
function skillOptions(ctx, skill) {
  return list(ctx && ctx.attrs).map(a => ({ key: a.key, label: a.label, on: a.key === skill }))
    .concat({ key: '', label: '不加属性', on: !text(skill) });
}

/** 「技能」输入框里的字:存的是属性键时只显示属性名(认不出就空着),从不显示键名。 */
function skillText(ctx, skill) {
  const value = text(skill);
  return /^counter\./.test(value) ? attrLabel(ctx, value) : value;
}

function skillValue(ctx, skill) {
  const state = skillState(ctx);
  if (state === 'error') return '—';
  if (state === 'empty') return '无';
  if (!text(skill)) return '不加属性';
  return attrLabel(ctx, skill) || text(skill);
}

/** 幸运预设和「生命值越低越困难」阶梯有自己的行,不进情境加值列表。 */
function isOwnRow(mod) {
  if (!mod || typeof mod !== 'object') return false;
  if (LUCK_PRESET_LABELS.indexOf(mod.label) >= 0) return false;
  if (mod.when && HP_LADDER_VARS.indexOf(mod.when.var) >= 0 && ['带伤', '重伤'].indexOf(mod.label) >= 0) return false;
  return true;
}

const sign = n => (n < 0 ? '−' : '+') + Math.abs(n);

function nameOf(ctx, kind, key) {
  const pool = kind === 'aspect' ? ctx && ctx.aspects : kind === 'clock' ? ctx && ctx.clocks
    : kind === 'value' ? VALUES : ctx && ctx.attrs;
  const hit = list(pool).find(row => row.key === key);
  return hit ? hit.label : '';
}

/** 情境加值的条件种类(只列名字能从主题里选出来的几种)。 */
const VALUES = [{ key: 'sys.hp', label: '生命值' }, { key: 'sys.luck', label: '幸运值' }];
const MOD_KINDS = [
  { key: 'aspect', label: '有特质' },
  { key: 'attr', label: '属性' },
  { key: 'clock', label: '进度钟' },
  { key: 'value', label: '数值' },
];

/** 一条加值的句子:「有特质 · 夜行者」「属性 · 观察 至少 3」;名字认不出来就用作者存下的 label。 */
function modTitle(ctx, mod) {
  const when = mod.when || {};
  if (when.op === 'HAS_CONDITION') {
    const name = nameOf(ctx, 'aspect', when.value);
    if (name) return '有特质 · ' + name;
  } else if (when.op === 'GTE' && when.var) {
    for (const kind of ['attr', 'clock', 'value']) {
      const name = nameOf(ctx, kind, when.var);
      if (name) return (kind === 'attr' ? '属性' : kind === 'clock' ? '进度钟' : '数值') + ' · ' + name + ' 至少 ' + when.value;
    }
  }
  return text(mod.label) || '情境加值';
}

function modValue(ctx, mod) {
  if (mod.var) return '+' + (attrLabel(ctx, mod.var) || '属性');
  return sign(Number(mod.value) || 0);
}

/** 只算固定加值;加上属性值的那几条在运行期才知道多少。 */
function modTotal(mods) {
  return list(mods).reduce((sum, m) => sum + (m && !m.var && Number(m.value) > 0 ? Number(m.value) : 0), 0);
}

function capOf(ctx) {
  return ctx && Number.isInteger(ctx.cap) ? ctx.cap : DEFAULT_CAP;
}

/** 情境加值列表的行;index 是它在 check.mods 里的真实下标。 */
function modRows(ctx, mods) {
  const all = list(mods);
  const left = capOf(ctx) - modTotal(all);
  return all.map((mod, index) => ({ mod, index })).filter(r => isOwnRow(r.mod)).map(r => ({
    index: r.index,
    title: modTitle(ctx, r.mod),
    value: modValue(ctx, r.mod),
    byAttr: !!r.mod.var,
    attrKey: r.mod.var || '',
    amount: Number(r.mod.value) || 0,
    // 固定加值步进器的上限:这一条自己的值 + 剩下的上限
    max: Math.max(1, (Number(r.mod.value) > 0 ? Number(r.mod.value) : 0) + left),
    threshold: r.mod.when && r.mod.when.op === 'GTE' ? Number(r.mod.when.value) || 0 : null,
  }));
}

/** 「添加加值」半屏:每种列出能选的名字。 */
function modKindRows(ctx) {
  return MOD_KINDS.map(kind => ({
    key: kind.key, label: kind.label,
    names: list(kind.key === 'aspect' ? ctx && ctx.aspects : kind.key === 'clock' ? ctx && ctx.clocks
      : kind.key === 'value' ? VALUES : ctx && ctx.attrs).map(row => ({ key: row.key, label: row.label })),
  })).filter(kind => kind.names.length);
}

/** 选好种类和名字后新建一条加值:默认 +1(上限还有的话),条件「至少 1」。 */
function newMod(ctx, kind, key) {
  const name = nameOf(ctx, kind, key);
  if (!name) return null;
  const when = kind === 'aspect' ? { op: 'HAS_CONDITION', value: key } : { var: key, op: 'GTE', value: 1 };
  const label = kind === 'aspect' ? '有特质 · ' + name
    : (kind === 'attr' ? '属性' : kind === 'clock' ? '进度钟' : '数值') + ' · ' + name;
  return { when, value: 1, label };
}

/** 魔鬼交易的代价种类(AdvancedGameConfigValidator:SET / INC / ADD_TAG / ADD_CONDITION / TICK_CLOCK)。 */
const PRICE_KINDS = [
  { key: 'clock', label: '进度钟' },
  { key: 'attr', label: '属性' },
  { key: 'aspect', label: '加上特质' },
];

function priceKindRows(ctx) {
  return PRICE_KINDS.map(kind => ({
    key: kind.key, label: kind.label,
    names: list(kind.key === 'aspect' ? ctx && ctx.aspects : kind.key === 'clock' ? ctx && ctx.clocks : ctx && ctx.attrs)
      .map(row => ({ key: row.key, label: row.label })),
  })).filter(kind => kind.names.length);
}

function newPrice(ctx, kind, key) {
  if (kind === 'clock') {
    const clock = list(ctx && ctx.clocks).find(c => c.key === key);
    return clock ? { op: 'TICK_CLOCK', var: key, value: 1, max: clock.max } : null;
  }
  if (kind === 'attr') return attrLabel(ctx, key) ? { op: 'INC', var: key, value: -1 } : null;
  if (kind === 'aspect') return nameOf(ctx, 'aspect', key) ? { op: 'ADD_CONDITION', value: key } : null;
  return null;
}

function priceRows(ctx, effects) {
  return list(effects).map((eff, index) => {
    let title = '代价', stepper = null;
    if (eff.op === 'TICK_CLOCK') {
      title = '进度钟 · ' + (nameOf(ctx, 'clock', eff.var) || '进度钟') + ' ' + sign(Number(eff.value) || 0);
      stepper = { value: Number(eff.value) || 1, min: 1, max: Number(eff.max) || 12 };
    } else if (eff.op === 'INC') {
      title = '属性 · ' + (attrLabel(ctx, eff.var) || '属性') + ' ' + sign(Number(eff.value) || 0);
      stepper = { value: Number(eff.value) || 0, min: -5, max: 5 };
    } else if (eff.op === 'ADD_CONDITION') {
      title = '加上特质 · ' + (nameOf(ctx, 'aspect', eff.value) || '特质');
    }
    return { index, title, stepper };
  });
}

/** 魔鬼交易段出现与否:主题开着才出现;不是从主题进来时,只有模板里本来就配了才出现。 */
function bargainVisible(ctx, check) {
  if (ctx && !ctx.error) return ctx.on === true && ctx.bargain === true;
  return !!(check && check.bargain);
}

function bargainError(bargain) {
  if (bargain == null) return '';
  if (!text(bargain.label)) return '请写魔鬼交易的说明。';
  if (!(Number.isInteger(bargain.bonus) && bargain.bonus >= 1 && bargain.bonus <= 3)) return '魔鬼交易的加值只能是 +1 到 +3。';
  const n = list(bargain.effects).length;
  if (n < 1) return '请为魔鬼交易添加代价。';
  if (n > MAX_BARGAIN_EFFECTS) return '魔鬼交易最多 ' + MAX_BARGAIN_EFFECTS + ' 条代价。';
  return '';
}

module.exports = {
  DEFAULT_CAP, MAX_BARGAIN_EFFECTS, VALUES, MOD_KINDS, PRICE_KINDS,
  fromRules, query, parse, skillState, skillOptions, skillText, skillValue, isOwnRow,
  modRows, modKindRows, newMod, modTotal, capOf,
  priceKindRows, newPrice, priceRows, bargainVisible, bargainError,
};
