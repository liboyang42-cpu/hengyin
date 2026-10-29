#!/usr/bin/env node
/*
 * Harness: run the mini-program template editor's OWN advanced-config pipeline
 * (pages/publish/utils/publish/advanced-game-config.js) on a JSON file of
 * { key: advancedConfigObject | advancedConfigJsonString }.
 *
 * Per key it does what pages/publish/temp/index.js does on save
 * (index.js:3129-3132 / 3152): parse(raw) -> validate(model, opts) -> serialize(model, opts),
 * then reports ok/errors, the serialized JSON, and any field the round trip rewrote.
 *
 * Usage:
 *   node validate.js <configs.json> [--adopted] [--java] [--quiet] [--no-diff]
 *     --adopted   validate as "adopted from public library" (opts.adoptedFromLibrary=true,
 *                 like temp/index.js:3143 when formData.originalTemplateId > 0)
 *     --java      also feed the xcx-serialized output to the repo's real Java
 *                 AdvancedGameConfigValidator (needs ./run_java_validator.sh, built by javac)
 *     --quiet     do not print serialized JSON
 *     --no-diff   do not print input-vs-serialized differences
 *   env XCX_ROOT  path to chengyinhub-xcx (default /home/user/hengyin/chengyinhub-xcx)
 *
 * Exit code: 0 if every key passed xcx validation, 1 otherwise.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const args = process.argv.slice(2);
const file = args.find(a => !a.startsWith('--'));
if (!file) {
  console.error('usage: node validate.js <configs.json> [--adopted] [--java] [--quiet] [--no-diff]');
  process.exit(2);
}
const flag = name => args.includes('--' + name);
const XCX = process.env.XCX_ROOT || path.resolve(__dirname, '../../../../../chengyinhub-xcx');

// Defensive stubs: the module tree is pure today, but keep it loadable if a wx/getApp call sneaks in.
if (typeof global.wx === 'undefined') {
  global.wx = new Proxy({}, { get: () => () => undefined });
}
if (typeof global.getApp === 'undefined') global.getApp = () => ({ globalData: {} });
if (typeof global.getCurrentPages === 'undefined') global.getCurrentPages = () => [];

const agc = require(path.join(XCX, 'pages/publish/utils/publish/advanced-game-config.js'));
const catalog = require(path.join(XCX, 'pages/publish/utils/publish/node-game-catalog.js'));

/* Sections an ordinary author can create from the template editor UI
 * (pages/publish/temp): the game picker = node-game-catalog BASE_GAMES (PICKER_GROUPS),
 * plus the three R5 "附加规则" modifiers (timer / timeWindow / leaderboard).
 * scan is a 参与方式 (participation), not a game. Everything else is passthrough/legacy. */
const pickerKeys = [];
catalog.BASE_GAMES.forEach(base => {
  if (base.disabled) return;
  if (base.forms) base.forms.forEach(f => pickerKeys.push(f.gameKey));
  else if (base.gameKey) pickerKeys.push(base.gameKey);
});
const UI_SECTIONS = new Set(pickerKeys.map(k => (catalog.findGame(k) || {}).section).filter(Boolean)
  .concat(['timer', 'timeWindow', 'leaderboard', 'scan']));
const UI_NOTES = {
  branch: 'standalone branch (startStepId/score/outcomeCode) is only editable on legacy templates without outcomeRules v1; new authors get "反馈分段（本站内）" (temp/index.wxml:1200-1228)',
  random: 'author UI: 抽卡 form of 随机结果',
  diyName: 'NOT in author picker (no editor panel) — passthrough only',
  slowTask: 'NOT in author picker (no editor panel) — passthrough only',
  musicCorner: 'NOT in author picker — passthrough only',
  silentOrder: 'NOT in author picker — passthrough only',
  multiplayer: 'modifier exists but disabled in UI: 规则待定义 (node-game-catalog.js:94)',
};

function sectionsOf(cfg) {
  return Object.keys(cfg || {}).filter(k => cfg[k] && typeof cfg[k] === 'object' && cfg[k].enabled);
}

function diff(a, b, prefix, out) {
  if (JSON.stringify(a) === JSON.stringify(b)) return out;
  const isObj = v => v && typeof v === 'object';
  if (isObj(a) && isObj(b) && Array.isArray(a) === Array.isArray(b)) {
    const keys = new Set(Object.keys(a).concat(Object.keys(b)));
    keys.forEach(k => diff(a[k], b[k], prefix ? prefix + '.' + k : k, out));
    return out;
  }
  out.push({ path: prefix, from: a, to: b });
  return out;
}

const input = JSON.parse(fs.readFileSync(file, 'utf8'));
const opts = { adoptedFromLibrary: flag('adopted') };
let failures = 0;
const javaBatch = {};

Object.keys(input).forEach(key => {
  const raw = input[key];
  const rawObj = typeof raw === 'string' ? JSON.parse(raw) : raw;
  const parsed = agc.parse(raw);
  const lines = [];
  if (parsed.error) lines.push('parse error: ' + parsed.error);
  const enabled = sectionsOf(rawObj);
  // root-level fields the xcx module does understand (present / mistakeTier / variants / outcomeRules ...)
  const ROOT_FIELDS = ['schemaVersion', 'present', 'mistakeTier', 'variants', 'outcomeRules', 'interactionRules', 'interactionTools', 'clues', 'requires', 'effects'];
  const unknown = Object.keys(rawObj).filter(k => ROOT_FIELDS.indexOf(k) < 0 && agc.SECTIONS.indexOf(k) < 0);
  const error = agc.validate(parsed.value, opts);
  let serialized = null;
  let serializeError = '';
  try { serialized = agc.serialize(parsed.value, opts); } catch (e) { serializeError = e.message; }
  const ok = !parsed.error && !error && !serializeError;
  if (!ok) failures += 1;

  console.log((ok ? '[XCX OK ] ' : '[XCX ERR] ') + key);
  console.log('    enabled sections: ' + (enabled.join(', ') || '(none)')
    + '   detected game: ' + (catalog.detectGame(parsed.value) || '(none)'));
  enabled.forEach(s => {
    const ui = UI_SECTIONS.has(s);
    const note = UI_NOTES[s] || (agc.SECTIONS.indexOf(s) < 0 ? 'xcx editor does not model this section — kept as unknown passthrough' : '');
    console.log('    - ' + s + ': ' + (ui ? 'author-UI' : 'NOT author-UI') + (note ? ' — ' + note : ''));
  });
  if (unknown.length) console.log('    passthrough keys (not modelled by xcx editor): ' + unknown.join(', '));
  if (error) console.log('    validate(): ' + error);
  if (serializeError && serializeError !== error) console.log('    serialize(): ' + serializeError);
  if (serialized !== null) {
    if (!flag('quiet')) console.log('    serialized: ' + (serialized || '"" (no enabled section -> stored as empty)'));
    if (!flag('no-diff') && serialized) {
      const out = JSON.parse(serialized);
      // compare only what the author supplied: report fields the round trip rewrote or dropped
      const changes = diff(rawObj, out, '', []).filter(c => c.from !== undefined);
      const added = diff(rawObj, out, '', []).filter(c => c.from === undefined && enabled.some(s => c.path.startsWith(s + '.')));
      changes.forEach(c => console.log('    CHANGED ' + c.path + ': ' + JSON.stringify(c.from) + ' -> ' + JSON.stringify(c.to)));
      if (added.length) console.log('    added defaults: ' + added.map(c => c.path + '=' + JSON.stringify(c.to)).join(', '));
    }
    if (serialized) javaBatch[key] = serialized;
  }
  lines.forEach(l => console.log('    ' + l));
});

console.log('\nxcx summary: ' + (Object.keys(input).length - failures) + ' ok, ' + failures + ' rejected'
  + (opts.adoptedFromLibrary ? ' (adoptedFromLibrary=true)' : ''));

if (flag('java')) {
  const runner = path.join(__dirname, 'run_java_validator.sh');
  const tmp = path.join(__dirname, '.xcx-serialized.json');
  fs.writeFileSync(tmp, JSON.stringify(javaBatch));
  console.log('\n--- Java AdvancedGameConfigValidator on the xcx-serialized output ---');
  try {
    process.stdout.write(execFileSync(runner, [tmp, '--quiet'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }));
  } catch (e) {
    console.log('java run failed: ' + (e.stdout || e.message));
  }
}
process.exit(failures ? 1 : 0);
