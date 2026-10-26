'use strict';
const values = require('../../fabu/theme-values.js');

const OPTIONS = [
  { type: 'hp', label: '精力', example: '游戏失败时消耗' },
  { type: 'luck', label: '幸运', example: 'D20 失败后重掷' },
];

function parse(raw) {
  const fresh = raw == null || (typeof raw === 'string' && !raw.trim());
  let rules = raw;
  if (typeof raw === 'string') {
    try { rules = raw.trim() ? JSON.parse(raw) : {}; } catch (_) { throw new Error('初始状态配置无法读取'); }
  }
  if (rules == null) rules = {};
  if (typeof rules !== 'object' || Array.isArray(rules)) throw new Error('初始状态配置无法读取');
  rules = JSON.parse(JSON.stringify(rules));
  return Object.assign(rules, {
    schemaVersion: 1,
    stateEnabled: rules.stateEnabled === true,
    hp: Object.assign({ enabled: !fresh, init: 10, max: 10 }, rules.hp),
    luck: Object.assign({ enabled: !fresh, init: 3, max: 3 }, rules.luck),

  });
}

// 思维阁(2026-09-22):念头清单与初始状态同住 journeyRules,作者那边是并排的两个入口。
// 字段格式跟服务端 JourneyRulesValidator 一致;作者端只允许配置一个伏笔。
// parse 原样保留存量清单,避免读取旧资料时静默丢失内容。
const THOUGHT_KEY = /^[a-z][a-z0-9_]{0,31}$/;
const MAX_THOUGHTS = 1;

function thoughts(list) {
  if (list == null) return null;
  if (!Array.isArray(list)) throw new Error('伏笔清单必须是数组');
  if (!list.length) return null;
  if (list.length > MAX_THOUGHTS) throw new Error('只能配置一个伏笔，请删除多余伏笔');
  const seen = [];
  return list.map((row) => {
    const key = String((row && row.key) || '').trim();
    if (!THOUGHT_KEY.test(key)) throw new Error('伏笔编号不合法:' + key);
    if (seen.indexOf(key) >= 0) throw new Error('伏笔编号重复:' + key + ';同一个 key 的进度会互相覆盖');
    seen.push(key);
    const name = String((row && row.name) || '').trim();
    if (!name || name.length > 20) throw new Error('伏笔要有名字，且不超过 20 字');
    const desc = String((row && row.desc) || '').trim();
    if (desc.length > 60) throw new Error('伏笔的一句话不能超过 60 字');
    // 应验方式(2026-09-22 用户裁决「伏笔不能只是微信步数」):走够步数,或选到某些选项,至少一种。
    const rawNeed = row && row.need;
    const hasNeed = rawNeed != null && String(rawNeed).trim() !== '';
    const need = Number(rawNeed);
    if (hasNeed && (!Number.isInteger(need) || need < 1 || need > 100000)) {
      throw new Error('伏笔的步数阈值要在 1 到 100000 之间');
    }
    const when = conditions(row && row.when, name);
    const doneWhen = conditions(row && row.doneWhen, name);
    if (!hasNeed && !doneWhen) throw new Error('伏笔「' + name + '」还没有应验方式:填步数，或选一个选项');
    const item = { key: key, name: name };
    if (hasNeed) item.need = need;
    if (desc) item.desc = desc;
    if (when) item.when = when;
    if (doneWhen) item.doneWhen = doneWhen;
    return item;
  });
}

const STATE_TAG = /^tag\.[a-z][a-z0-9_]{0,47}$/;

/** 伏笔条件:选项标签(HAS_TAG)或到过某站(NODE_COMPLETED),任一满足即算。空 = 没配。 */
function conditions(list, name) {
  if (list == null) return null;
  if (!Array.isArray(list)) throw new Error('伏笔「' + name + '」的条件必须是数组');
  if (!list.length) return null;
  if (list.length > 8) throw new Error('伏笔「' + name + '」的条件最多 8 条');
  return list.map((c) => {
    const op = c && c.op;
    if (op === 'HAS_TAG') {
      const tag = String(c.value || '').trim();
      if (!STATE_TAG.test(tag)) throw new Error('伏笔「' + name + '」的选项条件要引用 tag. 开头的标签:' + tag);
      return { op: op, value: tag };
    }
    if (op === 'NODE_COMPLETED') {
      const id = c.nodeId;
      const exactNumber = typeof id === 'number' && Number.isSafeInteger(id) && id > 0;
      const exactString = typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id)
        && (id.length < 19 || id <= '9223372036854775807');
      if (exactNumber || exactString) return { op: op, nodeId: id };
    }
    throw new Error('伏笔「' + name + '」的条件只能是选项标签或到过某站');
  });
}

/**
 * 本主题玩法里「挂了贴标签效果」的选项 —— 伏笔面板里作者挑「选到这些选项时」用的清单。
 * 标签由选项效果 ADD_TAG 写进本局,服务端据此结算伏笔。读不出的玩法配置跳过,不拦作者。
 */
function optionTagsOf(chapters) {
  const out = [];
  const seen = {};
  const labelOf = (o) => String((o && (o.label || o.text || o.title || o.question)) || '').trim();
  const walk = (node, ctx) => {
    if (Array.isArray(node)) { node.forEach((x) => walk(x, ctx)); return; }
    if (!node || typeof node !== 'object') return;
    (Array.isArray(node.effects) ? node.effects : []).forEach((eff) => {
      const tag = String((eff && eff.op === 'ADD_TAG' && eff.value) || '').trim();
      if (!STATE_TAG.test(tag) || seen[tag]) return;
      seen[tag] = true;
      // 站点名与题目常常同名(「站起来回答 · 站起来回答 · …」),相邻重复的只留一个
      const parts = ctx.concat(labelOf(node) || tag).filter((x, i, arr) => i === 0 || x !== arr[i - 1]);
      out.push({ tag: tag, label: parts.join(' · ') });
    });
    const next = Array.isArray(node.options) && labelOf(node) ? ctx.concat(labelOf(node)) : ctx;
    Object.keys(node).forEach((k) => { if (k !== 'effects') walk(node[k], k === 'options' ? next : ctx); });
  };
  (chapters || []).forEach((chapter) => (chapter && chapter.nodes || []).forEach((n) => {
    let config;
    try { config = JSON.parse((n.templateInfo && n.templateInfo.advancedConfigJson) || 'null'); } catch (e) { return; }
    if (config) walk(config, [String(n.name || '').trim()].filter(Boolean));
  }));
  return out;
}

function integer(value, label, min, max) {
  const number = Number(value);
  if (value == null || String(value).trim() === '' || !Number.isInteger(number) || number < min || number > max) {
    throw new Error(label + '须为 ' + min + ' 至 ' + max + ' 的整数');
  }
  return number;
}

function serialize(input, names) {
  const rules = parse(input);
  const hpName = names && String(names.hpName || '').trim() || '精力';
  const luckName = names && String(names.luckName || '').trim() || '幸运';
  rules.hp.max = integer(rules.hp.max, hpName + '上限', 1, 20);
  rules.hp.init = integer(rules.hp.init, '初始' + hpName, 1, rules.hp.max);
  rules.luck.max = integer(rules.luck.max, luckName + '上限', 0, 5);
  rules.luck.init = integer(rules.luck.init, '初始' + luckName, 0, rules.luck.max);
  if (Array.isArray(rules.attributes)) {
    const keys = new Set();
    rules.attributes = rules.attributes.map(row => {
      if (!values.COUNTER.test(row.key || '')) return row;
      if (keys.has(row.key)) throw new Error('数值编号重复：' + row.key);
      keys.add(row.key);
      return values.attribute(row);
    });
    if (rules.attributes.length > 32) throw new Error('初始属性最多配置 32 项');
  }
  if (rules.statLinks) rules.statLinks = values.links(rules.statLinks, rules.attributes, rules.hp.enabled);
  // 降低资源上限时同步收口已有恢复值，避免默认恢复量大于新上限而无法保存。
  const recovery = Object.assign({ stationEndIfHurt: 2, stageRestHpFloor: 6, stageRestLuckFloor: 1, exhaustRestHp: 5 }, rules.recovery);
  ['stationEndIfHurt', 'stageRestHpFloor', 'exhaustRestHp'].forEach(key => { recovery[key] = Math.min(recovery[key], rules.hp.max); });
  recovery.stageRestLuckFloor = Math.min(recovery.stageRestLuckFloor, rules.luck.max);
  rules.recovery = recovery;
  const list = thoughts(rules.thoughts);
  if (list) rules.thoughts = list; else delete rules.thoughts;
  return JSON.stringify(rules);
}

function serializeChanges(raw, changed, names) {
  const serialized = serialize(changed, names);
  return raw ? require('./preserve-json-edit.js')(raw, parse(raw), changed, serialized) : serialized;
}

function preserveChanges(raw, changed) {
  return raw ? require('./preserve-json-edit.js')(raw, parse(raw), changed, changed) : JSON.stringify(changed);
}

module.exports = { optionTagsOf, parse, serialize, serializeChanges, preserveChanges, OPTIONS };
