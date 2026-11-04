import { run, S } from './lib.mjs';
import { call } from './f2.mjs';
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-m2', agree: true }, async (w) => {
  await S(3000); await call(w, 'bmClick'); await S(2000);
  const r = await w.eval(`(()=>{let target=null;function walk(root){for(const el of root.querySelectorAll('*')){if(el.shadowRoot)walk(el.shadowRoot);let own='';for(const n of el.childNodes)if(n.nodeType===3)own+=n.data;if(own.includes('下一步 · 去结算'))target=el;}}walk(document);if(!target)return 'none';const b=target.getBoundingClientRect();const x=b.x+b.width/2,y=b.y+b.height/2;let t=document.elementFromPoint(x,y);const chain=[];while(t){chain.push((t.tagName||'')+'.'+(t.className||'').toString().slice(0,40));if(!t.shadowRoot)break;const n=t.shadowRoot.elementFromPoint(x,y);if(!n||n===t)break;t=n;}return {b:[x,y],chain, target: target.tagName+'.'+target.className, parentStyle: getComputedStyle(target).pointerEvents};})()`);
  console.log('PROBE', JSON.stringify(r));
});
