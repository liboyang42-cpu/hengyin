const S = (ms) => new Promise(r => setTimeout(r, ms));
export default async function (w) {
  await S(1500); await w.tap('开始申请'); await S(1200);
  console.log(await w.page.evaluate(() => { const o=[]; (function walk(r){ for (const e of r.querySelectorAll('*')) { if (e.shadowRoot) walk(e.shadowRoot); if (/^(INPUT|TEXTAREA|WX-INPUT|INPUT-)/i.test(e.tagName) || e.tagName.toLowerCase().includes('input')) o.push(e.tagName+' '+[...e.attributes].map(a=>a.name+'='+a.value).join(' ').slice(0,200)); } })(document); return o.join('\n'); }));
}
