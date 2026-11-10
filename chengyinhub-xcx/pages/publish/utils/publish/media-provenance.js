// 素材来源声明(r2-create,业主「放到模板配置里」):模板配置里作者给自己上传的素材声明来源。
// 写 PUT /api/media/asset/{id}/provenance;字段与上限同服务端 AppMediaProvenance.normalize。
// 「来源不明(unknown)」不给作者选 —— 选了等于没声明,公开时服务端照样会拦。

var TYPES = [
  { value: 'original', label: '原创' },
  { value: 'licensed', label: '已获授权' },
  { value: 'ai-generated', label: 'AI 生成' },
  { value: 'public-domain', label: '公有领域' },
];

function assetIdOf(raw) {
  var id = String(raw == null ? '' : raw);
  return /^[1-9][0-9]{0,18}$/.test(id) ? id : '';
}

function label(type) {
  for (var i = 0; i < TYPES.length; i++) if (TYPES[i].value === type) return TYPES[i].label;
  return '';
}

// 校验错误按字段写在弹层里(红字),不弹窗
function payload(form) {
  var f = form || {};
  if (!label(f.sourceType)) return { error: '请选择素材来源' };
  var attribution = String(f.attribution == null ? '' : f.attribution).trim();
  var licenseNote = String(f.licenseNote == null ? '' : f.licenseNote).trim();
  if (attribution.length > 255) return { error: '署名最多 255 字' };
  if (licenseNote.length > 2000) return { error: '授权说明最多 2000 字' };
  // 服务端公开校验(AppMediaProvenance.missing)要求授权说明非空,任何来源都一样
  if (!licenseNote) return { error: '请写明授权说明' };
  var data = { sourceType: f.sourceType };
  if (attribution) data.attribution = attribution;
  if (licenseNote) data.licenseNote = licenseNote;
  return { data: data };
}

module.exports = { TYPES: TYPES, assetIdOf: assetIdOf, label: label, payload: payload };
