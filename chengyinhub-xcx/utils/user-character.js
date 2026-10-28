'use strict';
const { exactSessionId } = require('./roam-recovery.js');
const exactId = value => typeof value === 'string' || typeof value === 'number' ? exactSessionId(value) : '';

const isRecord = value => !!value && typeof value === 'object' && !Array.isArray(value);
const integer = value => Number.isInteger(value) && value >= -2147483648 && value <= 2147483647;
const text = value => typeof value === 'string' ? value.trim() : '';
const responseCode = value => typeof value === 'number' && Number.isInteger(value)
  || typeof value === 'string' && /^[1-5][0-9]{2}$/.test(value) ? Number(value) : 0;

function reader(app) {
  return { id: exactId(app.getUserID && app.getUserID()),
    token: app.getAuthorization && app.getAuthorization() || '' };
}
function sameReader(app, captured) {
  const now = reader(app);
  return now.id === captured.id && now.token === captured.token;
}

// registration/info attaches cmsTopic (theme) or cmsActivity (activity with an optional source theme).
function registrationTopic(info, recordId) {
  const registrationId = exactId(recordId);
  if (!registrationId || !isRecord(info) || exactId(info.id) !== registrationId) return '';
  const owner = exactId(info.ownerId);
  let topic = '';
  if ([1, '1'].includes(info.ownerType)) {
    topic = isRecord(info.cmsTopic) ? exactId(info.cmsTopic.id) : '';
    if (!owner || owner !== topic) return '';
  } else if ([2, '2'].includes(info.ownerType)) {
    const activity = info.cmsActivity;
    if (!isRecord(activity) || !owner || exactId(activity.id) !== owner) return '';
    topic = exactId(activity.topicId);
  }
  if (!topic) return '';
  if (info.topicId != null && info.topicId !== 0 && info.topicId !== '0'
      && exactId(info.topicId) !== topic) return '';
  return topic;
}

// No theme relation = a plain activity (cmsActivity.topicId null/0) or another owner type without
// cmsTopic/topicId. Such registrations have no character/score entry at all, which is not an error.
const noId = value => value == null || value === 0 || value === '0';
function hasTopicRelation(info) {
  if (!isRecord(info) || [1, '1'].includes(info.ownerType) || info.cmsTopic != null || !noId(info.topicId)) return true;
  if (![2, '2'].includes(info.ownerType)) return false;
  return !isRecord(info.cmsActivity) || !noId(info.cmsActivity.topicId);
}

function attribute(raw, partial) {
  if (!isRecord(raw)) throw new Error('invalid attribute');
  if (raw.visible === false || raw.hidden === true) return null;
  if (!text(raw.stateKey) || !text(raw.label) || !integer(raw.value) || !integer(raw.min)
      || !integer(raw.max) || raw.min > raw.max || raw.value < raw.min || raw.value > raw.max
      || !(['number', 'bar', 'stars'].includes(raw.displayType) || partial && raw.displayType == null)
      || !(['run', 'topic'].includes(raw.persistScope) || partial && raw.persistScope == null)) throw new Error('invalid attribute');
  const levels = isRecord(raw.rules) && Array.isArray(raw.rules.levels) ? raw.rules.levels : [];
  let levelLabel = '';
  for (const level of levels) {
    if (isRecord(level) && integer(level.at) && raw.value >= level.at && text(level.label)) {
      levelLabel = text(level.label);
    }
  }
  // Only visible display fields enter setData; raw rules/variables/unknown metadata stay out.
  return { stateKey: raw.stateKey, label: text(raw.label), value: raw.value, min: raw.min, max: raw.max,
    displayType: raw.displayType || 'number', persistScope: raw.persistScope, levelLabel,
    // 仅消费已知展示元数据；旧局 partial 不以实时定义补齐。色值/图标不得成为 CSS 或 URL 输入。
    color: !partial && typeof raw.color === 'string' && /^#[0-9a-fA-F]{6}$/.test(raw.color) ? raw.color : '',
    icon: !partial && typeof raw.icon === 'string' && /^[a-z0-9][a-z0-9-]{0,31}$/.test(raw.icon) ? raw.icon : '',
    scopeText: raw.persistScope == null ? '保留范围未记录' : raw.persistScope === 'topic' ? '同主题跨局保留' : '每局重置',
    percent: raw.max === raw.min ? 100 : Math.round((raw.value - raw.min) * 100 / (raw.max - raw.min)),
    stars: raw.displayType === 'stars' && raw.min === 0 && raw.max > 0 && raw.max <= 10
      ? Array.from({ length: raw.max }, (_, index) => ({ index, filled: index < raw.value })) : [],
  };
}

function rpgView(raw) {
  if (raw == null || isRecord(raw) && raw.enabled === false) return null;
  if (!isRecord(raw) || raw.enabled !== true) throw new Error('invalid rpg');
  const result = { momentum: null, clocks: [], facets: [], aspects: [] };
  if (raw.momentum != null) {
    const row = raw.momentum;
    if (!isRecord(row) || !integer(row.value) || !integer(row.max)
        || row.value < 0 || row.value > row.max) throw new Error('invalid momentum');
    result.momentum = { value: row.value, max: row.max };
  }
  for (const name of ['clocks', 'facets', 'aspects']) {
    if (raw[name] == null) continue;
    if (!Array.isArray(raw[name])) throw new Error('invalid rpg rows');
    result[name] = raw[name].filter(row => isRecord(row) && row.visible !== false && row.hidden !== true).map(row => {
      if (!text(row.label)) throw new Error('invalid rpg label');
      if (name === 'clocks') {
        if (!text(row.var) || !integer(row.value) || !integer(row.segments)
            || row.segments <= 0 || row.value < 0 || row.value > row.segments) throw new Error('invalid clock');
        return { key: row.var, label: text(row.label), value: row.value, max: row.segments,
          percent: Math.round(row.value * 100 / row.segments) };
      }
      if (!text(row.key)) throw new Error('invalid aspect');
      return { key: row.key, label: text(row.label), desc: text(row.desc) };
    });
  }
  return result.momentum || result.clocks.length || result.facets.length || result.aspects.length ? result : null;
}

function projectCard(raw, topicId) {
  if (!isRecord(raw) || !exactId(topicId) || exactId(raw.topicId) !== exactId(topicId)
      || typeof raw.created !== 'boolean' || typeof raw.running !== 'boolean'
      || raw.running && !raw.created || !Array.isArray(raw.attributes)) return null;
  try {
    const partial = raw.definitionSource === 'SESSION_SNAPSHOT_PARTIAL';
    const attributes = raw.attributes.map(row => attribute(row, partial)).filter(Boolean);
    const definitionNote = partial ? '本局属性使用开始时的配置；部分展示信息未记录'
      : raw.definitionSource === 'SESSION_SNAPSHOT_V1' ? '属性定义已随本局冻结'
      : raw.definitionSource === 'LEGACY_LIVE' ? '旧局未保存完整配置，当前展示可能随主题更新'
      : raw.created ? '属性以当前已保存的记录为准' : '以下为角色模板的初始属性，尚未创建角色';
    if (new Set(attributes.map(row => row.stateKey)).size !== attributes.length) return null;
    return { topicId: exactId(raw.topicId), created: raw.created, running: raw.running,
      attributes, definitionNote, rpg: rpgView(raw.rpg) };
  } catch (error) { return null; }
}

function failure(response, httpStatus) {
  const status = responseCode(httpStatus), code = responseCode(response && response.code);
  if (response && response.reason === 'sessionChanged') return 'session-changed';
  if (status === 426 || code === 426) return 'upgrade';
  if (status === 401 || status === 2 || code === 401 || code === 2 || response && response.code === '2'
      || response && response.errorCode === 'CHARACTER_LOGIN_REQUIRED') return 'login';
  if (status === 403 || code === 403) return 'forbidden';
  if (status === 404 || code === 404
      || response && response.errorCode === 'CHARACTER_TOPIC_NOT_FOUND') return 'unavailable';
  if (status === 400 || code === 400) return 'missing-param';
  return 'error';
}

module.exports = { exactId, reader, sameReader, registrationTopic, hasTopicRelation, projectCard, failure, responseCode };
