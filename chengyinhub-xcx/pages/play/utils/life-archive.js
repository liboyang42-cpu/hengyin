/**
 * utils/life-archive.js —— 结局页「这一生的档案」(2026-09-24 用户:结局要回收这一局)
 *
 * 两个来源同一个形状:真玩家读服务端 /api/play/journey/ending 的 archive,
 * 作者试玩从各站的试玩 kit 里现拼。走过的地点两边都取本地 nodes(已完成且有坐标的站)。
 * 输出:{ identity:[{label,value}], avatarUrl, photos:[{url,title}], notes:[{title,text}], stations:[名字] }
 */

function stationsOf(nodes) {
  return (nodes || []).filter((n) => n && n.done && Number(n.lat) && Number(n.lng)).map((n) => String(n.name || ''));
}

function fromServer(archive, nodes) {
  const a = archive || {};
  return withWalk({
    identity: (a.identity || []).filter((r) => r && r.value),
    avatarUrl: a.avatarUrl || '',
    photos: (a.photos || []).filter((p) => p && p.url),
    notes: (a.notes || []).filter((n) => n && n.text),
    stations: stationsOf(nodes),
  });
}

function fromPreview(nodes) {
  const out = { identity: [], avatarUrl: '', photos: [], notes: [], stations: stationsOf(nodes) };
  (nodes || []).forEach((n) => {
    const kit = n && n.previewKit;
    if (!kit) return;
    if (kit.type === 'profile' && kit.done) {
      if (kit.avatarUrl && !out.avatarUrl) out.avatarUrl = kit.avatarUrl;
      (kit.questions || []).forEach((q) => {
        const raw = kit.answers && kit.answers[q.key];
        if (!raw) return;
        const opt = (q.options || []).find((o) => o.key === raw);
        out.identity.push({ label: q.label || q.key, value: opt ? (opt.label || raw) : raw });
      });
    }
    if (kit.type === 'photocheck' && kit.lastUrl) out.photos.push({ url: kit.lastUrl, title: kit.title || n.name || '' });
    if (kit.type === 'note' && kit.mine) out.notes.push({ title: kit.title || n.name || '', text: kit.mine });
  });
  return withWalk(out);
}

/** 档案里有没有东西可看(全空就不画这一块) */
function withWalk(a) {
  a.walkText = a.stations.join(' · ');
  return a;
}

function isEmpty(a) {
  return !a || !(a.identity.length || a.photos.length || a.notes.length || a.stations.length);
}

module.exports = { fromServer, fromPreview, isEmpty };
