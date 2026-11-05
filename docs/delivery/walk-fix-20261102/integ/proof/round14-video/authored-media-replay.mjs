// UI-only replay driver. Product values change only through rendered controls; queues are native-picker seams.
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';

const plan = [
  'Author 9008: 发布 → 城市定向 → 专业手动; create a new R14-VIDEO-prefixed theme through the editor; preserve R14-GAME rows.',
  'Fill required details, opening text/audio/video, one ordinary question station with a real result rule, ending text/audio/video, theme music and a free ticket.',
  'Pause at media/theme HOLD and give the coordinator only the owned review IDs; this lane performs no moderation.',
  'Save/reopen through UI; retain author-edit-detail media fields and theme ID; publish through UI after review.',
  'Player 990010 (login label 9010): open the authored topic, free signup through checkout, play the actual station to server-confirmed completion.',
  'Record native opening/ending frames and audio events, actual ending response and coordinator frozen-snapshot readback; continue to finish and reopen.',
  'Repeat native request-delay/error interception and four looks without altering completed session data; phone/headphone acceptance stays pending.',
];
if (process.argv.includes('--selfcheck')) {
  assert.equal(safe('https://example.test/clip.mp4?Signature=private'), 'https://example.test/clip.mp4');
  assert.deepEqual(safe({authorization:'private',phone:'private',chapterVideo:{token:4}}),
    {authorization:'[redacted]',phone:'[redacted]',chapterVideo:{token:4}});
  console.log('PASS: source gating precedes launch; evidence strips credentials/URL queries and retains numeric video generations');
  process.exit(0);
}
if (process.argv.includes('--plan')) {
  assert.equal(plan.length,7); assert.ok(plan.every(step=>typeof step==='string'&&step.length>20));
  console.log(JSON.stringify({status:'PREPARED_NOT_EXECUTED',plan},null,2));
  process.exit(0);
}
const source = process.env.R14_VIDEO_RUNTIME_READY || '';
assert.match(source,/^[0-9a-f]{40}$/,'Coordinator must signal the verified combined candidate before execution');
const identity=process.env.R14_VIDEO_IDENTITY;
assert.ok(['e2e-openid-9008','e2e-openid-9010'].includes(identity),'Only the coordinator-verified author/player login identities are allowed');
const expectedMember=process.env.R14_VIDEO_EXPECTED_MEMBER_ID || '';
assert.match(expectedMember,/^[1-9][0-9]{0,18}$/,'Coordinator must confirm the actual member ID; login labels do not allocate IDs');
const root=process.env.R14_RIG_HOME || '/private/tmp/cy-r14-rig-1105';
const output=process.env.R14_VIDEO_OUTPUT || path.join(root,'r14-video-replay');
fs.mkdirSync(output,{recursive:true});
process.env.RIG_WEB='http://127.0.0.1:18197'; process.env.RIG_SHOTS=output;
const {launch}=await import(pathToFileURL(path.join(root,'docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs')));
const w=await launch({path:'pages/template/index',identity,localOnly:true});
const user=await w.page.evaluate(()=>MPWEB.getApp().getUserID());
try {
  assert.equal(String(user),expectedMember,'Login must resolve to the independently confirmed member');
  if ((await w.route()).at(-1)==='pages/privacy/index') {
    await w.tap('我已阅读并同意'); await w.tap('同意并继续');
  }
}
catch(error) { await w.close(); throw error; }
const history=[],readbacks=[],pendingReadbacks=[];
w.page.on('response',response=>{
  if (!/\/api\/play\/journey\/ending|\/api\/topic\/author-edit-detail|\/api\/topic\/publish-draft/.test(response.url())) return;
  pendingReadbacks.push(response.json().then(body=>readbacks.push({url:response.url(),status:response.status(),body})).catch(()=>{}));
});
function safe(value,key='') {
  if (key==='token'&&typeof value==='number') return value;
  if (/authorization|token|openid|phone|mobile|password|secret/i.test(key)) return '[redacted]';
  if (Array.isArray(value)) return value.map(item=>safe(item));
  if (value&&typeof value==='object') return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,safe(v,k)]));
  if (typeof value==='string') return value.replace(/https?:\/\/[^\s"<>]+/g,url=>{try{const parsed=new URL(url);return parsed.origin+parsed.pathname;}catch{return '[url]';}});
  return value;
}
async function aria(label) {
  const point=await w.page.evaluate(label=>{
    let found;
    (function walk(root){for(const el of root.querySelectorAll('*')){if(el.shadowRoot)walk(el.shadowRoot);
      if(el.getAttribute('aria-label')===label){const r=el.getBoundingClientRect();const css=getComputedStyle(el);
        if(r.width&&r.height&&css.display!=='none'&&css.visibility!=='hidden'&&r.top<844&&r.bottom>0)found={x:r.x+r.width/2,y:r.y+r.height/2};}}})(document);
    return found;
  },label);
  assert.ok(point,'No visible accessible control: '+label);
  await w.page.mouse.click(point.x,point.y);
}
async function inspect() {
  return safe({source,identity,route:await w.route(),text:await w.text(),errors:w.errors(),
    page:await w.page.evaluate(()=>{const d=getCurrentPages().at(-1).data;return {topicId:d.topicId,editingTopicId:d.editingTopicId,
      name:d.formData?.name,chapters:d.formData?.chapters,chapter:d.chapter,chapterVideo:d.chapterVideo,chapterFull:d.chapterFull,
      chapterGate:d.chapterGate,audioLoading:d.audioLoading,audioPlaying:d.audioPlaying,audioError:d.audioError,
      allDone:d.allDone,showFinish:d.showFinish,submitError:d.submitError,chapterVideoError:d.chapterVideoError};}),
    media:await w.media()});
}
async function command(c) {
  switch(c.op) {
    case 'tap': return w.tap(c.text,c.options||{});
    case 'aria': return aria(c.label);
    case 'type': {
      const handle=await w.page.$('pierce/input[placeholder='+JSON.stringify(c.placeholder)+']')
        || await w.page.$('pierce/textarea[placeholder='+JSON.stringify(c.placeholder)+']');
      assert.ok(handle,'No visible input: '+c.placeholder); await handle.click({clickCount:3});
      await w.page.keyboard.press('Backspace'); await w.page.keyboard.sendCharacter(c.text); return;
    }
    case 'scroll': return w.scroll(c.px);
    case 'wait': assert.ok(c.ms<=10000); return new Promise(resolve=>setTimeout(resolve,c.ms));
    case 'back': return w.back();
    case 'open': assert.match(c.path,/^\/?pages\//); return w.open(c.path);
    case 'loc': return w.loc(c.lat,c.lng);
    case 'network': return w.mediaNetwork({kind:c.kind,mode:c.mode,delayMs:c.delayMs});
    case 'queueVideo': return w.page.evaluate(()=>{MPWEB.mediaQueue=[{tempFilePath:location.origin+'/build/media-fixtures/clip.mp4',
      thumbTempFilePath:location.origin+'/build/media-fixtures/poster.jpg',duration:2,size:21281,fileType:'video',width:160,height:120}];});
    case 'queueAudio': return w.page.evaluate(()=>{MPWEB.fileQueue=[{path:location.origin+'/build/media-fixtures/audio.mp3',name:'audio.mp3',size:16527,type:'file'}];});
    case 'shot': assert.match(c.name,/^[A-Za-z0-9_-]+$/); return w.shot(c.name);
    case 'inspect': return inspect();
    case 'endingTrace': await Promise.all(pendingReadbacks); return safe(readbacks);
    case 'save': {const state=await inspect();fs.writeFileSync(path.join(output,'checkpoint.json'),JSON.stringify({history,state},null,2)+'\n');return state;}
    case 'close': return 'CLOSED';
    default: throw new Error('Unsupported UI operation: '+c.op);
  }
}
console.log(JSON.stringify({status:'READY_UI',source,identity,plan}));
const lines=readline.createInterface({input:process.stdin,crlfDelay:Infinity});
try {
  for await(const line of lines){if(!line.trim())continue;try{const c=JSON.parse(line);const result=await command(c);
    history.push({at:new Date().toISOString(),command:c,result:safe(result)});console.log(JSON.stringify({ok:true,result:safe(result)}));if(c.op==='close')break;
  }catch(error){console.log(JSON.stringify({ok:false,error:error.message}));}}
} finally {await w.close();}
