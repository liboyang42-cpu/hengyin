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
  const assertVisibility=expression=>{
    const visible=new Function('item','entryCoverErrors','return !!('+expression+')');
    assert.equal(visible({cover:''},{}),false);
    assert.equal(visible({cover:'real.jpg'},{}),true);
    assert.equal(visible({cover:'failed.jpg'},{'failed.jpg':true}),false);
    assert.equal(visible({cover:'new.jpg'},{'old.jpg':true}),true);
  };
  const assertCovers=source=>{
    const images=[...source.matchAll(/<image\s+wx:if="\{\{([^}]+)\}\}"\s+class="entry-card__cover"[^>]*>/g)];
    assert.equal(images.length,3);
    for(const [tag,expression]of images){
      const attrs=Object.fromEntries([...tag.matchAll(/([\w:-]+)="([^"]*)"/g)].map(match=>[match[1],match[2]]));
      assert.equal(attrs.src,'{{item.cover}}');assert.equal(attrs.binderror,'onEntryCoverError');
      assertVisibility(expression);
    }
    assert.equal((source.match(/class="entry-card[^"\n]*entry-card--text/g)||[]).length,3);
    const shades=(source.match(/<view\b[^>]*>/g)||[]).filter(tag=>{
      const classes=(tag.match(/\bclass="([^"]*)"/)||[])[1]||'';
      return classes.split(/\s+/).includes('entry-card__shade');
    });
    assert.equal(shades.length,3);
    for(const tag of shades){
      const attrs=Object.fromEntries([...tag.matchAll(/([\w:-]+)="([^"]*)"/g)].map(match=>[match[1],match[2]]));
      assert.ok(attrs.class.split(/\s+/).includes('entry-card__shape'),'保留装饰shape语义');
      assert.equal(attrs['aria-hidden'],'true','装饰不冒充可访问内容');
      const expression=(attrs['wx:if']||'').match(/^\{\{([^}]+)\}\}$/);
      assert.ok(expression,'遮罩有自己的实际图片guard');
      assertVisibility(expression[1]);
    }
    assert.doesNotMatch(source,/home-route-city-placeholder|entryActivity.cover \|\|/);
    return {image:images[0][0],shade:shades[0]};
  };
  const {image,shade}=assertCovers(markup);
  for(const [original,changed]of [
    [image,image.replace('item.cover && !entryCoverErrors[item.cover]','item.cover')],
    [image,image.replace(/(\s)src="\{\{item.cover\}\}"/,'$1src="{{item.cover || \'/unit-fake-cover.png\'}}"')],
    [shade,shade.replace('item.cover && !entryCoverErrors[item.cover]','item.cover')],
    [shade,shade.replace(' entry-card__shape','')],
    [shade,shade.replace('aria-hidden="true"','aria-hidden="false"')],
  ]){
    assert.notEqual(changed,original,'负控必须实际变异真实图片/遮罩');
    assert.throws(()=>assertCovers(markup.replace(original,changed)),{name:'AssertionError'});
  }
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
