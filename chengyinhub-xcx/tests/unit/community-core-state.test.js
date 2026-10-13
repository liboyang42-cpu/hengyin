const test = require('node:test');
const assert = require('node:assert/strict');
const intent = require('../../utils/publish/publish-intent');
const geo = require('../../utils/roam-geo');
function storage() { const map = {}; return { getStorageSync: k => map[k],
  setStorageSync(k,v) { map[k] = JSON.parse(JSON.stringify(v)); }, removeStorageSync(k) { delete map[k]; } }; }
test('wire stages persist only the exact owner/key and cannot replace immutable intent identity', () => {
  const wx=storage(); const i=intent.begin(wx,{scope:'square:list',memberId:'A',payload:['body']});
  const wire={body:'body',mediaIds:[7]};
  assert.equal(intent.saveStage(wx,'square:list','A',i.key,{stage:'CREATE',createBody:wire}),true);
  wire.mediaIds.push(8);
  assert.deepEqual(intent.read(wx,'square:list','A').createBody.mediaIds,[7]);
  assert.equal(intent.saveStage(wx,'square:list','B',i.key,{stage:'PUBLISH'}),false);
  assert.equal(intent.saveStage(wx,'square:list','A','different-key',{stage:'PUBLISH'}),false);
  assert.equal(intent.saveStage(wx,'square:list','A',i.key,{memberId:'B'}),false);
  assert.equal(intent.saveStage(wx,'square:list','A',i.key,{key:'different-key'}),false);
  assert.equal(intent.read(wx,'square:list','A').stage,'CREATE');
});
test('stage storage failure is explicit and retains the previous durable wire', () => {
  const wx=storage(); const i=intent.begin(wx,{scope:'square:list',memberId:'A',payload:['body']});
  wx.setStorageSync=()=>{throw new Error('storage full')};
  assert.equal(intent.saveStage(wx,'square:list','A',i.key,{stage:'CREATE'}),false);
  assert.equal(intent.read(wx,'square:list','A').stage,undefined);
});
test('9 digit location uses the same exact 7 digit prefix but rejects invalid new coordinates', () => {
  for(const [lat,lng] of [[0,0],[31.2304,121.4737],[-33.8,151.2],[-90,-180],[90,180]]){
    const nine=geo.geohash9(lat,lng);
    assert.match(nine,/^[0123456789bcdefghjkmnpqrstuvwxyz]{9}$/);
    assert.equal(nine.slice(0,7),geo.geohash7(lat,lng));
  }
  for(const [lat,lng] of [[NaN,0],[Infinity,0],[91,0],[0,181],['31',121],[undefined,0]])
    assert.throws(()=>geo.geohash9(lat,lng),/坐标/);
});
