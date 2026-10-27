const { exactId, reader, sameReader } = require('../../../utils/user-character.js');
const { createRecovery } = require('../../../utils/team-event-command.js');
const modal = require('../../../utils/modal.js');
const FIELDS = [
 ['phone','公开值班电话'],['role','值班岗位'],['backupPhone','备用电话（选填）'],['backupRole','备用岗位'],
 ['dutyStart','值班开始（北京时间 YYYY-MM-DDTHH:mm）'],['dutyEnd','值班结束（北京时间 YYYY-MM-DDTHH:mm）'],
 ['meetingName','集合点名称'],['meetingAddress','集合点地址'],['latitude','WGS84纬度'],['longitude','WGS84经度'],
 ['checkinOpenAt','签到开始（北京时间 YYYY-MM-DDTHH:mm）'],['checkinCloseAt','签到结束（北京时间 YYYY-MM-DDTHH:mm）']
];
const TIMES = ['dutyStart','dutyEnd','checkinOpenAt','checkinCloseAt'];
function formatted(ms) { return new Date(Number(ms) + 8*3600000).toISOString().slice(0,16); }
Component({
 properties: { eventId: { type: String, value: '', observer() { if(this._attached)this.open(); } }, organizer: Boolean },
 data: { snapshot: null, error: '', loading: false, recovery: null, fields: FIELDS.map(([key,label])=>({key,label,value:''})), publicConfirmed: false, code: '', notice: '', result: null },
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
    terminal:receipt=>{if(this._current()){this.setData({result:receipt});this.reload();}}
   });
   this.reload();
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
     this.setData({snapshot:view,loading:false,error:'',fields:FIELDS.map(([key,label])=>({key,label,value:form[key]==null?'':TIMES.includes(key)?formatted(form[key]):String(form[key])})),publicConfirmed:!!form.publicConfirmed,
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
  rotate() {this.submit('attendance-rotate',{},'换码后旧码立即失效，已经签到的记录不会撤销。');},
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
