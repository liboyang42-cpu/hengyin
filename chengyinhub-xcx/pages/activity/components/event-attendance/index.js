const { exactId, reader, sameReader } = require('../../../../utils/user-character.js');
const { createRecovery } = require('../../../../utils/team-event-command.js');
const modal = require('../../../../utils/modal.js');
const toast = require('../../../../utils/toast.js');
const FIELDS = [
 ['phone','公开值班电话'],['role','值班岗位'],['backupPhone','备用电话（选填）'],['backupRole','备用岗位'],
 ['dutyStart','值班开始（北京时间 YYYY-MM-DDTHH:mm）'],['dutyEnd','值班结束（北京时间 YYYY-MM-DDTHH:mm）'],
 ['meetingName','集合点名称'],['meetingAddress','集合点地址'],['latitude','WGS84纬度'],['longitude','WGS84经度'],
 ['checkinOpenAt','签到开始（北京时间 YYYY-MM-DDTHH:mm）'],['checkinCloseAt','签到结束（北京时间 YYYY-MM-DDTHH:mm）']
];
const TIMES = ['dutyStart','dutyEnd','checkinOpenAt','checkinCloseAt'];
function formatted(ms) { return new Date(Number(ms) + 8*3600000).toISOString().slice(0,16); }
// 玩家端文案用「10月25日 18:00」/「18:30」(北京时间)
function md(ms) { const t=formatted(ms); return Number(t.slice(5,7))+'月'+Number(t.slice(8,10))+'日 '+t.slice(11,16); }
function hm(v) { const ms=typeof v==='number'?v:Date.parse(String(v||'').replace(' ','T')+(/[zZ]|[+-]\d\d:?\d\d$/.test(String(v||''))?'':'+08:00')); return Number.isFinite(ms)?formatted(ms).slice(11,16):''; }
// 集合点存的是 WGS84,wx.openLocation 要 GCJ-02
function gcj02(lat,lng) {
 const a=6378245.0,ee=0.00669342162296594323,PI=Math.PI;
 if(lng<72.004||lng>137.8347||lat<0.8293||lat>55.8271)return {lat,lng};
 const x=lng-105.0,y=lat-35.0;
 let dLat=-100.0+2.0*x+3.0*y+0.2*y*y+0.1*x*y+0.2*Math.sqrt(Math.abs(x))+(20.0*Math.sin(6.0*x*PI)+20.0*Math.sin(2.0*x*PI))*2.0/3.0+(20.0*Math.sin(y*PI)+40.0*Math.sin(y/3.0*PI))*2.0/3.0+(160.0*Math.sin(y/12.0*PI)+320*Math.sin(y*PI/30.0))*2.0/3.0;
 let dLng=300.0+x+2.0*y+0.1*x*x+0.1*x*y+0.1*Math.sqrt(Math.abs(x))+(20.0*Math.sin(6.0*x*PI)+20.0*Math.sin(2.0*x*PI))*2.0/3.0+(20.0*Math.sin(x*PI)+40.0*Math.sin(x/3.0*PI))*2.0/3.0+(150.0*Math.sin(x/12.0*PI)+300.0*Math.sin(x/30.0*PI))*2.0/3.0;
 const rad=lat/180.0*PI;let magic=Math.sin(rad);magic=1-ee*magic*magic;const sq=Math.sqrt(magic);
 dLat=(dLat*180.0)/((a*(1-ee))/(magic*sq)*PI);dLng=(dLng*180.0)/(a/sq*Math.cos(rad)*PI);
 return {lat:lat+dLat,lng:lng+dLng};
}
// 玩家视图:集合一行(有集合时间就带上)、联系主办一行(契约 §4 GET /api/play/contact 的俱乐部电话;没有号码整行不出)、签到状态
function playerView(s,contact) {
 const c=s.config||{},org=contact||{},mine=s.mine||{},now=Number(s.serverNow)||Date.now();
 const meet=c.meetingName?(c.meetingAt?md(c.meetingAt)+' · ':'')+c.meetingName+'集合':'';
 const phone=org.phone||'',name=String(org.name||'').trim();
 let checkin=null;
 if(s.activeParticipation&&c.checkinOpenAt){
  const done=!!mine.checkedAt,before=now<c.checkinOpenAt,closed=!done&&c.checkinCloseAt&&now>=c.checkinCloseAt;
  checkin={done,doneAt:done?hm(mine.checkedAt):'',show:!done&&!closed,ok:done||(!before&&!closed),
   chip:done?'已签到':closed?'已截止':before?formatted(c.checkinOpenAt).slice(11,16)+' 开放':'可签到'};
 }
 return {meet,canNavigate:Number.isFinite(Number(c.latitude))&&Number.isFinite(Number(c.longitude))&&c.latitude!==''&&c.latitude!=null,
  host:phone?'联系主办'+(name?' · '+name:''):'',hostName:name||'主办',phone,checkin};
}
Component({
 // 玩家端行形复用宿主活动详情页的 od-section / od-task 样式
 options: { styleIsolation: 'apply-shared' },
 properties: { eventId: { type: String, value: '', observer() { if(this._attached)this.open(); } }, organizer: Boolean },
 data: { player: {}, codeShow: false, snapshot: null, error: '', loading: false, recovery: null, fields: FIELDS.map(([key,label])=>({key,label,value:''})), publicConfirmed: false, code: '', notice: '', result: null },
 lifetimes: {
  attached() { this._attached=true;this._visible=true;this.open(); },
  detached() { this.stop();this._attached=false; }
 },
 pageLifetimes: { show() { this._visible=true;if(this._attached)this.open(); }, hide() { this._visible=false;this.stop(); } },
 methods: {
  stop() { if(this._dutyTimer)clearTimeout(this._dutyTimer);this._generation=(this._generation||0)+1;if(this._recovery)this._recovery.dispose(); },
  open() {
   this.stop();this._visible=true;const id=exactId(this.properties.eventId);if(!id)return;
   const app=getApp(), actor=reader(app), generation=this._generation;
   this._current=()=>this._attached&&this._visible&&this._generation===generation&&sameReader(app,actor)&&exactId(this.properties.eventId)===id;
   this.setData({snapshot:null,error:'',notice:'',result:null,code:'',publicConfirmed:false,fields:FIELDS.map(([key,label])=>({key,label,value:''}))});
   this._recovery=createRecovery({app,platform:wx,domain:this.properties.organizer?'EVENT':'SIGNUP',targetId:id,
    changed:value=>{if(this._current())this.setData({recovery:value,result:value.receipt||null});},
    terminal:receipt=>{if(this._current()){
     // 玩家端:签到码领取 / 更换的结果读回即归档,不留「确认归档」一步;被拒时只提示后端原句
     if(!this.properties.organizer){if(receipt.state!=='APPLIED'&&receipt.message)toast(receipt.message);this._recovery.acknowledge();}
     this.setData({result:receipt});this.reload();}}
   });
   this.reload();
   if(!this.properties.organizer)this.loadContact();
  },
  // 联系主办只读主办俱乐部已有电话;读不到就不出这一行,不回退到值班电话
  loadContact() {
   if(!this._current||!this._current())return;
   const current=this._current,id=exactId(this.properties.eventId);this._contact=null;
   getApp().sendRequest({url:'/api/play/contact',method:'GET',data:{activityId:id},hideLoading:true,autoErrorToast:false,
    success:res=>{if(!current())return;this._contact=res&&res.code==200&&res.data&&res.data.phone?res.data:null;
     if(this.data.snapshot)this.setData({player:playerView(this.data.snapshot,this._contact)});},
    fail:()=>{if(current())this._contact=null;}});
  },
  reload() {
   if(!this._current||!this._current())return;
   const current=this._current,seq=this._readSeq=(this._readSeq||0)+1,id=exactId(this.properties.eventId);
   this.setData({loading:true,error:''});
   const fail=()=>{if(current()&&seq===this._readSeq)this.setData({loading:false,error:'联系及签到信息读取失败，请重试',snapshot:null});};
   const request={hideLoading:true,autoErrorToast:false,
    success:res=>{
     if(!current()||seq!==this._readSeq)return;
     if(!res||res.code!=200||!res.data)return fail();
     const s=res.data,c=s.config||{};
     const view={...s, dutyLabel:({UNCONFIGURED:'未配置值班联络',NOT_STARTED:'值班尚未开始',ON_DUTY:'配置的值班时段内',ENDED:'值班已结束'})[s.dutyState]||'值班状态未知',
      dutyWindow:c.dutyStart?formatted(c.dutyStart)+' 至 '+formatted(c.dutyEnd):'',
      checkinWindow:c.checkinOpenAt?formatted(c.checkinOpenAt)+' 至 '+formatted(c.checkinCloseAt):''};
     const frozen=this.data.recovery&&this.data.recovery.input;
     let form=c;
     if(frozen&&frozen.action==='attendance-config')try{form=JSON.parse(frozen.attendanceConfig);}catch(_){}
     if(this._dutyTimer)clearTimeout(this._dutyTimer);
     const boundary=s.dutyState==='NOT_STARTED'?c.dutyStart:s.dutyState==='ON_DUTY'?c.dutyEnd:0;
     if(boundary>s.serverNow)this._dutyTimer=setTimeout(()=>{if(current())this.reload();},Math.min(boundary-s.serverNow+10,2147483647));
     this.setData({snapshot:view,player:playerView(s,this._contact),loading:false,error:'',fields:FIELDS.map(([key,label])=>({key,label,value:form[key]==null?'':TIMES.includes(key)?formatted(form[key]):String(form[key])})),publicConfirmed:!!form.publicConfirmed,
      code:frozen&&frozen.action==='attendance-verify'?frozen.credentialCode:this.data.code});
    },fail,successStatusAbnormal:fail};
   if(this.properties.organizer)getApp().sendRequest({...request,method:'GET',url:'/api/official/events/'+id+'/attendance/manage'});
   else getApp().sendRequest({...request,method:'GET',url:'/api/official/events/'+id+'/attendance'});
  },
  input(e) {
   if(this._recovery&&this._recovery.pending())return;
   const key=e.currentTarget.dataset.key;
   if(!FIELDS.some(f=>f[0]===key))return;
   this.setData({fields:this.data.fields.map(f=>f.key===key?{...f,value:e.detail.value}:f)});
  },
  confirmPublic(e) {if(!this._recovery.pending())this.setData({publicConfirmed:e.detail.value});},
  inputCode(e) {if(!this._recovery.pending())this.setData({code:e.detail.value});},
  submit(action,payload,content) {
   if(!this._current||!this._current()||!this._recovery||this._recovery.pending())return;
   const current=this._current,recovery=this._recovery;
   modal.show({title:'确认操作',content,success:r=>{if(r.confirm&&current())recovery.start(action,payload);}});
  },
  saveConfig() {
   if(!this.data.snapshot)return;
   const c={publicConfirmed:this.data.publicConfirmed};
   try {
    for(const f of this.data.fields){
     if(TIMES.includes(f.key)){
      if(!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(f.value))throw Error('时段请填写完整北京时间');
      c[f.key]=Date.parse(f.value+':00+08:00');if(!Number.isFinite(c[f.key])||formatted(c[f.key])!==f.value)throw Error('时间无效');
     }else if(['latitude','longitude'].includes(f.key)){if(!f.value.trim())throw Error('请填写集合点坐标');c[f.key]=Number(f.value);if(!Number.isFinite(c[f.key]))throw Error('坐标无效');}
     else c[f.key]=f.value;
    }
    if(!c.publicConfirmed)throw Error('请确认联系方式可公开');
    this.submit('attendance-config',{attendanceConfig:JSON.stringify(c),configVersion:this.data.snapshot.configVersion},'保存并公开本场值班电话、岗位、时段和集合点；不会发送通知。');
   }catch(e){this.setData({notice:e.message});}
  },
  claim() {this.submit('attendance-claim',{},'领取本人签到凭证；签到仅记录到场，不影响成绩或奖励。');},
  rotate() {
   if(this.properties.organizer){this.submit('attendance-rotate',{},'换码后旧码立即失效，已经签到的记录不会撤销。');return;}
   // B11:换码不限次;确认框照原型 B
   if(!this._current||!this._current()||!this._recovery||this._recovery.pending())return;
   const current=this._current,recovery=this._recovery;
   modal.show({title:'更换签到码？',content:'旧码会失效，签到记录保留。',confirmText:'更换',success:r=>{if(r.confirm&&current())recovery.start('attendance-rotate',{});}});
  },
  showCode() {
   if(!this._current||!this._current())return;
   this.setData({codeShow:true});
   const mine=this.data.snapshot&&this.data.snapshot.mine;
   if(!(mine&&mine.credential)&&this._recovery&&!this._recovery.pending())this._recovery.start('attendance-claim',{});
  },
  closeCode() {this.setData({codeShow:false});},
  navigate() {
   const p=this.data.player,c=this.data.snapshot&&this.data.snapshot.config;
   if(!p||!p.canNavigate||!c)return;
   const g=gcj02(Number(c.latitude),Number(c.longitude));
   wx.openLocation({latitude:g.lat,longitude:g.lng,name:c.meetingName||'',address:c.meetingAddress||'',scale:16});
  },
  verify() {
   const code=String(this.data.code||'').trim();if(!/^[a-f0-9]{64}$/.test(code)){this.setData({notice:'请使用完整的本场签到码，不是号码布或核销码'});return;}
   this.submit('attendance-verify',{credentialCode:code},'核验到场签到，不核销任何权益。');
  },
  scan() {
   if(!this._current||!this._current()||this._recovery.pending())return;
   const current=this._current;
   wx.scanCode({onlyFromCamera:true,success:r=>{if(current())this.setData({code:String(r.result||''),notice:'请核对候选码后点击核验'});},fail:()=>{if(current())this.setData({notice:'未取得签到码，可手动输入后核验'});}});
  },
  contact(e) {
   if(!this._current||!this._current())return;
   if(!this.properties.organizer){
    // B9:只给「拨打」,号码不上屏;是谁写在确认框里
    const p=this.data.player,current=this._current;if(!p||!p.phone)return;
    modal.show({title:'',content:'拨打「'+p.hostName+'」的电话？',confirmText:'拨打',success:r=>{if(r.confirm)wx.makePhoneCall({phoneNumber:p.phone,fail:()=>{if(current())toast('拨号未完成，请重试');}});}});
    return;
   }
   const c=this.data.snapshot&&this.data.snapshot.config,key=e.currentTarget.dataset.backup?'backupPhone':'phone';
   if(!c||!c[key])return;
   const current=this._current,phone=c[key];
   this.setData({notice:'正在打开拨号；平台无法判断接听或救援是否受理。无人接听可重拨或联系备用岗位；紧急情况请按现场情况拨打110/120。'});
   wx.makePhoneCall({phoneNumber:phone,fail:()=>{if(current())this.setData({notice:'拨号未完成。可重试或联系备用岗位；平台未受理救援请求。'});}});
  },
  commandAction(e) {
   const action=e.currentTarget.dataset.action;
   if(action==='query')this.queryCommand();else if(action==='retry')this.retryCommand();
   else if(action==='cancel')this.cancelCommand();else if(action==='acknowledge')this.acknowledgeCommand();
  },
  commandReady() { return !!(this._current&&this._current()&&this._recovery); },
  queryCommand() { if(this.commandReady())this._recovery.query(); },
  retryCommand() { if(this.commandReady())this._recovery.retry(); },
  cancelCommand() { if(this.commandReady())this._recovery.cancel(); },
  acknowledgeCommand() { if(this.commandReady())this._recovery.acknowledge(); }
 }
});
