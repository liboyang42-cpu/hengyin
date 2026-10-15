const gameplay = require('./theme-gameplay.js');

function ended(view) {
  return view.claimOnly === true || view.status === 'COMPLETED' || !!view.result || view.naturallyCompleted === true;
}

function canClaim(view, source) {
  if (!view.pending || !view.pending[source]) return false;
  return view.scope === 'TEAM' ? (view.availableActions || []).includes('ITEM_RECEIVE')
    : (view.allowedActions || []).some(row => row.operation === 'RECEIVE' && row.contentId === source);
}

function itemName(id, view) {
  const pending = Object.values(view.pending || {}).find(row => row.itemId === id);
  return pending && pending.name || gameplay.valueName(id, view);
}

function sourceName(source) {
  const parts = String(source || '').split(':');
  if (parts[0] === 'chapter') return '第' + (Number(parts[1]) + 1) + '章' + (parts[2] === 'END' ? '完成奖励' : '开启奖励');
  return parts[0] === 'quest' ? '任务奖励' : parts[0] === 'event' ? '事件奖励' : parts[0] === 'facility' ? '工具奖励' : '动作奖励';
}

// 数量和装载结果均来自预览；只给当前归属提供放弃本次奖励的选择。
function preview(data, view) {
  const pack = view.scope === 'TEAM' ? data.settlement || data.result : data;
  if (!pack || typeof pack.previewVersion !== 'string' || !pack.previewVersion || typeof pack.overflow !== 'boolean') return null;
  const hasOwners = Array.isArray(pack.rewardOwners) && pack.rewardOwners.length > 0;
  const owners = hasOwners ? pack.rewardOwners : [pack];
  return {
    previewVersion: pack.previewVersion, overflow: pack.overflow,
    owners: owners.map(owner => {
      const own = !hasOwners || view.scope !== 'TEAM' || String(owner.ownerId) === String(view.teamId);
      const drops = owner.discardRewards || pack.discardRewards || [];
      return { ownerId: owner.ownerId, own, label: own ? view.scope === 'TEAM' ? '本队' : '本人' : '队伍 ' + owner.ownerId,
        finalUsed: owner.finalUsed, capacity: owner.capacity, overflow: owner.overflow,
        sources: (owner.rewardSources || []).map(row => ({ ...row, name: itemName(row.itemId, view), sourceLabel: sourceName(row.source) })),
        rewards: Object.entries(owner.grants || {}).map(([itemId, quantity]) => ({ itemId, quantity, name: itemName(itemId, view),
          discard: (drops.find(row => row.itemId === itemId) || {}).quantity || 0 })),
      };
    }),
  };
}

function selection(rows) {
  return rows.filter(row => row.discard > 0).map(row => ({ itemId: row.itemId || row.id, quantity: row.discard }));
}

module.exports = { ended, canClaim, itemName, sourceName, preview, selection };
