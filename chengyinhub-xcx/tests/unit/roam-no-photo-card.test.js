const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'../..');
const markup=fs.readFileSync(path.join(root,'pages/roam/index.wxml'),'utf8');
function mount(){
  let definition;
  global.getApp=()=>({getUserID:()=> '7',globalData:{},getImgUrl:value=>value});
  global.wx={getStorageSync(){},nextTick:done=>done(),getWindowInfo:()=>({})};
  global.Page=global.Component=value=>{definition=value;};global.Behavior=value=>value;global.getCurrentPages=()=>[];
  const file=path.join(root,'pages/roam/index.js');delete require.cache[require.resolve(file)];require(file);
  return {...definition,data:structuredClone(definition.data),setData(patch){Object.assign(this.data,patch);}};
}
test('city, nearby and live card image guards use only real covers and hide failed image/shade',()=>{
  const images=[...markup.matchAll(/<image\s+wx:if="\{\{([^}]+)\}\}"\s+class="entry-card__cover"[^>]*>/g)];
  assert.equal(images.length,3);
  for(const [tag,expression]of images){
    assert.match(tag,/src="\{\{item.cover\}\}"/);assert.match(tag,/binderror="onEntryCoverError"/);
    const visible=new Function('item','entryCoverErrors','return !!('+expression+')');
    assert.equal(visible({cover:''},{}),false);
    assert.equal(visible({cover:'real.jpg'},{}),true);
    assert.equal(visible({cover:'failed.jpg'},{'failed.jpg':true}),false);
    assert.equal(visible({cover:'new.jpg'},{'old.jpg':true}),true);
  }
  assert.equal((markup.match(/class="entry-card[^"\n]*entry-card--text/g)||[]).length,3);
  assert.equal((markup.match(/wx:if="\{\{item.cover && !entryCoverErrors\[item.cover\]\}\}" class="entry-card__shade"/g)||[]).length,3);
  assert.doesNotMatch(markup,/home-route-city-placeholder|entryActivity.cover \|\|/);
});
test('a real image error selects text presentation for that URL without modifying sources or another card',()=>{
  const page=mount();page.data.entryStrip=[{id:1,cover:'failed.jpg'},{id:2,cover:'good.jpg'}];
  page.onEntryCoverError({currentTarget:{dataset:{src:'failed.jpg'}}});
  assert.equal(page.data.entryCoverErrors['failed.jpg'],true);
  assert.equal(page.data.entryCoverErrors['good.jpg'],undefined);
  assert.equal(page.data.entryStrip[0].cover,'failed.jpg');assert.equal(page.data.entryStrip[1].cover,'good.jpg');
  page._unloaded=true;page.onEntryCoverError({currentTarget:{dataset:{src:'late.jpg'}}});
  assert.equal(page.data.entryCoverErrors['late.jpg'],undefined);
});
test('no-photo cards have content height and text flow instead of an empty photo frame',()=>{
  const css=fs.readFileSync(path.join(root,'pages/roam/index.wxss'),'utf8');
  assert.match(css,/\.entry-card\.entry-card--text\s*\{[^}]*height:auto;[^}]*align-self:flex-start/);
  assert.match(css,/\.entry-card--text \.entry-card__copy\s*\{[^}]*position:relative/);
  assert.match(css,/\.entry-card--text \.entry-card__title\s*\{[^}]*white-space:normal/);
  const page=mount();const card=page._placeCardFor({id:8,_roamId:9,name:'地点',tags:'["roam:seed:v1:place"]',lat:31.2,lng:121.4});
  assert.equal(card.photo,'');assert.equal(card.shareId,'9');
});
