var ruleSteps = require('../../../utils/rule-steps.js');
/**
 * 玩法详情页：画廊图列表 + 展示字段归一化
 * imgUrls 支持：JSON 数组、逗号/分号分隔字符串；后续后端多图字段可直接对接
 */
function parseImgUrls(raw) {
  if (!raw) return [];
  if (Array.isArray(raw)) {
    return raw.filter(function (u) { return u && String(u).trim(); });
  }
  var str = String(raw).trim();
  if (!str) return [];
  if (str.charAt(0) === '[') {
    try {
      var parsed = JSON.parse(str);
      if (Array.isArray(parsed)) {
        return parsed.filter(function (u) { return u && String(u).trim(); });
      }
    } catch (e) { /* fall through */ }
  }
  return str.split(/[,;]/).map(function (s) { return s.trim(); }).filter(Boolean);
}

const { gameTemplateCover } = require('../../../utils/template-display.js');
const { decodeDisplayFields } = require('../../../utils/display-text.js');

// 详情页按纯文本展示的作者文字(含规则 ruleInstructions)。服务端过滤会把 x<y 存成 x&lt;y,在归一化这一处解码。
var DISPLAY_TEXT_FIELDS = ['title', 'name', 'description', 'ruleInstructions', 'storyText', 'requiredMaterials',
  'questionName', 'questionA', 'questionB', 'questionC', 'questionD', 'usageLocation', 'reason'];

function buildGallery(info) {
  info = info || {};
  var seen = {};
  var list = [];

  function push(url) {
    if (!url || seen[url]) return;
    seen[url] = true;
    list.push(url);
  }

  parseImgUrls(info.imgUrls).forEach(push);
  push(info.imgUrl);
  push(info.storyImg);
  if (!list.length) push(gameTemplateCover(info));

  return list;
}

function joinCategories(list) {
  if (!list || !list.length) return '';
  return list.map(function (c) { return c.categoryName; }).filter(Boolean).join(' · ');
}

/** 双行横滑：每列上图下各一张 */
function buildGalleryColumns(urls) {
  urls = urls || [];
  var cols = [];
  for (var i = 0; i < urls.length; i += 2) {
    cols.push({ top: urls[i], bottom: urls[i + 1] || '' });
  }
  return cols;
}

function normalizeTemplateDetail(info) {
  info = decodeDisplayFields(info || {}, DISPLAY_TEXT_FIELDS);
  var gallery = buildGallery(info);
  return {
    info: info,
    gallery: gallery,
    galleryColumns: buildGalleryColumns(gallery),
    coverUrl: gallery[0] || info.imgUrl || '',
    categoryText: joinCategories(info.sysCategoryList),
    ratingText: info.rating ? String(info.rating) : '—'
  };
}

// 编辑器把规则按「一行一步」存进 ruleInstructions(pages/publish/temp 的 ruleSteps ↔ join('\n')),
// 详情页按同一规则拆回步骤,每步一个序号;空行不算一步。
// V-12:与编辑器共用 utils/rule-steps.js —— 一行里的「1. … 2. … 3. …」也拆成多步,运营备注不展示。
function splitRuleSteps(raw) {
  if (raw === null || raw === undefined) return [];
  return ruleSteps.splitRuleSteps(ruleSteps.stripOperatorNotes(String(raw).replace(/\r/g, ''))).filter(Boolean);
}

module.exports = {
  splitRuleSteps: splitRuleSteps,
  parseImgUrls: parseImgUrls,
  buildGallery: buildGallery,
  buildGalleryColumns: buildGalleryColumns,
  normalizeTemplateDetail: normalizeTemplateDetail
};
