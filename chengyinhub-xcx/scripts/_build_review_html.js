/*
 * 把 227 张截图拼成一页走查用的 HTML：每张图配路由、状态、矩阵备注和一个备注框。
 * 用户逐页写意见 > 我反复重拍猜问题。
 *
 *   node scripts/_build_review_html.js
 */
const fs = require('fs');
const path = require('path');
const { SHOTS } = require('./shot-matrix');

// 截图目录写死过一次(2026-08-05 那批的桌面路径),导致每换一批截图就得改源码。
// 现在用 SHOTS_ROOT 覆盖;不传仍是原默认值,老用法零变化。
//   SHOTS_ROOT=~/Downloads/chengyin-shots/chengyin-reshoot-0809-clean node scripts/_build_review_html.js
const ROOT = process.env.SHOTS_ROOT || '/Users/arlan/Desktop/城瘾全量截图_20260805';
const OUT = path.join(ROOT, 'UI走查.html');
const byFile = new Map(SHOTS.filter((s) => !s.blocked).map((s) => [s.file, s]));

const cards = [];
for (const sub of ['玩家版', '商家版']) {
  const dir = path.join(ROOT, sub);
  if (!fs.existsSync(dir)) continue;
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.png')).sort()) {
    const m = byFile.get(f) || {};
    cards.push({
      sub, file: f, src: `${encodeURIComponent(sub)}/${encodeURIComponent(f)}`,
      id: m.id || f.split('-')[0], route: m.route || '', state: m.state || '',
      note: (m.note || '').replace(/[<>]/g, ''), pri: m.priority || '',
    });
  }
}

const html = `<meta charset="utf-8"><title>城瘾 UI 走查 · ${cards.length} 页</title>
<style>
:root{color-scheme:light dark}
body{margin:0;padding:16px;font:14px/1.6 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif;background:#f6f6f7;color:#111}
@media(prefers-color-scheme:dark){body{background:#111;color:#eee}.card{background:#1c1c1e!important;border-color:#2c2c2e!important}textarea{background:#2c2c2e!important;color:#eee!important;border-color:#3a3a3c!important}.meta{color:#98989d!important}}
h1{font-size:20px;margin:0 0 4px}
.bar{position:sticky;top:0;z-index:9;background:inherit;padding:10px 0 12px;border-bottom:1px solid #ddd;margin-bottom:16px}
.bar input,.bar select{padding:6px 10px;border:1px solid #ccc;border-radius:8px;font-size:13px;margin-right:8px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:16px}
.card{background:#fff;border:1px solid #e5e5e7;border-radius:12px;overflow:hidden;display:flex;flex-direction:column}
.card img{width:100%;display:block;background:#000;cursor:zoom-in}
.card .body{padding:10px 12px 12px}
.id{font-weight:700}
.meta{font-size:12px;color:#666;word-break:break-all;margin:2px 0}
textarea{width:100%;box-sizing:border-box;margin-top:8px;min-height:52px;padding:6px 8px;border:1px solid #ddd;border-radius:8px;font:13px/1.5 inherit;resize:vertical}
.tag{display:inline-block;font-size:11px;padding:1px 6px;border-radius:999px;background:#eee;margin-left:6px}
#zoom{position:fixed;inset:0;background:rgba(0,0,0,.9);display:none;align-items:center;justify-content:center;z-index:99;cursor:zoom-out}
#zoom img{max-width:96vw;max-height:96vh}
#out{position:fixed;right:16px;bottom:16px;z-index:20;padding:10px 16px;border:0;border-radius:999px;background:#111;color:#fff;font-size:14px;cursor:pointer}
@media(prefers-color-scheme:dark){#out{background:#eee;color:#111}}
</style>
<h1>城瘾 UI 走查 · ${cards.length} 页</h1>
<div class="bar">
  <input id="q" placeholder="搜路由 / ID / 备注…" size="30">
  <select id="f"><option value="">全部</option><option>玩家版</option><option>商家版</option></select>
  <span class="meta" id="cnt"></span>
  <span class="meta">　填完点右下角「导出意见」</span>
</div>
<div class="grid" id="g">
${cards.map((c, i) => `<div class="card" data-k="${(c.id + ' ' + c.route + ' ' + c.note + ' ' + c.sub).replace(/"/g, '')}" data-sub="${c.sub}">
  <img loading="lazy" src="${c.src}" alt="${c.id}">
  <div class="body">
    <div><span class="id">${c.id}</span><span class="tag">${c.sub}</span>${c.state ? `<span class="tag">${c.state}</span>` : ''}${c.pri ? `<span class="tag">${c.pri}</span>` : ''}</div>
    <div class="meta">${c.route}</div>
    ${c.note ? `<div class="meta">备注：${c.note.slice(0, 120)}</div>` : ''}
    <textarea data-i="${i}" data-id="${c.id}" data-route="${c.route}" placeholder="这页有什么问题？"></textarea>
  </div>
</div>`).join('\n')}
</div>
<div id="zoom"><img></div>
<button id="out">导出意见</button>
<script>
const g=document.getElementById('g'),q=document.getElementById('q'),f=document.getElementById('f'),cnt=document.getElementById('cnt');
function flt(){const s=q.value.trim().toLowerCase(),sub=f.value;let n=0;
 for(const c of g.children){const ok=(!s||c.dataset.k.toLowerCase().includes(s))&&(!sub||c.dataset.sub===sub);c.style.display=ok?'':'none';if(ok)n++}
 cnt.textContent=n+' / '+g.children.length}
q.oninput=flt;f.onchange=flt;flt();
const z=document.getElementById('zoom');
g.onclick=e=>{if(e.target.tagName==='IMG'){z.querySelector('img').src=e.target.src;z.style.display='flex'}};
z.onclick=()=>z.style.display='none';
// 输入实时存 localStorage，刷新不丢
for(const t of document.querySelectorAll('textarea')){
  const k='ui-review-'+t.dataset.id;t.value=localStorage.getItem(k)||'';
  t.oninput=()=>localStorage.setItem(k,t.value);
}
document.getElementById('out').onclick=()=>{
  const rows=[...document.querySelectorAll('textarea')].filter(t=>t.value.trim())
    .map(t=>'## '+t.dataset.id+'  '+t.dataset.route+'\\n'+t.value.trim());
  if(!rows.length){alert('还没填任何意见');return}
  const blob=new Blob(['# 城瘾 UI 走查意见（'+rows.length+' 页）\\n\\n'+rows.join('\\n\\n')],{type:'text/markdown'});
  const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='UI走查意见.md';a.click();
};
</script>`;
fs.writeFileSync(OUT, html);
console.log(`${cards.length} 张 → ${OUT}`);
