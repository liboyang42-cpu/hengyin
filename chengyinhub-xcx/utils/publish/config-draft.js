'use strict';

const { createDraftUuid } = require('./pro-editor-draft.js');
const TOKENS = ['--cy-color-bg-surface', '--cy-color-text-primary', '--cy-color-text-secondary', '--cy-color-border-subtle', '--cy-color-brand'];
const clone = value => JSON.parse(JSON.stringify(value));
const id = value => { if (!Number.isSafeInteger(Number(value)) || Number(value) < 1) throw new Error('配置编号不完整，请重试'); return Number(value); };
const text = value => value == null ? '' : String(value);
const pick = (row, keys) => keys.reduce((result, key) => { if (row[key] != null) result[key] = clone(row[key]); return result; }, {});
function sourceOf(raw) {
  const value = raw ? (typeof raw === 'string' ? JSON.parse(raw) : clone(raw)) : {};
  return value.configSource || null;
}
function toneTokens(raw) {
  const tokens = raw ? (typeof raw === 'string' ? JSON.parse(raw) : raw) : {};
  if (!tokens || typeof tokens !== 'object' || Array.isArray(tokens)) throw new Error('风格色值格式不正确');
  Object.keys(tokens).forEach(key => {
    if (!TOKENS.includes(key) || !/^#[0-9a-f]{6}$/i.test(tokens[key])) throw new Error('风格仅支持主题颜色，请先修正配置');
  });
  return clone(tokens);
}
function styleOf(raw) {
  try {
    const source = sourceOf(raw);
    const tokens = toneTokens(source && source.style && source.style.toneTokens);
    // 预览仍读 v1 别名，须在宿主重声明，避免继承已解析的旧色值。
    const aliases = { '--cy-color-text-primary': '--cy-text-title', '--cy-color-text-secondary': '--cy-text-secondary',
      '--cy-color-bg-surface': '--cy-comp-card-bg', '--cy-color-border-subtle': '--cy-comp-card-border' };
    return Object.keys(tokens).map(key => key + ':' + tokens[key] + (aliases[key] ? ';' + aliases[key] + ':' + tokens[key] : '')).join(';');
  } catch (error) { return ''; }
}
function snapshot(row, keys) {
  if (!row || typeof row !== 'object') throw new Error('配置内容不完整，请重试');
  return Object.assign(pick(row, keys), { id: id(row.id), updatedAt: text(row.updateTime || row.createTime) });
}
function buildConfigDraft(formData, raw, kind) {
  const next = clone(formData);
  const runtime = next.themeRuntimeConfig ? JSON.parse(next.themeRuntimeConfig) : { schemaVersion: 1, capacity: 6 };
  if (!runtime || typeof runtime !== 'object' || Array.isArray(runtime)) throw new Error('原草稿配置无法读取，内容已保留');
  const source = runtime.configSource || { schemaVersion: 1 };
  let selected;
  if (kind === 'route') {
    if ((next.chapters || []).some(ch => (ch.nodes || []).length || (ch.blocks || []).length || ch.description)) throw new Error('路线已有内容，请在空白新草稿选用路线');
    if (!raw || raw.status !== '1' || (raw.delFlag != null && Number(raw.delFlag) !== 0) || !Array.isArray(raw.nodes) || !raw.nodes.length || raw.nodes.length > 200) throw new Error('路线未公开或站点不完整');
    selected = snapshot(raw, ['name', 'city', 'previewImg', 'routeGeometry', 'defaultCategoryIds']);
    const seen = new Set();
    selected.nodes = raw.nodes.map(row => {
      const node = pick(row, ['name', 'address', 'longitude', 'latitude', 'imgUrl', 'nodeTime']);
      node.id = id(row.id);
      if (seen.has(node.id)) throw new Error('路线包含重复站点');
      seen.add(node.id);
      if (!text(node.name).trim() || !text(node.longitude).trim() || !text(node.latitude).trim()
          || !Number.isFinite(Number(node.longitude)) || Math.abs(Number(node.longitude)) > 180
          || !Number.isFinite(Number(node.latitude)) || Math.abs(Number(node.latitude)) > 90) throw new Error('路线地点或坐标不完整，请先修正配置');
      if (row.playTemplateId != null && Number(row.playTemplateId) !== 0) node.playTemplateId = id(row.playTemplateId);
      return node;
    });
    next.chapters = [{ _localId: 'chapter-' + createDraftUuid(), name: text(raw.name), description: '', atmospherePreset: 'DEFAULT',
      recruitEnabled: 0, termsMode: 'PERK', maxMerchant: 0, nodes: selected.nodes.map(row => {
        const node = pick(row, ['name', 'address', 'longitude', 'latitude', 'imgUrl', 'nodeTime']);
        node._localId = 'node-' + createDraftUuid();
        node.templateId = 0;
        if (row.playTemplateId) node.sourceLibraryTemplateId = row.playTemplateId;
        return node;
      }) }];
    if (!next.name) next.name = text(raw.name);
  } else if (kind === 'style') {
    if (!raw || raw.status !== '1' || (raw.delFlag != null && Number(raw.delFlag) !== 0)) throw new Error('风格未公开');
    selected = snapshot(raw, ['name', 'coverImg', 'defaultTitle', 'defaultSubtitle', 'defaultDesc', 'defaultCategoryIds']);
    selected.toneTokens = toneTokens(raw.toneTokens);
    if (!next.name) next.name = text(raw.defaultTitle);
    if (!next.subtitle) next.subtitle = text(raw.defaultSubtitle);
    if (!next.description) next.description = text(raw.defaultDesc);
    if (!next.imgUrl && /^https:\/\/[^\s]+$/.test(text(raw.coverImg))) next.imgUrl = raw.coverImg;
  } else if (kind === 'world') {
    const world = raw && raw.world;
    if (!world || world.status !== '0' || (world.deleted != null && world.deleted !== '0')) throw new Error('世界未公开');
    selected = snapshot(world, ['worldCode', 'subtitle', 'cityCode', 'coverUrl', 'intro']);
    selected.name = text(world.worldName);
    if (!Array.isArray(raw.chapters) || !Array.isArray(raw.quests) || raw.chapters.length > 100 || raw.quests.length > 200) throw new Error('世界来源内容不完整');
    selected.chapters = raw.chapters.map(row => Object.assign(pick(row, ['cityCode', 'coverUrl', 'intro']), { id: id(row.id), name: text(row.chapterName) }));
    selected.quests = raw.quests.map(row => Object.assign(pick(row, ['chapterId', 'questType', 'targetBizType', 'targetBizId', 'rewardExp']), { id: id(row.id), name: text(row.questName) }));
    if (!next.name) next.name = selected.name;
    if (!next.subtitle) next.subtitle = text(world.subtitle);
    if (!next.description) next.description = text(world.intro);
    if (!next.imgUrl && /^https:\/\/[^\s]+$/.test(text(world.coverUrl))) next.imgUrl = world.coverUrl;
  } else throw new Error('配置类型不支持');
  source[kind] = selected;
  if (JSON.stringify(source).length > 60000) throw new Error('配置来源内容过大');
  runtime.configSource = source;
  next.themeRuntimeConfig = JSON.stringify(runtime);
  return next;
}
module.exports = { buildConfigDraft, sourceOf, styleOf, toneTokens };
