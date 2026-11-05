// Harness-only synthetic media; no product data, backend requests or authoring acceptance.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import puppeteer from './mpweb/node_modules/puppeteer-core/lib/esm/puppeteer/puppeteer-core.js';

const root = path.dirname(new URL(import.meta.url).pathname);
const sources = path.join(root, 'docs/delivery/cloud-walk-20261101/rig/mpweb/src');
const assets = path.join(root, 'media-selfcheck-assets');
const checks = [], requests = [], uploads = [];
const html = `<!doctype html><div id="pages"></div><script>
window.__F=[s=>s.src,s=>s.token]; window.__T={'probe.wxml':[{g:'video',i:'v',p:{id:'probe-video',src:{f:0},'data-token':{f:1},autoplay:true,controls:false,bindplay:'record',bindtimeupdate:'record',bindwaiting:'record',bindended:'record',binderror:'record',style:'width:160px;height:120px'},c:[]}]};
window.__M={'probe.js':function(module,exports,require,Component,Page){Page({data:{src:location.origin+'/clip.mp4',token:1},events:[],record(e){this.events.push({type:e.type,time:e.detail.currentTime,token:e.currentTarget.dataset.token});}})}};
window.__C={probe:{page:true}}; window.__CSS={}; window.__APPCSS='';
</script><script src="/runtime.js"></script><script src="/nav.js"></script><script>
window.probe=MPWEB.openPage('probe',{},document.getElementById('pages'));
</script>`;
const server = http.createServer((req, res) => {
  requests.push(req.url);
  if (req.url === '/upload') {
    const chunks=[]; req.on('data',chunk=>chunks.push(chunk)); req.on('end',()=>{
      uploads.push(Buffer.concat(chunks)); res.setHeader('content-type','application/json');res.end('{"code":200}');
    }); return;
  }
  if (req.url === '/') { res.setHeader('content-type','text/html'); res.end(html); return; }
  if (req.url === '/runtime.js' || req.url === '/nav.js') {
    res.setHeader('content-type','text/javascript'); res.end(fs.readFileSync(path.join(sources,req.url.slice(1)))); return;
  }
  if (req.url === '/failed.mp4') { res.writeHead(503); res.end('fixture unavailable'); return; }
  const file = req.url.includes('.mp3') ? 'audio.mp3' : req.url.includes('.mp4') ? 'clip.mp4' : null;
  if (!file) { res.writeHead(404); res.end(); return; }
  const send = () => { if (res.destroyed) return; const data=fs.readFileSync(path.join(assets,file));
    res.writeHead(200,{'content-type':file.endsWith('mp4')?'video/mp4':'audio/mpeg','content-length':data.length}); res.end(data); };
  if (req.url.startsWith('/slow')) setTimeout(send,1500); else send();
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const origin=`http://127.0.0.1:${server.address().port}`;
let browser;
try {
  browser=await puppeteer.launch({executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true,
    args:['--autoplay-policy=no-user-gesture-required','--disable-dev-shm-usage']});
  const page=await browser.newPage();
  await page.setRequestInterception(true);
  page.on('request',req=>new URL(req.url()).origin===origin ? req.continue() : req.abort());
  const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  await page.goto(origin,{waitUntil:'domcontentloaded'});
  await page.waitForFunction(()=>probe.events.some(e=>e.type==='timeupdate'&&e.time>0),{timeout:10000});
  const first=await page.evaluate(()=>({events:probe.events.slice(),state:MPWEB.mediaState(),video:probe.__root.querySelector('video').videoWidth}));
  assert.equal(first.video,160); assert.ok(first.events.some(e=>e.type==='play'&&e.token===1));
  checks.push('native video decoded 160px frames and emitted token-bound play/timeupdate');
  assert.equal(await page.evaluate(async()=>{const old=probe.__root.querySelector('video');probe.setData({token:2});await Promise.resolve();return old===probe.__root.querySelector('video');}),true);
  checks.push('setData preserves native media identity');
  await page.waitForFunction(()=>probe.events.some(e=>e.type==='ended'),{timeout:10000});
  checks.push('native ended callback forwarded');
  await page.evaluate(()=>{probe.events=[];probe.setData({src:location.origin+'/failed.mp4',token:3});});
  await page.waitForFunction(()=>probe.events.some(e=>e.type==='error'&&e.token===3),{timeout:10000});
  checks.push('HTTP503 emits native error with current token');
  await page.evaluate(()=>{probe.events=[];probe.setData({src:location.origin+'/slow.mp4',token:4});});
  await new Promise(resolve=>setTimeout(resolve,500));
  assert.equal(await page.evaluate(()=>probe.events.some(e=>e.type==='timeupdate'&&e.time>0)),false);
  await page.waitForFunction(()=>probe.events.some(e=>e.type==='timeupdate'&&e.time>0&&e.token===4),{timeout:10000});
  checks.push('delayed video emits no fabricated progress before bytes arrive');
  await page.evaluate(()=>{window.audioProbe=wx.createInnerAudioContext();window.audioStarted=0;audioProbe.onPlay(()=>audioStarted++);audioProbe.src=location.origin+'/slow.mp3';audioProbe.play();});
  await new Promise(resolve=>setTimeout(resolve,500));
  assert.equal(await page.evaluate(()=>audioStarted),0);
  await page.waitForFunction(()=>audioStarted>0&&audioProbe.currentTime>0,{timeout:10000});
  checks.push('slow audio onPlay waits for native playing and advancing clock');
  await page.evaluate(()=>{wx.createVideoContext('probe-video',probe).stop();audioProbe.destroy();});
  assert.equal(await page.evaluate(()=>probe.__root.querySelector('video').paused),true);
  assert.equal(await page.evaluate(()=>MPWEB.audioLive.size),0);
  checks.push('video context stop and audio destroy release playback');
  const picked=await page.evaluate(async()=>{MPWEB.mediaQueue=[{tempFilePath:'test.mp4',duration:2,size:123,fileType:'video'}];return await new Promise(resolve=>wx.chooseMedia({mediaType:['video'],success:r=>resolve(r.tempFiles[0])}));});
  assert.equal(picked.fileType,'video'); assert.equal(picked.duration,2);
  checks.push('explicit native picker queue preserves chosen video metadata');
  const cancelled=await page.evaluate(async()=>await new Promise(resolve=>wx.chooseMedia({mediaType:['video'],fail:r=>resolve(r.errMsg)})));
  assert.match(cancelled,/cancel/); checks.push('empty video queue cancels rather than inventing a selected image');
  for (const [file,mime] of [['clip.mp4','video/mp4'],['audio.mp3','audio/mpeg']]) {
    const bytes=fs.readFileSync(path.join(assets,file));
    const uploaded=await page.evaluate(async(data)=>await new Promise(resolve=>wx.uploadFile({url:location.origin+'/upload',name:'file',filePath:data,success:r=>resolve(r.statusCode)})),`data:${mime};base64,${bytes.toString('base64')}`);
    assert.equal(uploaded,200); assert.ok(uploads.at(-1).includes(bytes));
    assert.ok(uploads.at(-1).includes(Buffer.from(`filename="rig-upload.${file.split('.').pop()}"`)));
  }
  checks.push('multipart uploads preserve actual MP4/MP3 bytes and correct file extensions');
  const localUpload=await page.evaluate(async()=>await new Promise(resolve=>wx.uploadFile({url:location.origin+'/upload',name:'file',filePath:location.origin+'/clip.mp4',success:r=>resolve(r.statusCode)})));
  assert.equal(localUpload,200); assert.ok(uploads.at(-1).includes(fs.readFileSync(path.join(assets,'clip.mp4'))));
  checks.push('selected same-origin MP4 uploads original bytes instead of an image fallback');
  assert.deepEqual(errors,[]);
  const result={status:'PASS',scope:'harness-only, synthetic localhost media; no business/device acceptance',checks,requests,
    trace:await page.evaluate(()=>({audio:MPWEB.audioLog,video:MPWEB.videoLog}))};
  fs.writeFileSync(path.join(root,'media-selfcheck.json'),JSON.stringify(result,null,2)+'\n');
  console.log(JSON.stringify({status:'PASS',checks:checks.length,report:path.join(root,'media-selfcheck.json')}));
} finally {
  if(browser) await browser.close();
  server.closeAllConnections(); await new Promise(resolve=>server.close(resolve));
}
