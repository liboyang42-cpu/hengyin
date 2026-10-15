'use strict';
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),{createRequire}=require('node:module');
const root=path.resolve(__dirname,'../..');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
function post(id=77,author=1,over={}){return{post:{id,authorId:author,version:3,lifecycle:'PUBLISHED',audience:'PUBLIC',body:'原文',viewerLiked:0,viewerBookmarked:0,likeCount:3,bookmarkCount:2,commentCount:2,...over},media:[],references:[],viewerIsAuthor:author===1,viewerFollowing:false,viewerCanComment:true,topicCodes:[],mentions:[]}}
function comment(id=501,author=1,over={}){return{id,post_id:77,author_id:author,version:2,body:'评论',parent_id:null,root_id:id,viewer_liked:0,viewer_following:0,like_count:3,...over}}
function load(route,options={}){const file=path.join(root,`pages/square/${route}/index.js`);let definition,actor=1;const requests=[],modals=[],toasts=[],timers=[],patches=[];
 const toast=value=>toasts.push(value);toast.success=toast;const wx={hideTabBar(){},stopPullDownRefresh(){},getStorageSync(){},setStorageSync(){},removeStorageSync(){},navigateBack(){},reLaunch(){},previewImage(){}};
 const app={globalData:{},getUserID:()=>actor,getPageSize:()=>10,getAvatar:()=>'',getNickname:()=>'',getRequestErrorMessage:(res,fallback)=>res&&res.msg||fallback,sendRequest(o){requests.push(o);return{aborted:false,abort(){this.aborted=true}}}};
 let page;const req=createRequire(file);
 vm.runInNewContext(options.source||fs.readFileSync(file,'utf8'),{getApp:()=>app,getCurrentPages:()=>options.share?[page]:options.prev?[options.prev,page]:[page],Page:c=>definition=c,wx,console,
  setTimeout:cb=>{timers.push(cb);return timers.length},clearTimeout(){},setInterval(){},clearInterval(){},require(id){if(id.endsWith('/utils/modal.js'))return{show:o=>modals.push(o)};if(id.endsWith('/utils/toast.js'))return toast;if(id.endsWith('/utils/merchant-theme.js'))return{merchantPageRestore(){}};if(id.endsWith('/utils/analytics.js'))return{track(){}};return req(id)}});
 page=Object.assign({},definition,{data:JSON.parse(JSON.stringify(definition.data)),selectComponent:()=>null,setData(patch,callback){patches.push(patch);for(const [key,value]of Object.entries(patch)){const parts=key.replace(/\[(\d+)\]/g,'.$1').split('.');let node=this.data;while(parts.length>1){const part=parts.shift();node=node[part]||(node[part]={})}node[parts[0]]=value}if(callback)callback.call(this)},burstLike(){}});
 const asked=url=>requests.filter(r=>r.url===url);
 async function answer(url,data,code=200){const request=asked(url).at(-1);if(!request)throw new Error('缺少真实请求 '+url);request.success({code,data});await tick();return request}
 async function ready(bundle=post(),rows=[comment(),comment(500,2)]){page.onLoad({id:'77'});await tick();await answer('/api/v1/community/posts/77',bundle);await answer('/api/v1/community/posts/77/comments',{items:rows,hasMore:false,nextCursor:rows.length?rows.at(-1).id:null});}
 return{page,requests,modals,toasts,timers,patches,app,wx,asked,answer,ready,changeOwner(){actor=2},source:fs.readFileSync(file,'utf8')};
}
module.exports={load,post,comment,tick,root};
