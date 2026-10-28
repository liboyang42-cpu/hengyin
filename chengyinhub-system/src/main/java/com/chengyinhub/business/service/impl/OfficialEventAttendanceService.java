package com.chengyinhub.business.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.OfficialEventParticipation;
import com.chengyinhub.business.domain.dto.TeamEventCommand;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.util.*;

/** Arrival only: never touches progress, scores, sessions, redemption or payments. */
@Service
public class OfficialEventAttendanceService {
 private final OfficialEventMapper events;
 private final OfficialEventV2Mapper participants;
 private final OfficialEventAttendanceMapper mapper;
 private final OfficialEventV2ServiceImpl authority;
 private final ISysConfigService settings;
 private static final SecureRandom RANDOM = new SecureRandom();
 public OfficialEventAttendanceService(OfficialEventMapper events, OfficialEventV2Mapper participants,
   OfficialEventAttendanceMapper mapper, OfficialEventV2ServiceImpl authority, ISysConfigService settings) {
  this.events=events; this.participants=participants; this.mapper=mapper; this.authority=authority; this.settings=settings;
 }
 public static boolean handles(String action) {
  return Set.of("attendance-config", "attendance-verify", "attendance-claim", "attendance-rotate").contains(action);
 }
 private OfficialEvent event(Long id, boolean lock) {
  OfficialEvent e=lock?events.selectOfficialEventByIdForUpdate(id):events.selectOfficialEventById(id);
  if(e==null || e.getContractVersion()==null || e.getContractVersion()<2) throw new ServiceException("仅支持官方V2赛事");
  return e;
 }
 private void organizer(OfficialEvent e, Long actor) {
  String whitelist=settings.selectConfigByKey("official_publish_whitelist");
  boolean listed=whitelist!=null && Arrays.stream(whitelist.split(",")).anyMatch(s->s.trim().equals(String.valueOf(actor)));
  if(!listed || !authority.canOperateAttendance(e,actor)) throw new ServiceException("仅本场有效主办可操作");
 }
 private JSONObject config(Long id) {
  Map<String,Object> row=mapper.config(id);
  return row==null?null:JSON.parseObject(String.valueOf(row.get("config_json")));
 }
 private long millis(JSONObject c,String key) {
  Object value=c.get(key);
  if(!(value instanceof Number)) throw new ServiceException("时段必须提供有效时间");
  long n=((Number)value).longValue();
  if(n<=0 || n>253402300799000L) throw new ServiceException("时间超出范围");
  return n;
 }
 private String text(JSONObject c,String key,int max,boolean optional) {
  Object v=c.get(key); if(v==null && optional)return "";
  if(!(v instanceof String) || ((String)v).trim().length()>max || (!optional && ((String)v).trim().isEmpty())) throw new ServiceException("联系配置字段无效："+key);
  return ((String)v).trim();
 }
 private JSONObject validateConfig(OfficialEvent e,String json) {
  if(json==null || json.length()>6000)throw new ServiceException("联系配置无效");
  JSONObject c; try{c=JSON.parseObject(json);}catch(Exception ex){throw new ServiceException("联系配置格式无效");}
  if(c==null || !Boolean.TRUE.equals(c.get("publicConfirmed")))throw new ServiceException("请确认值班联系方式可公开");
  JSONObject clean=new JSONObject();
  for(String key:List.of("phone","role","backupPhone","backupRole","meetingName","meetingAddress")) {
   String v=text(c,key,key.contains("Address")?300:100,key.startsWith("backup"));
   if(key.toLowerCase().contains("phone") && !v.isEmpty() && !v.matches("[+0-9][0-9 -]{2,39}"))throw new ServiceException("联系电话格式无效");
   clean.put(key,v);
  }
  if(clean.getString("backupPhone").isEmpty()!=clean.getString("backupRole").isEmpty())throw new ServiceException("备用电话与岗位须同时填写");
  for(String key:List.of("dutyStart","dutyEnd","checkinOpenAt","checkinCloseAt"))clean.put(key,millis(c,key));
  if(clean.getLongValue("dutyStart")>=clean.getLongValue("dutyEnd"))throw new ServiceException("值班时段无效");
  long open=clean.getLongValue("checkinOpenAt"),close=clean.getLongValue("checkinCloseAt");
  if(e.getSignupStart()==null || e.getActivityEnd()==null || open<e.getSignupStart().getTime() || close>e.getActivityEnd().getTime() || open>=close)throw new ServiceException("签到窗口须在报名开放至赛事结束之间");
  for(String key:List.of("latitude","longitude")) {
   Object v=c.get(key);if(!(v instanceof Number))throw new ServiceException("集合点须提供WGS84坐标");
   double n=((Number)v).doubleValue();if(!Double.isFinite(n) || Math.abs(n)>(key.equals("latitude")?90:180))throw new ServiceException("集合点坐标无效");clean.put(key,n);
  }
  clean.put("publicConfirmed",true); return clean;
 }
 private void active(OfficialEventParticipation p) {
  if(p==null || !OfficialEventParticipation.STATE_ACTIVE.equals(p.getState()))throw new ServiceException("报名资格无效");
 }
 private void window(OfficialEvent e,JSONObject c,long now) {
  if(!Set.of(OfficialEvent.ST_SIGNUP,OfficialEvent.ST_LIVE).contains(e.getStatus()==null?-1:e.getStatus()) || "PAUSED".equals(e.getControlState()))throw new ServiceException("赛事当前不受理签到");
  if(c==null || now<millis(c,"checkinOpenAt") || now>=millis(c,"checkinCloseAt"))throw new ServiceException("当前不在签到窗口");
 }
 private String newCode(){byte[] b=new byte[32];RANDOM.nextBytes(b);return HexFormat.of().formatHex(b);}
 private Map<String,Object> projection(Map<String,Object> row,boolean code) {
  Map<String,Object> out=new LinkedHashMap<>();if(row==null)return out;
  out.put("bib",String.valueOf(row.get("participation_id")));
  out.put("version",String.valueOf(row.get("version")));
  out.put("checkedAt",row.get("checked_at"));out.put("state",row.get("checked_at")==null?"ISSUED":"CHECKED_IN");
  if(code) {
   String credential=String.valueOf(row.get("credential"));out.put("credential",credential);
   out.put("qrImage",qrImage(credential));
  }
  return out;
 }
 private String qrImage(String credential) {
  try {
   // Existing ZXing dependency; inline only, no public OSS object or external side effect.
   com.google.zxing.common.BitMatrix matrix=new com.google.zxing.MultiFormatWriter().encode(credential,com.google.zxing.BarcodeFormat.QR_CODE,300,300);
   java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(300,300,java.awt.image.BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<300;y++)for(int x=0;x<300;x++)image.setRGB(x,y,matrix.get(x,y)?0x000000:0xffffff);
   java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
   if(!javax.imageio.ImageIO.write(image,"png",bytes))throw new IllegalStateException("PNG unavailable");
   return "data:image/png;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());
  } catch(Exception failure) { throw new ServiceException("签到码图像暂不可读，请重试"); }
 }
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> execute(Long actor,TeamEventCommand command) {
  if(actor==null || actor<=0)throw new ServiceException("请先登录");
  Long id=command.getTargetId(); OfficialEvent e=event(id,true);String action=command.getAction();
  if(action.equals("attendance-config")) {
   organizer(e,actor);JSONObject c=validateConfig(e,command.getAttendanceConfig());Map<String,Object> previous=mapper.config(id);
   long version=previous==null?0:((Number)previous.get("version")).longValue();
   if(command.getConfigVersion()==null || command.getConfigVersion()!=version)throw new ServiceException("联系配置已变化，请重读后提交");
   mapper.saveConfig(id,c.toJSONString(),actor,version+1);return Map.of("configVersion",String.valueOf(version+1));
  }
  if(action.equals("attendance-verify")) {
   organizer(e,actor);String code=command.getCredentialCode();
   if(code==null || !code.matches("[a-f0-9]{64}"))throw new ServiceException("签到码无效");
   Map<String,Object> row=mapper.credential(id,code);if(row==null)throw new ServiceException("签到码无效或已换码");
   // Repeat verification reads history, even after the receiving window has closed.
   if(row.get("checked_at")!=null)return projection(row,false);
   active(participants.selectParticipation(id,((Number)row.get("member_id")).longValue()));
   window(e,config(id),System.currentTimeMillis());
   if(mapper.checkin(((Number)row.get("participation_id")).longValue(),actor,new Date())!=1)throw new IllegalStateException("签到写入失败");
   return projection(mapper.credential(id,code),false);
  }
  OfficialEventParticipation p=participants.selectParticipation(id,actor);active(p);
  Map<String,Object> row=mapper.mine(id,actor);
  if(row!=null && (action.equals("attendance-claim") || row.get("checked_at")!=null))return projection(row,false);
  window(e,config(id),System.currentTimeMillis());
  if(action.equals("attendance-rotate")) {
   if(row==null)throw new ServiceException("请先领取签到码");
   if(mapper.rotate(p.getId(),newCode())!=1)throw new IllegalStateException("换码失败");
  } else if(action.equals("attendance-claim"))mapper.issue(id,actor,p.getId(),newCode());
  else throw new ServiceException("未知签到命令");
  return projection(mapper.mine(id,actor),false);
 }
 @Transactional(rollbackFor=Exception.class)
 public Map<String,Object> read(Long id,Long actor,boolean management) {
  OfficialEvent e=event(id,management);OfficialEventParticipation p=actor==null||actor<=0?null:participants.selectParticipation(id,actor);
  if(management)organizer(e,actor);
  else if((e.getStatus()==null || e.getStatus()<OfficialEvent.ST_SCHEDULED || e.getStatus()==OfficialEvent.ST_OFFLINE) && p==null)throw new ServiceException("赛事当前不可见");
  JSONObject c=config(id);Map<String,Object> out=new LinkedHashMap<>();out.put("config",c);
  Map<String,Object> cfg=mapper.config(id);out.put("configVersion",cfg==null?"0":String.valueOf(cfg.get("version")));
  long now=System.currentTimeMillis();out.put("serverNow",now);
  out.put("dutyState",c==null?"UNCONFIGURED":now<millis(c,"dutyStart")?"NOT_STARTED":now>=millis(c,"dutyEnd")?"ENDED":"ON_DUTY");
  Map<String,Object> mine=actor==null||actor<=0?null:mapper.mine(id,actor);
  boolean active=p!=null && OfficialEventParticipation.STATE_ACTIVE.equals(p.getState());
  out.put("mine",projection(mine,active));out.put("activeParticipation",active);
  try{window(e,c,now);out.put("receiving",true);}catch(ServiceException closed){out.put("receiving",false);out.put("receivingReason",closed.getMessage());}
  return out;
 }
}
