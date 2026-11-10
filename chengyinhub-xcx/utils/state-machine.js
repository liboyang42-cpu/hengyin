/**
 * 显式状态机小工具（qx-sm）。把「状态码 → 文案/徽章/是否终态/允许迁移」收进一张表，
 * 页面只查表，不再各写一份数组或 if 链。
 *
 * 只做展示与本地守卫；状态的真源永远是服务端。未知码一律落 unknown（不猜成某个已知态）。
 * 用法见 docs/architecture/state-machines/README.md。
 */
'use strict';

function normalizeCode(code) {
  if (typeof code === 'number') return Number.isInteger(code) ? code : null;
  if (typeof code === 'string' && /^-?\d+$/.test(code.trim())) return Number(code.trim());
  return null;
}

/**
 * @param {{name:string, states:Array<{code:number,key:string,label:string}>, transitions:Object<string, string[]>}} spec
 *   transitions 以 key 为键：{ pending: ['accepted', ...] }；未列出的 key 视为终态。
 */
function createStatusMachine(spec) {
  if (!spec || !Array.isArray(spec.states) || !spec.states.length) throw new Error('state machine needs states');
  const byCode = new Map();
  const byKey = new Map();
  spec.states.forEach(function (s) {
    if (!Number.isInteger(s.code) || !s.key) throw new Error(spec.name + ': bad state ' + JSON.stringify(s));
    if (byCode.has(s.code) || byKey.has(s.key)) throw new Error(spec.name + ': duplicate state ' + s.key);
    byCode.set(s.code, Object.freeze(Object.assign({}, s)));
    byKey.set(s.key, byCode.get(s.code));
  });
  const transitions = spec.transitions || {};
  Object.keys(transitions).forEach(function (from) {
    if (!byKey.has(from)) throw new Error(spec.name + ': unknown transition source ' + from);
    transitions[from].forEach(function (to) {
      if (!byKey.has(to)) throw new Error(spec.name + ': unknown transition target ' + to);
    });
  });

  function stateOf(code) {
    const n = normalizeCode(code);
    return n === null ? null : (byCode.get(n) || null);
  }
  function keyOf(code) {
    const s = stateOf(code);
    return s ? s.key : 'unknown';
  }
  function targetsOf(code) {
    const s = stateOf(code);
    return s ? (transitions[s.key] || []).slice() : [];
  }
  return Object.freeze({
    name: spec.name,
    states: Object.freeze(spec.states.map(function (s) { return byCode.get(s.code); })),
    stateOf: stateOf,
    keyOf: keyOf,
    /** 未知码返回 fallback（默认 ''），不把未知显示成任何已知态。 */
    label: function (code, fallback) {
      const s = stateOf(code);
      return s ? s.label : (fallback === undefined ? '' : fallback);
    },
    /** 按 code 下标排好的数组，供保留旧数组导出的模块派生使用；空位为 undefined。 */
    toArray: function (field) {
      const out = [];
      byCode.forEach(function (s, code) { if (code >= 0) out[code] = s[field]; });
      return Object.freeze(out);
    },
    isKnown: function (code) { return !!stateOf(code); },
    isTerminal: function (code) {
      const s = stateOf(code);
      return !!s && targetsOf(code).length === 0;
    },
    targets: targetsOf,
    can: function (from, to) {
      const target = stateOf(to);
      return !!target && targetsOf(from).indexOf(target.key) >= 0;
    },
  });
}

module.exports = { createStatusMachine, normalizeCode };
