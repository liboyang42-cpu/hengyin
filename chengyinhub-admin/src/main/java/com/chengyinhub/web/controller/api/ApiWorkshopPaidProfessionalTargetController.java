package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.marketplace.paid.PaidProfessionalTargetReader;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** NEW owner-only metadata contract. Profile and reader are both absent by default. These reads
 * do not bind an installation, create a CMS template, authorize publication or attest a full CAS. */
@RestController
@Profile("w18-paid-professional-target-read-contract")
@RequestMapping("/api/workshop/purchased/professional-targets")
public final class ApiWorkshopPaidProfessionalTargetController {
    @Autowired(required=false) private PaidProfessionalTargetReader reader;
    @PostMapping("/list") public ResponseEntity<AjaxResult> list(HttpServletRequest request){return handle(request,false);}
    @PostMapping("/detail") public ResponseEntity<AjaxResult> detail(HttpServletRequest request){return handle(request,true);}
    private ResponseEntity<AjaxResult> handle(HttpServletRequest request,boolean detail){
        try{
            long actor=actor();Map<String,Object> values=read(request);
            Set<String> allowed=new HashSet<String>(Arrays.asList("licenseId",detail?"topicId":"beforeTopicId"));
            if(!allowed.containsAll(values.keySet())||detail&&!values.keySet().equals(allowed))throw bad();
            Object license=values.get("licenseId");if(!(license instanceof String)||!((String)license).matches("w18-paid-[1-9][0-9]{0,18}"))throw bad();
            Long topic=null;if(detail)topic=positive(values.get("topicId"));else if(values.containsKey("beforeTopicId"))topic=positive(values.get("beforeTopicId"));
            if(reader==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_PROFESSIONAL_TARGET_NOT_ENABLED");
            Object result=detail?reader.detail(actor,(String)license,topic):reader.list(actor,(String)license,topic);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.success(Objects.requireNonNull(result)));
        }catch(ServiceException invalid){boolean unauthenticated=Integer.valueOf(401).equals(invalid.getCode());return failure(unauthenticated?HttpStatus.UNAUTHORIZED:HttpStatus.BAD_REQUEST,unauthenticated?"AUTH_REQUIRED":"INVALID_REQUEST");}
        catch(Exception unavailable){return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_PROFESSIONAL_TARGET_UNAVAILABLE");}
    }
    private static Map<String,Object> read(HttpServletRequest request)throws IOException{
        long length=request.getContentLengthLong();if(request.getQueryString()!=null||length<2||length>512||request.getHeader("Transfer-Encoding")!=null||request.getContentType()==null)throw bad();
        try{MediaType type=MediaType.parseMediaType(request.getContentType());if(!"application".equals(type.getType())||!"json".equals(type.getSubtype())||type.getCharset()!=null&&!StandardCharsets.UTF_8.equals(type.getCharset()))throw bad();}catch(IllegalArgumentException invalid){throw bad();}
        ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=request.getInputStream();for(int b=in.read();b!=-1;b=in.read()){if(out.size()>=512)throw bad();out.write(b);}if(out.size()!=length)throw bad();
        String json;try{json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}catch(CharacterCodingException invalid){throw bad();}
        Map<String,Object> values=new LinkedHashMap<String,Object>();
        // Scalar-only bounded input: reject nested/deep payloads before any tree allocation/query.
        try(JsonParser p=new JsonFactory().createParser(json)){
            if(p.nextToken()!=JsonToken.START_OBJECT)throw bad();JsonToken token;
            while((token=p.nextToken())!=JsonToken.END_OBJECT){if(token!=JsonToken.FIELD_NAME||values.size()>=2||values.containsKey(p.currentName()))throw bad();String key=p.currentName();token=p.nextToken();
                if(token==JsonToken.VALUE_STRING)values.put(key,p.getText());else if(token==JsonToken.VALUE_NUMBER_INT)values.put(key,p.getLongValue());else throw bad();}
            if(p.nextToken()!=null)throw bad();
        }catch(JsonProcessingException invalid){throw bad();}return values;
    }
    private static long positive(Object value){if(!(value instanceof Long)||(Long)value<=0)throw bad();return (Long)value;}
    private static long actor(){Authentication auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof LoginAppUser))throw new ServiceException("AUTH_REQUIRED",401);
        LoginAppUser login=(LoginAppUser)auth.getPrincipal();if(login.getAppUser()==null||login.getAppUser().getUserId()==null||login.getAppUser().getUserId()<=0)throw new ServiceException("AUTH_REQUIRED",401);return login.getAppUser().getUserId();}
    private static ServiceException bad(){return new ServiceException("INVALID_REQUEST",400);}
    private static ResponseEntity<AjaxResult> failure(HttpStatus status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.error(status.value(),code).put("errorCode",code));}
}
