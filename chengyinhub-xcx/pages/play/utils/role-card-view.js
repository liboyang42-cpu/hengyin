'use strict';
// F05 组队角色卡:GET /api/play/encounter 的 roleView → 卡片数据(纯函数)。
//
// ★ 角色分配全在服务端(队员次序取模 A/B/C/D;单人 / 自玩 / 只剩 1 个活跃队员 / 多队歧义 → SOLO 全量)。
//   这里只搬服务端给的视图,不推断、不补另一半。
// ★ 组队:抬头「你的角色：<roleLabel>」,正文是本角色那一份,底部「队友在看另一份」。
//   SOLO:全部视图合并成一张,每段带角色名小标题 —— 单人永远能看全,不被卡住。
// ★ 旧服务端的 roleMissing(没有视图)不画空卡:返回 null。

const TEAM_ROLES = ['A', 'B', 'C', 'D'];
const TEAM_FOOTER = '队友在看另一份';

const text = (value) => (typeof value === 'string' ? value.trim() : '');

function section(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const items = (Array.isArray(raw.items) ? raw.items : [])
    .map((item, k) => ({ k, label: text(item && item.label), text: text(item && item.text) }))
    .filter((item) => item.label || item.text);
  const out = { roleId: text(raw.roleId), label: text(raw.label), title: text(raw.title), body: text(raw.body), items };
  return out.title || out.body || items.length ? out : null;
}

/** roleView → { mode:'team'|'solo', heading, sections:[{k,label,title,body,items}], footer };没有可显示的视图返回 null。 */
function roleCardView(roleView) {
  if (!roleView || typeof roleView !== 'object') return null;
  const views = (Array.isArray(roleView.views) ? roleView.views : []).map(section).filter(Boolean);
  if (!views.length) return null;
  const role = text(roleView.role);
  if (role === 'SOLO') {
    return { mode: 'solo', heading: '', footer: '',
      sections: views.map((v, k) => Object.assign(v, { k, label: v.label || v.roleId })) };
  }
  if (TEAM_ROLES.indexOf(role) < 0) return null;
  const own = views.find((v) => v.roleId === role) || views[0];
  const label = text(roleView.roleLabel) || own.label || role;
  return { mode: 'team', heading: '你的角色：' + label, footer: TEAM_FOOTER,
    sections: [Object.assign(own, { k: 0, label: '' })] };
}

module.exports = { roleCardView, TEAM_ROLES, TEAM_FOOTER };
