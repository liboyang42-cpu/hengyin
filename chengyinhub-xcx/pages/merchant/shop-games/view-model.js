// S5 店铺小游戏(商家):把 POST /api/merchant/shop-games 的回包整理成页面用的形状。纯函数,单测友好。
'use strict';

function text(v, fallback) {
  return typeof v === 'string' && v.trim() ? v.trim() : (fallback || '');
}

function count(v, fallback) {
  return typeof v === 'number' && Number.isInteger(v) && v >= 0 ? v : fallback;
}

// 返回 null 表示回包不可用(页面显示加载失败)。
function shapeShopGames(data) {
  if (!data || typeof data !== 'object' || !Array.isArray(data.pool) || !data.kind) return null;
  const templates = Array.isArray(data.templates) ? data.templates : [];
  const current = Array.isArray(data.games) ? data.games : [];
  const chosen = {};
  current.forEach(g => { if (g && g.key) chosen[g.key] = g.templateId == null ? null : g.templateId; });
  const pool = data.pool.filter(g => g && g.key).map(g => {
    const options = templates.filter(t => t && t.game === g.key && t.id != null)
      .map(t => ({ id: t.id, title: text(t.title, '未命名内容') }));
    const selected = Object.prototype.hasOwnProperty.call(chosen, g.key);
    const templateId = selected ? chosen[g.key] : (options[0] ? options[0].id : null);
    const index = options.findIndex(o => o.id === templateId);
    return {
      key: g.key,
      name: text(g.nameZh, '小游戏'),
      seconds: count(g.seconds, 60),
      needs: g.needsMotion ? '需要走动' : (g.needsCamera ? '需要拍照' : '只需作答'),
      enabled: g.enabled !== false,
      selected,
      options,
      optionTitles: options.map(o => o.title),
      optionIndex: index < 0 ? 0 : index,
      templateId: index < 0 ? (options[0] ? options[0].id : null) : templateId,
      ready: index >= 0 || options.length > 0,
    };
  });
  return {
    kindName: text(data.kind.nameZh, '店铺'),
    allowance: Math.max(1, count(data.allowance, 1)),
    editable: data.editable === true,
    sourceText: data.source === 'OPERATOR' ? '平台已为你的店设好了小游戏' : (data.source === 'MERCHANT' ? '你选的小游戏' : '默认小游戏'),
    pool,
  };
}

function selectedCount(pool) {
  return (pool || []).filter(g => g.selected).length;
}

function saveBody(pool) {
  return { games: (pool || []).filter(g => g.selected).map(g => (g.templateId == null ? { key: g.key } : { key: g.key, templateId: g.templateId })) };
}

module.exports = { shapeShopGames, selectedCount, saveBody };
