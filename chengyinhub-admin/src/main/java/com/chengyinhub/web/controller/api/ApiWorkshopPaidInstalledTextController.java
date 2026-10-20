package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.marketplace.paid.PaidInstalledTextReader;
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

/** NEW protected-body read. This independent profile/reader is absent by default and cannot be
 * satisfied by purchased metadata approval. No CMS materialization, publication or mutation. */
@RestController
@Profile("w18-paid-installed-text-read-contract")
@RequestMapping("/api/workshop/purchased/installed-text")
public final class ApiWorkshopPaidInstalledTextController {
    @Autowired(required=false) private PaidInstalledTextReader reader;
    @PostMapping("/detail") public ResponseEntity<AjaxResult> detail(HttpServletRequest request){
        try{
            long actor=actor();Map<String,Object> values=read(request);
            if(!values.keySet().equals(new HashSet<String>(Arrays.asList("requestId","ownedDraftId"))))throw bad();
            Object requestId=values.get("requestId");if(!(requestId instanceof String)||!((String)requestId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw bad();
            long owned=positive(values.get("ownedDraftId"));
            if(reader==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_INSTALLED_TEXT_NOT_ENABLED");
            Object result=reader.detail(actor,(String)requestId,owned);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.success(Objects.requireNonNull(result)));
        }catch(ServiceException invalid){boolean unauthenticated=Integer.valueOf(401).equals(invalid.getCode());return failure(unauthenticated?HttpStatus.UNAUTHORIZED:HttpStatus.BAD_REQUEST,unauthenticated?"AUTH_REQUIRED":"INVALID_REQUEST");}
        catch(Exception unavailable){return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_INSTALLED_TEXT_UNAVAILABLE");}
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
