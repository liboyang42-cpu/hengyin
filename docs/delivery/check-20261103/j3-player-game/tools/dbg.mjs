import { run, S } from './lib.mjs';
await run({ path: 'pages/topic/index/index?id=990015', identity: 'e2e-openid-9004', agree: true }, async (w) => {
  await S(3000);
  console.log('hit', await w.eval(`(()=>{let e=document.elementFromPoint(195,722);const out=[];let d=0;while(e&&d<6){ if(e.shadowRoot){const i=e.shadowRoot.elementFromPoint(195,722); if(i&&i!==e){e=i;}} out.push((e.tagName||'')+'.'+(e.className&&e.className.baseVal===undefined?e.className:'')+'#'+(e.id||'')); if(!e.shadowRoot) break; d++;} return out.join(' > ')})()`));
  console.log('stack', await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];const root=p.__root;const all=[];(function walk(r){for(const el of r.querySelectorAll('*')){if(el.shadowRoot)walk(el.shadowRoot);const b=el.getBoundingClientRect();if(b.left<=195&&b.right>=195&&b.top<=722&&b.bottom>=722){const cs=getComputedStyle(el);all.push(el.tagName+'.'+(typeof el.className==='string'?el.className.slice(0,40):'')+' z='+cs.zIndex+' pe='+cs.pointerEvents+' pos='+cs.position)}}})(root);return all.slice(-25).join('\\n')})()`));
});
