'use strict';
/* 角色(Cast)· 编辑器侧真源 —— docs/design/cast-contract-1119.md(业主 2026-11-19 批准)。
 *
 * topic.cast_config 在编辑器里是 formData.castConfig(对象或 null);保存时随主题 payload 的
 * castConfig 上送(JSON 字符串,与 journeyRules / look 同一条路),服务端按同一组上限再校验一次。
 * null = 从没开过:老主题不带这个字段,编辑器里也什么都不多出来。
 * 关掉开关只把 enabled 置 false,角色、台词、信任、归属都留着(契约「Turning the switch off keeps data」)。
 *
 * 这里只放纯函数(无 wx / getApp),给 fabu 页面与单测共用。
 */

var COLORS = ['#E8604C', '#3D8BFD', '#2FB57A', '#B98AF0', '#F2B33D', '#8E8E93']; /* ds-ok 契约数据:cast[].color 只能是这六个字面值(后端逐字校验),不是样式 */
var PRESETS = [
  { value: 'bright_female', label: '明亮女声' },
  { value: 'warm_female', label: '温柔女声' },
  { value: 'deep_male', label: '低沉男声' },
  { value: 'youth', label: '少年' },
  { value: 'elder', label: '长者' },
];
var VOICE_MODES = ['preset', 'record', 'none'];
var MAX_CAST = 12;
var MAX_IDENTITIES = 4;
var NAME_MAX = 8;
var IDENTITY_MAX = 30;
// 与后端 CastConfigSupport(#873)同口径:身份名 1–8 字、说明 ≤24 字、角色 id = c_ + 6–12 位
var ID_TITLE_MAX = 8;
var ID_SUB_MAX = 24;
var MAX_CLUES = 6;
var CLUE_MAX = 40;
var ENDING_LINE_MAX = 60;
var LINE_MAX = 300;
var TRUST_MIN = -2;
var TRUST_MAX = 2;
var PLAYER = 'player';
var BANDS = ['low', 'mid', 'high'];
var MEDIA_URL = /^(https:\/\/\S+|media-asset:[1-9][0-9]*)$/;
var CAST_ID = /^c_[a-z0-9]{6,12}$/;
var ID_KEY = /^i_[a-z0-9]{4,16}$/;

function str(v) { return v == null ? '' : String(v); }
function len(v) { return Array.from(str(v)).length; }
function isObj(v) { return !!v && typeof v === 'object' && !Array.isArray(v); }

function presetLabel(value) {
  var hit = PRESETS.find(function (p) { return p.value === value; });
  return hit ? hit.label : '';
}

/** 一个角色的界面形状。看不懂的字段丢掉;值不合法的改回默认(读侧宽,存侧严)。 */
function normalizeMember(raw, index) {
  var m = isObj(raw) ? raw : {};
  var voiceRaw = isObj(m.voice) ? m.voice : {};
  var mode = VOICE_MODES.indexOf(voiceRaw.mode) >= 0 ? voiceRaw.mode : 'none';
  var voice = { mode: mode };
  if (mode === 'preset') voice.preset = presetLabel(voiceRaw.preset) ? voiceRaw.preset : PRESETS[0].value;
  if (mode === 'record') voice.url = str(voiceRaw.url).trim();
  var lines = isObj(m.endingLines) ? m.endingLines : {};
  return {
    id: str(m.id),
    name: str(m.name),
    identity: str(m.identity),
    avatarUrl: str(m.avatarUrl).trim(),
    color: COLORS.indexOf(m.color) >= 0 ? m.color : COLORS[(Number(index) || 0) % COLORS.length],
    voice: voice,
    clues: (Array.isArray(m.clues) ? m.clues : []).map(function (c) {
      var clue = isObj(c) ? c : {};
      var chapterId = clue.chapterId == null || clue.chapterId === '' ? null : clue.chapterId;
      return { text: str(clue.text), chapterId: chapterId };
    }),
    endingLines: { low: str(lines.low), mid: str(lines.mid), high: str(lines.high) },
  };
}

/** 服务端回填 / 本地草稿 → 界面形状。raw 为空或读不出来 ⇒ null(= 这个主题没开过角色)。 */
function parseCastConfig(raw) {
  var root = raw;
  if (typeof raw === 'string') {
    if (!raw.trim()) return null;
    try { root = JSON.parse(raw); } catch (e) { return null; }
  }
  if (!isObj(root)) return null;
  return {
    enabled: root.enabled === true,
    playerName: root.playerName === true,
    playerIdentity: root.playerIdentity === true,
    identities: (Array.isArray(root.identities) ? root.identities : []).map(function (it) {
      var row = isObj(it) ? it : {};
      return { key: str(row.key), title: str(row.title), sub: str(row.sub) };
    }),
    cast: (Array.isArray(root.cast) ? root.cast : []).map(normalizeMember),
  };
}

function emptyCastConfig() {
  return { enabled: true, playerName: true, playerIdentity: false, identities: [], cast: [] };
}

function isEnabled(cfg) { return !!(cfg && cfg.enabled === true); }

function randomSuffix(rand) {
  var r = typeof rand === 'function' ? rand : Math.random;
  var s = '';
  while (s.length < 6) s += Math.floor(r() * 36).toString(36);
  return s.slice(0, 6);
}

/** 新 id:c_ / i_ + 6 位,与已有的不撞。 */
function freshId(prefix, used, rand) {
  var taken = used || [];
  for (var i = 0; i < 50; i += 1) {
    var id = prefix + randomSuffix(rand);
    if (taken.indexOf(id) < 0) return id;
  }
  throw new Error('生成编号失败，请重试');
}

/** 新角色:颜色取第一个还没人用的(都用过就按序轮)。 */
function newMember(cfg, rand) {
  var cast = (cfg && cfg.cast) || [];
  var used = cast.map(function (m) { return m.color; });
  var color = COLORS.find(function (c) { return used.indexOf(c) < 0; }) || COLORS[cast.length % COLORS.length];
  return normalizeMember({
    id: freshId('c_', cast.map(function (m) { return m.id; }), rand),
    color: color,
    voice: { mode: 'none' },
  }, cast.length);
}

function newIdentity(cfg, rand) {
  var list = (cfg && cfg.identities) || [];
  return { key: freshId('i_', list.map(function (i) { return i.key; }), rand), title: '', sub: '' };
}

/* ===== 校验:每条问题带 field(界面定位用)+ message(红字)===== */

/** 单个角色的问题;others = 同主题其它角色(查重名)。chapterIds = 已保存章节 id(线索去向)。 */
function memberIssues(member, others, chapterIds) {
  var m = member || {};
  var out = [];
  var name = str(m.name).trim();
  if (!name) out.push({ field: 'name', message: '给角色起个名字' });
  else if (len(name) > NAME_MAX) out.push({ field: 'name', message: '名字最多 ' + NAME_MAX + ' 个字' });
  else if ((others || []).some(function (o) { return o && o.id !== m.id && str(o.name).trim() === name; })) {
    out.push({ field: 'name', message: '已经有叫「' + name + '」的角色' });
  }
  if (len(str(m.identity).trim()) > IDENTITY_MAX) out.push({ field: 'identity', message: '一句话身份最多 ' + IDENTITY_MAX + ' 字' });
  var avatar = str(m.avatarUrl).trim();
  if (avatar && !MEDIA_URL.test(avatar)) out.push({ field: 'avatar', message: '头像没有上传成功，请重新选一张' });
  if (COLORS.indexOf(m.color) < 0) out.push({ field: 'color', message: '选一个颜色' });
  var voice = m.voice || {};
  if (VOICE_MODES.indexOf(voice.mode) < 0) out.push({ field: 'voice', message: '选一种声音' });
  else if (voice.mode === 'preset' && !presetLabel(voice.preset)) out.push({ field: 'voice', message: '选一种声音' });
  else if (voice.mode === 'record' && !MEDIA_URL.test(str(voice.url).trim())) out.push({ field: 'voice', message: '录音还没有上传，请重录' });
  var clues = Array.isArray(m.clues) ? m.clues : [];
  if (clues.length > MAX_CLUES) out.push({ field: 'clues', message: '「你知道的」最多 ' + MAX_CLUES + ' 条' });
  clues.forEach(function (c, i) {
    var text = str(c && c.text).trim();
    if (!text) out.push({ field: 'clue' + i, message: '第 ' + (i + 1) + ' 条还没写' });
    else if (len(text) > CLUE_MAX) out.push({ field: 'clue' + i, message: '每条最多 ' + CLUE_MAX + ' 字' });
    if (c && c.chapterId != null && chapterIds && chapterIds.map(String).indexOf(String(c.chapterId)) < 0) {
      out.push({ field: 'clue' + i, message: '第 ' + (i + 1) + ' 条解锁的章节已经不在了，重新选一章' });
    }
  });
  var lines = m.endingLines || {};
  BANDS.forEach(function (band) {
    if (len(str(lines[band]).trim()) > ENDING_LINE_MAX) out.push({ field: 'ending_' + band, message: '结局台词最多 ' + ENDING_LINE_MAX + ' 字' });
  });
  return out;
}

function identityIssues(list) {
  var out = [];
  var rows = Array.isArray(list) ? list : [];
  if (rows.length > MAX_IDENTITIES) out.push({ field: 'identities', message: '玩家身份最多 ' + MAX_IDENTITIES + ' 个' });
  rows.forEach(function (row, i) {
    var title = str(row && row.title).trim();
    if (!title) out.push({ field: 'identity' + i, message: '第 ' + (i + 1) + ' 个身份还没写名字' });
    else if (len(title) > ID_TITLE_MAX) out.push({ field: 'identity' + i, message: '身份名字最多 ' + ID_TITLE_MAX + ' 字' });
    if (len(str(row && row.sub).trim()) > ID_SUB_MAX) out.push({ field: 'identity' + i, message: '身份说明最多 ' + ID_SUB_MAX + ' 字' });
  });
  return out;
}

/** 整份配置的第一处问题(给保存闸用);没有返回 null。castId 指出是哪个角色。 */
function firstIssue(cfg, chapterIds) {
  if (!cfg) return null;
  var cast = Array.isArray(cfg.cast) ? cfg.cast : [];
  if (cast.length > MAX_CAST) return { field: 'cast', message: '角色最多 ' + MAX_CAST + ' 位' };
  var ids = cast.map(function (m) { return m && m.id; });
  for (var c = 0; c < cast.length; c += 1) {
    if (!CAST_ID.test(str(cast[c].id)) || ids.indexOf(cast[c].id) !== c) return { field: 'cast', castId: cast[c].id, message: '角色编号无效，请删掉重建这个角色' };
    var mi = memberIssues(cast[c], cast, chapterIds);
    if (mi.length) return Object.assign({ castId: cast[c].id }, mi[0], { message: '「' + (str(cast[c].name).trim() || '未命名角色') + '」' + mi[0].message });
  }
  if (cfg.playerIdentity) {
    var keys = (cfg.identities || []).map(function (i) { return i && i.key; });
    var bad = (cfg.identities || []).findIndex(function (i, k) { return !ID_KEY.test(str(i && i.key)) || keys.indexOf(i.key) !== k; });
    if (bad >= 0) return { field: 'identity' + bad, message: '身份编号无效，请删掉重建' };
    var ii = identityIssues(cfg.identities);
    if (ii.length) return ii[0];
    if (!(cfg.identities || []).length) return { field: 'identities', message: '打开了玩家身份，至少加一个身份' };
  }
  return null;
}

/** 草稿保存用:写了一半的身份 / 线索先不带(本机那份不动),免得一行空身份把整份主题的自动保存拦住。 */
function draftView(cfg, chapterIds) {
  var ids = chapterIds ? chapterIds.map(String) : null;
  var out = JSON.parse(JSON.stringify(cfg));
  out.identities = (out.identities || []).filter(function (i) { return str(i && i.title).trim(); });
  if (out.playerIdentity && !out.identities.length) out.playerIdentity = false;
  out.cast = (out.cast || []).map(function (m) {
    // 解锁章已删掉的线索这次不带 —— 改成「开局就有」会让玩家提前看到
    m.clues = (m.clues || []).filter(function (c) {
      return str(c && c.text).trim() && !(ids && c.chapterId != null && ids.indexOf(String(c.chapterId)) < 0);
    });
    return m;
  });
  return out;
}

/** 界面形状 → 上送形状(JSON 字符串)。null 原样 null;有问题就抛那一句。
 *  options.draft:先按 draftView 收掉半截内容;收完还有问题就返回 null(= 这次不带,服务端不改这一列)。 */
function serializeCastConfig(cfgArg, chapterIds, options) {
  if (!cfgArg) return null;
  var draft = !!(options && options.draft);
  var cfg = draft ? draftView(cfgArg, chapterIds) : cfgArg;
  var issue = firstIssue(cfg, chapterIds);
  if (issue && draft) return null;
  if (issue) throw new Error(issue.message);
  var out = {
    enabled: cfg.enabled === true,
    playerName: cfg.playerName === true,
    playerIdentity: cfg.playerIdentity === true,
    identities: (cfg.identities || []).map(function (i) {
      var row = { key: i.key, title: str(i.title).trim() };
      if (str(i.sub).trim()) row.sub = str(i.sub).trim();
      return row;
    }),
    cast: (cfg.cast || []).map(function (m) {
      var voice = { mode: m.voice.mode };
      if (voice.mode === 'preset') voice.preset = m.voice.preset;
      if (voice.mode === 'record') voice.url = str(m.voice.url).trim();
      var row = { id: m.id, name: str(m.name).trim(), identity: str(m.identity).trim(), avatarUrl: str(m.avatarUrl).trim(), color: m.color, voice: voice };
      var clues = (m.clues || []).map(function (c) { return { text: str(c.text).trim(), chapterId: c.chapterId == null ? null : c.chapterId }; });
      if (clues.length) row.clues = clues;
      var lines = {};
      BANDS.forEach(function (b) { var t = str(m.endingLines && m.endingLines[b]).trim(); if (t) lines[b] = t; });
      if (Object.keys(lines).length) row.endingLines = lines;
      return row;
    }),
  };
  return JSON.stringify(out);
}

/* ===== 视图模型 ===== */

// 编辑器里能直接显示的头像只有 https;media-asset:N 由服务端换地址,这里先画首字(本次刚选的图由页面另存预览)
function shownAvatar(url) { return /^https:\/\//.test(str(url).trim()) ? str(url).trim() : ''; }

function initialOf(name) {
  var chars = Array.from(str(name).trim());
  return chars.length ? chars[0] : '?';
}

/** 主题首页「角色」卡:开关 + 叠起来的头像(最多 4 个)+「N 位」 */
function cardView(cfg) {
  var cast = (cfg && cfg.cast) || [];
  return {
    on: isEnabled(cfg),
    count: cast.length,
    faces: cast.slice(0, 4).map(function (m) {
      return { id: m.id, initial: initialOf(m.name), color: m.color, img: shownAvatar(m.avatarUrl) };
    }),
  };
}

/** 角色列表行 */
function listView(cfg) {
  return ((cfg && cfg.cast) || []).map(function (m) {
    var voice = m.voice || {};
    return {
      id: m.id, name: str(m.name).trim() || '未命名角色', identity: str(m.identity).trim(),
      initial: initialOf(m.name), color: m.color, img: shownAvatar(m.avatarUrl),
      voiceLabel: voice.mode === 'preset' ? presetLabel(voice.preset) : voice.mode === 'record' ? '自己录的' : '',
    };
  });
}

/** 「谁在说话」:角色 + 旁白(= 普通段落)+ 玩家 */
function speakerOptions(cfg) {
  var rows = ((cfg && cfg.cast) || []).map(function (m) {
    return { value: m.id, label: str(m.name).trim() || '未命名角色', initial: initialOf(m.name), color: m.color, img: shownAvatar(m.avatarUrl) };
  });
  rows.push({ value: 'narrator', label: '旁白', initial: '', color: '', img: '' });
  rows.push({ value: PLAYER, label: '玩家', initial: '', color: '', img: '' });
  return rows;
}

/** 台词块开头那个灰色小名字。角色被删了 ⇒ ''(按契约当普通文字,不崩)。 */
function speakerName(cfg, speaker) {
  if (speaker === PLAYER) return '玩家';
  var hit = ((cfg && cfg.cast) || []).find(function (m) { return m.id === speaker; });
  return hit ? (str(hit.name).trim() || '未命名角色') : '';
}

function speakerNameMap(cfg) {
  var map = { player: '玩家' };
  ((cfg && cfg.cast) || []).forEach(function (m) { map[m.id] = str(m.name).trim() || '未命名角色'; });
  return map;
}

/* ===== 台词块(type:'line')===== */

function isLineType(type) { return type === 'line'; }

/** 台词块的作者字段;cond 原样带着(契约:optional existing cond shape)。 */
function lineFields(block) {
  var b = block || {};
  var out = { speaker: str(b.speaker).trim(), content: str(b.content) };
  var audio = str(b.audioUrl).trim();
  out.audioUrl = audio;
  if (b.cond != null) out.cond = b.cond;
  return out;
}

/** 上送前的台词检查:说话的人、1–300 字、录音地址。 */
function lineIssue(block) {
  var f = lineFields(block);
  if (!f.speaker) return '这句对白还没选谁在说话';
  if (f.speaker !== PLAYER && !CAST_ID.test(f.speaker)) return '这句对白说话的人无效，请重新选';
  var n = len(f.content.trim());
  if (!n) return '对白还没写内容';
  if (n > LINE_MAX) return '每句对白最多 ' + LINE_MAX + ' 字';
  if (f.audioUrl && !MEDIA_URL.test(f.audioUrl)) return '这句对白的录音没有上传成功，请重录';
  return '';
}

/* ===== 分支信任(D2)===== */

/** 选项上的 trust:只留 -2..2 的非零整数、castId 合法且不重复。castIds 给了就只留还在的角色。 */
function normalizeTrust(list, castIds) {
  var seen = [];
  return (Array.isArray(list) ? list : []).filter(function (row) {
    if (!row || !CAST_ID.test(str(row.castId))) return false;
    if (castIds && castIds.indexOf(row.castId) < 0) return false;
    var d = Number(row.delta);
    if (!Number.isInteger(d) || d === 0 || d < TRUST_MIN || d > TRUST_MAX) return false;
    if (seen.indexOf(row.castId) >= 0) return false;
    seen.push(row.castId);
    return true;
  }).map(function (row) { return { castId: row.castId, delta: Number(row.delta) }; });
}

/** 把某个角色的增减改成 delta(0 = 去掉这一行),顺序按 cast 排。 */
function setTrust(list, castId, delta, castOrder) {
  var d = Math.max(TRUST_MIN, Math.min(TRUST_MAX, Math.round(Number(delta) || 0)));
  var rows = normalizeTrust(list).filter(function (r) { return r.castId !== castId; });
  if (d !== 0) rows.push({ castId: castId, delta: d });
  if (Array.isArray(castOrder)) rows.sort(function (a, b) { return castOrder.indexOf(a.castId) - castOrder.indexOf(b.castId); });
  return rows;
}

/** 分支弹窗里一条选项的信任行:每个角色一个 ±stepper,值为 0 的收起来(只留 + 号入口)。 */
function trustRows(cfg, trust) {
  var map = {};
  normalizeTrust(trust).forEach(function (r) { map[r.castId] = r.delta; });
  return ((cfg && cfg.cast) || []).map(function (m) {
    var delta = map[m.id] || 0;
    return { castId: m.id, name: str(m.name).trim() || '未命名角色', color: m.color, delta: delta, on: delta !== 0 };
  });
}

/** 编辑器里分支卡上的一行小字:「苏禾 +1 · 阿迟 −2」。没有就空。 */
function trustSummary(cfg, trust) {
  var names = speakerNameMap(cfg);
  return normalizeTrust(trust).filter(function (r) { return names[r.castId]; })
    .map(function (r) { return names[r.castId] + ' ' + (r.delta > 0 ? '+' + r.delta : '−' + Math.abs(r.delta)); }).join(' · ');
}

/* ===== 站点选择类玩法的信任(cast-game-trust-1119)===== */

/** 主题编辑器 → 玩法编辑器:角色开着才带 &cast=[{id,name}],没开 / 没有角色返回空串(玩法编辑器就不出信任行)。 */
function castQuery(cfg) {
  if (!isEnabled(cfg)) return '';
  var cast = ((cfg && cfg.cast) || []).filter(function (m) { return m && CAST_ID.test(str(m.id)); })
    .slice(0, MAX_CAST).map(function (m) { return { id: m.id, name: str(m.name).trim() }; });
  return cast.length ? '&cast=' + encodeURIComponent(JSON.stringify(cast)) : '';
}

/** 玩法编辑器读 &cast=:坏 JSON / 不合法的 id 一律丢掉,返回 [{id,name}]。 */
function parseCastQuery(raw) {
  var list;
  try { list = JSON.parse(typeof raw === 'string' ? raw : ''); } catch (e) { return []; }
  var seen = [];
  return (Array.isArray(list) ? list : []).filter(function (m) {
    if (!m || !CAST_ID.test(str(m.id)) || seen.indexOf(m.id) >= 0) return false;
    seen.push(m.id);
    return true;
  }).slice(0, MAX_CAST).map(function (m) { return { id: m.id, name: str(m.name).trim() }; });
}

/* ===== 线索 / 结局台词 ===== */

/** 线索解锁章节的选项:开局就有 + 已保存(有 id)的普通章节。 */
function clueChapterOptions(chapters) {
  var out = [{ value: null, label: '开局就有' }];
  var n = 0;
  (chapters || []).forEach(function (c) {
    if (!c || c.opening || c.ending) return;
    n += 1;
    if (c.id == null || c.id === '') return;
    out.push({ value: c.id, label: '第 ' + n + ' 章' + (str(c.name).trim() ? ' · ' + str(c.name).trim() : '') });
  });
  return out;
}

function clueChapterLabel(options, chapterId) {
  var hit = (options || []).find(function (o) { return String(o.value) === String(chapterId); });
  if (chapterId == null) return '开局就有';
  return hit ? hit.label : '章节已不在';
}

/* ===== 结局归属(endingFor)===== */

function endingForOptions(cfg) {
  return [{ value: '', label: '不归属任何角色' }].concat(((cfg && cfg.cast) || []).map(function (m) {
    return { value: m.id, label: str(m.name).trim() || '未命名角色' };
  }));
}

module.exports = {
  COLORS: COLORS, PRESETS: PRESETS, MAX_CAST: MAX_CAST, MAX_IDENTITIES: MAX_IDENTITIES, MAX_CLUES: MAX_CLUES,
  NAME_MAX: NAME_MAX, IDENTITY_MAX: IDENTITY_MAX, ID_TITLE_MAX: ID_TITLE_MAX, ID_SUB_MAX: ID_SUB_MAX,
  CLUE_MAX: CLUE_MAX, ENDING_LINE_MAX: ENDING_LINE_MAX, LINE_MAX: LINE_MAX, TRUST_MIN: TRUST_MIN, TRUST_MAX: TRUST_MAX,
  PLAYER: PLAYER, CAST_ID: CAST_ID, initialOf: initialOf,
  parseCastConfig: parseCastConfig, emptyCastConfig: emptyCastConfig, isEnabled: isEnabled,
  normalizeMember: normalizeMember, newMember: newMember, newIdentity: newIdentity, presetLabel: presetLabel,
  memberIssues: memberIssues, identityIssues: identityIssues, firstIssue: firstIssue, serializeCastConfig: serializeCastConfig,
  cardView: cardView, listView: listView, speakerOptions: speakerOptions, speakerName: speakerName, speakerNameMap: speakerNameMap,
  isLineType: isLineType, lineFields: lineFields, lineIssue: lineIssue,
  normalizeTrust: normalizeTrust, setTrust: setTrust, trustRows: trustRows, trustSummary: trustSummary,
  castQuery: castQuery, parseCastQuery: parseCastQuery,
  clueChapterOptions: clueChapterOptions, clueChapterLabel: clueChapterLabel, endingForOptions: endingForOptions,
};
