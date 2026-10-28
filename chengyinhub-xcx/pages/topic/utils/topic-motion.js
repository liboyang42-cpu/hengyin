/* 主题详情页动效与新增块的数据整形(2026-10-22 业主口述落地,见 docs/design/动效界面落地_20261022)。
   全部是纯函数:只读后端已下发的字段,缺字段就返回空,由 wxml 整块不出,不编造任何数据。 */

function text(value) {
  if (value === null || value === undefined) return '';
  const out = String(value).trim();
  return out === 'null' ? '' : out;
}

function csv(value) {
  return text(value).split(/[,;]/).map((s) => s.trim()).filter(Boolean);
}

/* 封面轮换图:多图 imgArr 优先,没有就用单张封面;最多 9 张 */
function buildCoverImages(info) {
  const list = csv(info && info.imgArr);
  if (list.length) return list.slice(0, 9);
  const one = text(info && info.imgUrl);
  return one ? [one] : [];
}

/* 发布者:内容主体公开名优先(俱乐部/个人/平台,后端已脱敏),没有再退到承接俱乐部名。
   头像只有「个人发布」时用发布人头像;俱乐部没有 logo 字段,交给 cy-avatar 用名字首字。 */
function buildPublisher(info) {
  const d = info || {};
  const name = text(d.contentPublisherName) || text(d.clubName);
  if (!name) return { name: '', avatar: '' };
  const member = d.umsMember && typeof d.umsMember === 'object' ? d.umsMember : null;
  const avatar = text(d.contentPublisherType) === 'MEMBER' && member ? text(member.avatar) : '';
  return { name, avatar };
}

/* 顶部那颗主按钮与底栏同口径:已购去票夹;未开售/打烊不放(底栏讲原因);自玩卖通行证;其余报名。 */
function buildHeroCta(info, opts) {
  const d = info || {};
  const o = opts || {};
  if (Number(o.isJoin) === 1) return { kind: 'joined', text: '去票夹' };
  const lc = Number(d.lifecycle);
  if (lc === 1 || lc === 2 || lc === 7) return { kind: 'locked', text: lc === 7 ? '立项待审 · 未开售' : (lc === 1 ? '招募中 · 未开售' : '定价中 · 未开售') };
  if (o.merchantClosed) return { kind: '', text: '' };
  if (Number(d.selfPlay) === 1) return { kind: 'selfplay', text: (o.priceText || '') + ' 随时开玩' };
  return { kind: 'signup', text: '立即报名' };
}

function firstNodeImage(chapter) {
  const nodes = (chapter && chapter.nodes) || [];
  for (const n of nodes) {
    const t = n && n.cmsMemberTemplate;
    const img = (t && text(t.imgUrl)) || csv(n && n.imgUrl)[0] || '';
    if (img) return img;
    const shop = (n && n.registrationMerchantList) || [];
    if (shop[0] && csv(shop[0].picUrl)[0]) return csv(shop[0].picUrl)[0];
  }
  return '';
}

const CN = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十'];
function chapterLabel(i, n) {
  if (i === n - 1 && n > 2) return '终章';
  return '第' + (CN[i] || String(i + 1)) + '章';
}

/* 故事卡:每章一张。自由探索没有章节顺序,用商家当「护照页」。 */
function buildStoryCards(info, merchantCards) {
  const d = info || {};
  if (Number(d.productType) === 2) {
    return (merchantCards || []).slice(0, 7).map((m, i) => ({
      img: m.img, label: '第' + (i + 1) + '页', title: m.name, excerpt: m.desc, meta: m.place,
    }));
  }
  const chapters = d.chaptersList || [];
  return chapters.map((c, i) => ({
    img: firstNodeImage(c),
    label: chapterLabel(i, chapters.length),
    title: text(c && c.name),
    excerpt: text(c && c.description),
    meta: [text(c && c.metaTime), text(c && c.metaPlace)].filter(Boolean).join(' · '),
  })).filter((c) => c.title);
}

function storyMeta(info) {
  const d = info || {};
  const parts = [];
  const chapters = (d.chaptersList || []).length;
  if (Number(d.productType) === 2) {
    const shops = Number(d.registrationMerchantCount) || 0;
    if (shops > 0) parts.push(shops + ' 家商家');
    parts.push('任意顺序');
  } else if (chapters) parts.push(chapters + ' 章');
  if (text(d.totalTimeFormatted)) parts.push('约 ' + text(d.totalTimeFormatted));
  const km = Number(d.totalMileage);
  if (km > 0) parts.push(km + ' 公里');
  return parts.join(' · ');
}

/* 参与商家卡:各站点报名商家去重;图取商家封面,其次报名图;介绍取一句话 slogan,其次简介 */
function buildMerchantCards(chapters) {
  const out = [];
  const seen = {};
  for (const c of chapters || []) {
    for (const n of (c && c.nodes) || []) {
      for (const row of (n && n.registrationMerchantList) || []) {
        const shop = (row && row.mmsMerchant) || {};
        const name = text(shop.name);
        if (!name) continue;
        const key = String(shop.id || name);
        if (seen[key]) continue;
        seen[key] = 1;
        out.push({
          key: 'm-' + key,
          targetId: row.memberId || 0,
          name,
          img: text(shop.coverImage) || csv(row.picUrl)[0] || text(shop.logo),
          desc: text(shop.slogan) || text(shop.description),
          gift: text(shop.derivativeBenefits),
          place: text(n && n.name),
        });
      }
    }
  }
  return out;
}

/* 路线说明(v13 rnote):城市定向写全程几站、步行多远、几站有到店礼;自由探索写任意顺序去完几家。只用已下发的数。 */
function routeNote(info, merchantCount) {
  const d = info || {};
  const nodes = [];
  (d.chaptersList || []).forEach((c) => ((c && c.nodes) || []).forEach((n) => nodes.push(n)));
  if (Number(d.productType) === 2) {
    const shops = Number(merchantCount) || Number(d.registrationMerchantCount) || 0;
    return '有效期内按任意顺序去完' + (shops ? ' ' + shops + ' 家' : '') + '，每家核销一次。';
  }
  const parts = ['按顺序到站，到一站解锁下一站'];
  if (nodes.length) parts.push('全程 ' + nodes.length + ' 站');
  const km = Number(d.totalMileage);
  if (km > 0) parts.push('步行约 ' + km + ' 公里');
  const gifts = nodes.filter((n) => { const m = ((n && n.registrationMerchantList) || [])[0]; return m && m.mmsMerchant && text(m.mmsMerchant.derivativeBenefits); }).length;
  if (gifts) parts.push('其中 ' + gifts + ' 站有到店礼');
  return parts.join('，') + '。';
}

/* 购买半屏的三颗小牌(v13):可玩至几号 / 章节与时长(或商家数)/ 玩法说明。只用已下发的数。 */
function buyChips(info, meta) {
  const d = info || {};
  const win = passWindow({ topicEnd: d.endDate });
  const out = ['可玩至 ' + win.untilText];
  if (text(meta)) out.push(text(meta).split(' · ').slice(0, 2).join(' · '));
  out.push(Number(d.productType) === 2 ? '任意顺序' : '可分几天玩完');
  return out;
}

/* 购买半屏的票种(v13 原型的「单人 / 双人 / 四人队」那一排):名字一律取发布者填的票种名称,不自拟。
   - 自玩通行证(selfPlay=1):随时开玩、不选场次,单价 selfPlayPrice;
   - 发布者配置的票种(omsTicketList):绑在场次上,同名合并,价格取最低并标「起」;人数取 teamSize / maxPeople。
   没有可买的场次时票种不列(买不了的不摆出来)。 */
function buyOptions(info) {
  const d = info || {};
  const out = [];
  const price = (v) => (v === undefined || v === null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v));
  if (Number(d.selfPlay) === 1 && price(d.selfPlayPrice) !== null) out.push({ key: 'pass', kind: 'pass', name: '自玩通行证', price: price(d.selfPlayPrice), people: 1 });
  if ((d.activityList || []).length) {
    const byName = {};
    (d.omsTicketList || []).forEach((t) => {
      const name = text(t && t.name);
      if (!name) return;
      const p = price(t.price);
      const people = Math.max(1, Number(t.teamSize) || Number(t.maxPeople) || 1);
      const hit = byName[name];
      if (hit) { if (p !== null && (hit.price === null || p !== hit.price)) { hit.from = true; if (hit.price === null || p < hit.price) hit.price = p; } return; }
      byName[name] = { key: 't-' + name, kind: 'ticket', name, price: p, people, from: false };
      out.push(byName[name]);
    });
  }
  return out.map((o) => Object.assign(o, {
    icon: o.people >= 3 ? 'team' : o.people === 2 ? 'two' : 'one',
    priceText: o.price === null ? '' : '¥' + o.price + (o.from ? ' 起' : ''),
  }));
}

/* 主要角色:各商家站点的出场 NPC(/api/ai/npc/profile scope=node 只回已过审的),圆头像 + 名字 + 所在站点 */
function buildChars(list) {
  const out = [];
  const seen = {};
  (list || []).forEach((row) => {
    const npc = row && row.npc;
    const name = text(npc && npc.name);
    if (!name || seen[name]) return;
    seen[name] = 1;
    out.push({ key: 'c-' + (npc.profileId || name), name, avatar: text(npc.avatar), sub: text(row.place) });
  });
  return out.slice(0, 8);
}

function pad2(n) { return (n < 10 ? '0' : '') + n; }
function toDate(value) {
  if (!value) return null;
  if (value instanceof Date) return isNaN(value.getTime()) ? null : value;
  const d = new Date(String(value).replace(/-/g, '/'));
  return isNaN(d.getTime()) ? null : d;
}

/* 可玩期限(业主:天数按主题时长算,不写死 90 天)。
   有通行证 expiresAt 就用它(最准);没有就取「购买后 90 天(后端 CmsRegistrationServiceImpl 的规则)」与主题结束日的较早者。 */
function passWindow(opts) {
  const o = opts || {};
  const now = toDate(o.now) || new Date();
  let until = toDate(o.expiresAt);
  let endsWithTopic = false;
  if (!until) {
    const ninety = new Date(now.getTime() + 90 * 86400000);
    const end = toDate(o.topicEnd);
    if (end) { const endOfDay = new Date(end.getFullYear(), end.getMonth(), end.getDate(), 23, 59, 59); if (endOfDay < ninety) { until = endOfDay; endsWithTopic = true; } }
    if (!until) until = ninety;
  }
  const days = Math.max(0, Math.ceil((until.getTime() - now.getTime()) / 86400000));
  const m = until.getMonth() + 1, dd = until.getDate();
  return {
    untilText: pad2(m) + '/' + pad2(dd),
    daysText: String(days),
    line: '可玩至 ' + m + ' 月 ' + dd + ' 日' + (endsWithTopic ? '（主题结束）' : '') + ' · 还剩 ' + days + ' 天',
  };
}

/* 组队块:附近队伍按 topicId 过滤;「还差几人」= 上限 − 已加入,只算有上限的队 */
function buildTeams(rows, topicId) {
  const list = Array.isArray(rows) ? rows : [];
  const mine = list.filter((r) => r && String(r.topicId) === String(topicId));
  let need = 0;
  const out = mine.slice(0, 5).map((r) => {
    const joined = Math.max(0, Number(r.joinedCount) || 0);
    const cap = Math.max(0, Number(r.maxMembers) || 0);
    const missing = cap > joined ? cap - joined : 0;
    need += missing;
    const faces = csv(r.memberAvatarsJoined).slice(0, 3);
    if (!faces.length && text(r.leaderAvatar)) faces.push(text(r.leaderAvatar));
    const when = toDate(r.startDate);
    const sub = [when ? (when.getMonth() + 1) + '/' + when.getDate() + ' ' + pad2(when.getHours()) + ':' + pad2(when.getMinutes()) : '', text(r.addressName)].filter(Boolean).join(' · ');
    return { teamId: r.teamId, activityId: r.activityId || null, when: when ? (when.getMonth() + 1) + '月' + when.getDate() + '日 ' + pad2(when.getHours()) + ':' + pad2(when.getMinutes()) : '', title: text(r.title) || text(r.activityName) || '主题队伍', sub, count: cap ? joined + '/' + cap : String(joined), missing, faces };
  });
  return { rows: out, need };
}

module.exports = { buyOptions, buyChips, routeNote, buildChars, chapterLabel, buildCoverImages, buildPublisher, buildHeroCta, buildStoryCards, storyMeta, buildMerchantCards, passWindow, buildTeams };
