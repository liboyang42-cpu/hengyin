package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.PersonalExplorationExportMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read-only bounded export. Cursor is a position, never an authorization capability or MVCC snapshot. */
@Service
public class PersonalExplorationExportService {
    public static final String FORMAT="chengyin-exploration-export-v1";
    private static final long TTL=24L*60*60*1000;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter WALL=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private final PersonalExplorationExportMapper mapper;
    public PersonalExplorationExportService(PersonalExplorationExportMapper mapper){this.mapper=mapper;}
    protected long now(){return System.currentTimeMillis();}
    private static void require(boolean ok,String message){if(!ok)throw new ServiceException(message,400);}
    private static void owner(Long owner){require(owner!=null&&owner>0,"请先登录");}
    private static LocalDate date(String value){
        try{require(value!=null&&value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),"日期格式无效");LocalDate out=LocalDate.parse(value);require(out.getYear()>=2000&&out.getYear()<=9998,"日期范围无效");return out;}
        catch(java.time.DateTimeException e){throw new ServiceException("日期无效",400);}
    }
    private static void range(String start,String end,long asOf){
        LocalDate from=date(start),to=date(end);long days=ChronoUnit.DAYS.between(from,to);
        require(days>=1&&days<=366,"导出范围须为1–366天");
        require(!to.isAfter(Instant.ofEpochMilli(asOf).atZone(ZONE).toLocalDate().plusDays(1)),"不能导出未来日期");
    }
    private static class Position {
        long owner,asOf,upper,before;int count,emitted,size;String start,end;
        String immutable(){return "1|"+owner+"|"+start+"|"+end+"|"+asOf+"|"+upper+"|"+count+"|"+size;}
        String encode(){return Base64.getUrlEncoder().withoutPadding().encodeToString((immutable()+"|"+before+"|"+emitted).getBytes(StandardCharsets.US_ASCII));}
        String wall(){return Instant.ofEpochMilli(asOf).atZone(ZONE).format(WALL);}
    }
    private Position decode(Long actor,String value){
        owner(actor);require(value!=null&&value.length()>=40&&value.length()<=512&&value.matches("[A-Za-z0-9_-]+"),"分页位置无效");
        try{
            String raw=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.US_ASCII);String[] fields=raw.split("\\|",-1);
            require(fields.length==10&&"1".equals(fields[0]),"分页版本无效");
            Position p=new Position();p.owner=Long.parseLong(fields[1]);p.start=fields[2];p.end=fields[3];p.asOf=Long.parseLong(fields[4]);p.upper=Long.parseLong(fields[5]);p.count=Integer.parseInt(fields[6]);p.size=Integer.parseInt(fields[7]);p.before=Long.parseLong(fields[8]);p.emitted=Integer.parseInt(fields[9]);
            require(value.equals(p.encode()),"分页参数须为规范格式");require(p.owner==actor,"分页不属于当前账号");
            long current=now();require(p.asOf>0&&p.asOf<=current&&current-p.asOf<=TTL,"导出已过期，请重新准备");range(p.start,p.end,p.asOf);
            require(p.upper>=0&&p.count>=0&&p.count<=1000&&p.emitted>=0&&p.emitted<=p.count&&p.size>=1&&p.size<=100,"分页边界无效");
            require((p.count==0?p.upper==0:p.upper>0)&&(p.emitted==0?p.before==0:p.before>0&&p.before<=p.upper),"分页位置不一致");
            return p;
        }catch(IllegalArgumentException e){throw new ServiceException("分页位置无效",400);}
    }
    private Map<String,Object> descriptor(Position p){
        Map<String,Object> out=new LinkedHashMap<>();out.put("format",FORMAT);out.put("timeZone","Asia/Shanghai");out.put("snapshotId",digest(p.immutable()));
        out.put("startDate",p.start);out.put("endDateExclusive",p.end);out.put("asOfMs",p.asOf);out.put("expiresAtMs",p.asOf+TTL);
        out.put("upperRecordId",String.valueOf(p.upper));out.put("expectedRows",p.count);out.put("pageSize",p.size);return out;
    }
    private static String digest(String value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b&255));return out.toString();}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> prepare(Long actor,String startDate,String endDateExclusive,Integer pageSize){
        owner(actor);Position p=new Position();p.owner=actor;p.asOf=now();p.start=startDate;p.end=endDateExclusive;range(p.start,p.end,p.asOf);p.size=pageSize==null?100:pageSize;require(p.size>=1&&p.size<=100,"每页须为1–100条");
        Long upper=mapper.upper(actor,p.start+" 00:00:00",p.end+" 00:00:00",p.wall());p.upper=upper==null?0:upper;
        p.count=mapper.boundedCount(actor,p.start+" 00:00:00",p.end+" 00:00:00",p.wall(),p.upper);require(p.count<=1000,"超过1000条，请缩小日期范围，不会截断导出");
        Map<String,Object> out=descriptor(p);out.put("cursor",p.encode());return out;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> page(Long actor,String cursor){
        Position p=decode(actor,cursor);Map<String,Object> out=descriptor(p);out.put("cursor",cursor);out.put("offset",p.emitted);
        int current=mapper.boundedCount(actor,p.start+" 00:00:00",p.end+" 00:00:00",p.wall(),p.upper);
        if(current!=p.count)return changed(out);
        List<Map<String,Object>> rows=mapper.page(actor,actor,p.start+" 00:00:00",p.end+" 00:00:00",p.wall(),p.upper,p.before==0?null:p.before,p.size+1);
        int size=Math.min(rows.size(),p.size);boolean more=rows.size()>p.size;List<Map<String,Object>> items=new ArrayList<>();long previous=p.before==0?Long.MAX_VALUE:p.before;
        for(Map<String,Object> row:rows.subList(0,size)){
            long id=number(row.get("recordId"));if(id<=0||id>p.upper||(p.before!=0&&id>=previous)||(p.before==0&&!items.isEmpty()&&id>=previous))throw new IllegalStateException("导出顺序无效");previous=id;
            Map<String,Object> item=new LinkedHashMap<>();item.put("recordId",String.valueOf(id));item.put("completedAt",row.get("completedAt"));
            Object version=row.get("routeVersion");item.put("routeVersion",version==null?null:String.valueOf(number(version)));
            item.put("elapsedSeconds",row.get("elapsedSeconds"));String availability=(String)row.get("routeAvailability");
            if(!"AVAILABLE".equals(availability)&&!"UNAVAILABLE".equals(availability))throw new IllegalStateException("路线权限未确认");
            item.put("routeAvailability",availability);item.put("routeTitle","AVAILABLE".equals(availability)?row.get("routeTitle"):null);items.add(item);
        }
        int after=p.emitted+items.size();if(after>p.count||(!more&&after!=p.count)||(more&&after>=p.count))return changed(out);
        out.put("items",items);out.put("emittedRows",after);out.put("complete",!more);out.put("state",more?"MORE":"COMPLETE");
        if(more){p.before=previous;p.emitted=after;out.put("nextCursor",p.encode());}else out.put("nextCursor",null);return out;
    }
    private static long number(Object value){if(!(value instanceof Number))throw new IllegalStateException("导出记录无效");return ((Number)value).longValue();}
    private Map<String,Object> changed(Map<String,Object> out){out.put("items",Collections.emptyList());out.put("emittedRows",out.get("offset"));out.put("complete",false);out.put("state","SOURCE_CHANGED");out.put("nextCursor",null);return out;}
}
