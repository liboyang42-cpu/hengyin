// 「线索与物品」里的物品一组:物品在主题物品表(themeRuntimeConfig.items, kind=ITEM),
// 不在状态键注册表里。这里只给 topic-state-keys 准备名字和「用在」,并接住它的改名 / 添加 / 删除。
const toast = require('../../../utils/toast.js');

/** 物品被哪些配置用到:只给作者看位置,不给 id。 */
function itemUsedIn(c, id) {
  const out = [];
  const add = text => { if (!out.includes(text)) out.push(text); };
  (c.grants || []).forEach(g => { if (g.itemId === id) add(g.id === 'completion_material' ? '主题通关奖励' : '主题发放'); });
  (c.chapters || []).forEach(ch => {
    if ((ch.grants || []).some(g => g.itemId === id)) add(`第 ${Number(ch.index) + 1} 章发放`);
    (ch.eggs || []).forEach(egg => { if ((egg.rewards || []).some(r => r.itemId === id)) add(`第 ${Number(ch.index) + 1} 章彩蛋${egg.name ? '「' + egg.name + '」' : ''}`); });
  });
  const survival = c.gameplayRuntime && c.gameplayRuntime.survival || {};
  if ((survival.rations || []).some(r => r.itemId === id)) add('配给');
  return out;
}

const data = { keysItems: [], keysSheetOpen: false };

const methods = {
  _syncKeysItems(c) {
    const config = c || this._runtimeConfig();
    const keysItems = (config.items || []).filter(i => i.kind === 'ITEM')
      .map(i => ({ id: i.id, name: String(i.name || '').trim(), usedIn: itemUsedIn(config, i.id) }));
    this.setData({ keysItems });
  },
  onKeysSheet(e) { this.setData({ keysSheetOpen: !!(e.detail && e.detail.open) }); },
  onKeysItemChange(e) {
    if (this.data.editScope === 'WHITELIST') return;
    const { action, id, name } = e.detail || {};
    const c = this._runtimeConfig();
    if (action === 'add') {
      c.items.push({ id: 'i' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), kind: 'ITEM', name, image: '' });
    } else {
      const index = c.items.findIndex(i => i.id === id && i.kind === 'ITEM');
      if (index < 0) return;
      if (action === 'rename') c.items[index].name = name;
      else if (action === 'delete') {
        if (itemUsedIn(c, id).length) { toast('没有删除成功，请再试一次。'); return; }
        c.items.splice(index, 1);
      } else return;
    }
    this._saveRuntime(c);
    this._syncKeysItems(c);
  },
};

module.exports = { data, methods, itemUsedIn };
