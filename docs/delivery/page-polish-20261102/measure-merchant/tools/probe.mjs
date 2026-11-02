// node probe.mjs <path> <js-expression-returning-json>
const { launch } = await import(process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const [p, js, pre] = process.argv.slice(2);
const w = await launch({ path: p, identity: 'e2e-openid-9002', agree: true, settle: 3500 });
for (const s of JSON.parse(pre || '[]')) { const [k, v] = Object.entries(s)[0]; if (k === 'tap') await w.tap(v); if (k === 'wait') await new Promise(r => setTimeout(r, v)); }
const helpers = `
function all(sel){const out=[];(function walk(r){for(const el of r.querySelectorAll('*')){if(el.shadowRoot)walk(el.shadowRoot);if(el.matches(sel))out.push(el);}})(document);return out;}
function R(el){const r=el.getBoundingClientRect();const cs=getComputedStyle(el);return {cls:el.className,l:+r.left.toFixed(1),r:+(390-r.right).toFixed(1),t:+r.top.toFixed(1),b:+r.bottom.toFixed(1),w:+r.width.toFixed(1),h:+r.height.toFixed(1),pad:cs.padding,mar:cs.margin,rad:cs.borderRadius};}
function textRect(el){const rg=document.createRange();rg.selectNodeContents(el);const r=rg.getBoundingClientRect();return {l:+r.left.toFixed(1),t:+r.top.toFixed(1),b:+r.bottom.toFixed(1),r:+(390-r.right).toFixed(1)};}
`;
console.log(JSON.stringify(await w.page.evaluate(helpers + 'JSON.stringify(' + js + ')'), null, 0));
await w.close();
