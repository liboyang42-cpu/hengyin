// 商家「行业类型」:入驻申请与门店信息共用一份胶囊短表(M-16,2026-11-02 主人:标签可以更多一些)。
// label 是胶囊上显示的短名,match 用来在 /api/category/list(type=1)里认领一条真实记录(存它的 id)。
// 更具体的词放前面:一条记录只被第一个命中的短表项认领。
const { tagIconName } = require('../components/cy/tag-sheet/model.js');

const INDUSTRY_OPTIONS = [
  { label: '咖啡茶饮', match: /咖啡|饮品|奶茶/ },
  { label: '茶馆', match: /茶馆|茶室/ },
  { label: '书店', match: /书店|书房|阅读/ },
  { label: '画廊展览', match: /画廊|艺术|展/ },
  { label: '手作工坊', match: /手作|工坊|手工/ },
  { label: '古着中古', match: /古着|中古|二手/ },
  { label: '文创杂货', match: /文创|杂货|买手/ },
  { label: '花店', match: /花店|花艺/ },
  { label: '烘焙甜品', match: /烘焙|甜品|蛋糕/ },
  { label: '餐饮美食', match: /美食|餐饮|烹饪|餐厅|食堂/ },
  { label: '酒馆小酌', match: /酒馆|酒吧|小酌/ },
  { label: '宠物友好', match: /宠物/ },
  { label: '运动健身', match: /运动|健身|体育/ },
  { label: '户外出行', match: /户外|徒步|骑行|出行|旅行/ },
  { label: '生活美学', match: /生活|美学|美妆|时尚/ },
  { label: '娱乐休闲', match: /娱乐|游戏|音乐|演出|电影|休闲/ },
  { label: '亲子社交', match: /亲子|社交|交友|组队/ },
  { label: '摄影', match: /摄影|相机/ },
];
const MAX_INDUSTRIES = 5;

/**
 * 常见行业短表 → 胶囊选项。每个短表项认领一条名字匹配的真实记录;没匹配上的短表项不出现。
 * 已选中但不在短表里的记录照常列出(用其原名),老数据保持可见可取消。
 * 一个都匹配不上(分类表全是陌生名字)时退回前 10 条真实记录,别给商家一张空面板。
 */
function buildIndustryOptions(rows, selectedIds) {
  const list = Array.isArray(rows) ? rows.filter(r => r && r.id != null && r.categoryName) : [];
  const selected = (selectedIds || []).map(String);
  const used = {};
  const out = [];
  INDUSTRY_OPTIONS.forEach(def => {
    const hit = list.filter(r => !used[r.id] && def.match.test(String(r.categoryName)))[0];
    if (!hit) return;
    used[hit.id] = true;
    out.push({ key: String(hit.id), label: def.label, name: String(hit.categoryName), icon: tagIconName(def.label) });
  });
  if (!out.length) {
    list.slice(0, 10).forEach(r => {
      used[r.id] = true;
      out.push({ key: String(r.id), label: String(r.categoryName), name: String(r.categoryName), icon: tagIconName(r.categoryName) });
    });
  }
  selected.forEach(id => {
    if (out.some(o => o.key === id)) return;
    const row = list.filter(r => String(r.id) === id)[0];
    if (row) out.push({ key: String(row.id), label: String(row.categoryName), name: String(row.categoryName), icon: tagIconName(row.categoryName) });
  });
  return out.map(o => Object.assign(o, { checked: selected.indexOf(o.key) >= 0 }));
}

module.exports = { INDUSTRY_OPTIONS, MAX_INDUSTRIES, buildIndustryOptions };
