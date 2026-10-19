package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.marketplace.paid.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** NEW explicit private-install command endpoint. Profile and service are independently absent by
 * default. A metadata reader cannot satisfy this port. No checkout, charge or professional publish. */
@RestController
@Profile("w18-paid-private-install-contract")
@RequestMapping("/api/workshop/purchased/install")
public final class ApiWorkshopPaidInstallController {
    @Autowired(required=false) private PaidInstallCommandHandling commands;
    @PostMapping("/targets") public ResponseEntity<AjaxResult> targets(HttpServletRequest request){return handle(request,"targets");}
    @PostMapping("/submit") public ResponseEntity<AjaxResult> submit(HttpServletRequest request){return handle(request,"submit");}
    @PostMapping("/history") public ResponseEntity<AjaxResult> history(HttpServletRequest request){return handle(request,"history");}
    @PostMapping("/status") public ResponseEntity<AjaxResult> status(HttpServletRequest request){return handle(request,"status");}
    private ResponseEntity<AjaxResult> handle(HttpServletRequest request,String action){
        try{
            long actor=actor();Map<String,Object> values=flatJson(request);Object result;
            if("targets".equals(action)){
                keys(values,"licenseId","beforeDraftId");String license=license(values);Long before=values.containsKey("beforeDraftId")?positive(values,"beforeDraftId"):null;
                if(commands==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_INSTALL_NOT_ENABLED");result=commands.targets(actor,license,before);
            }else if("history".equals(action)){
                keys(values,"licenseId","beforeCommandKey");String license=license(values),before=values.containsKey("beforeCommandKey")?string(values,"beforeCommandKey"):null;
                if(before!=null&&!before.matches("[0-9a-f]{64}"))throw bad();
                if(commands==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_INSTALL_NOT_ENABLED");result=commands.history(actor,license,before);
            }else if("status".equals(action)){
                exact(values,"requestId");String id=requestId(values);
                if(commands==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_INSTALL_NOT_ENABLED");result=commands.status(actor,id);
            }else{
                exact(values,"schema","requestId","licenseId","moduleId","purchasedVersionId","contentHash","termsHash","targetDraftId","targetRevision","businessType","targetPayloadHash","planningRegion","commercialUse");
                if(!PaidInstallCommand.SCHEMA.equals(string(values,"schema"))||!(values.get("commercialUse") instanceof Boolean))throw bad();
                PaidInstallCommand command;
                try{command=new PaidInstallCommand(requestId(values),license(values),string(values,"moduleId"),string(values,"purchasedVersionId"),string(values,"contentHash"),string(values,"termsHash"),
                        positive(values,"targetDraftId"),positive(values,"targetRevision"),string(values,"businessType"),string(values,"targetPayloadHash"),string(values,"planningRegion"),(Boolean)values.get("commercialUse"));}
                catch(IllegalArgumentException invalid){throw bad();}
                if(commands==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_INSTALL_NOT_ENABLED");result=commands.submit(actor,command);
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.success(Objects.requireNonNull(result)));
        }catch(ServiceException invalid){return failure(Integer.valueOf(401).equals(invalid.getCode())?HttpStatus.UNAUTHORIZED:HttpStatus.BAD_REQUEST,Integer.valueOf(401).equals(invalid.getCode())?"AUTH_REQUIRED":"INVALID_REQUEST");}
        catch(Exception unavailable){
            // A storage/connection exception may follow a commit. Never tell the client that the
            // install failed or generate a replacement UUID. Query status and retry the SAME command.
            return failure(HttpStatus.SERVICE_UNAVAILABLE,"submit".equals(action)?"INSTALL_OUTCOME_NOT_CONFIRMED":"PAID_INSTALL_UNAVAILABLE");
        }
    }
    private static Map<String,Object> flatJson(HttpServletRequest request)throws IOException{
        long length=request.getContentLengthLong();if(request.getQueryString()!=null||length<2||length>4096||request.getHeader("Transfer-Encoding")!=null||request.getContentType()==null)throw bad();
        try{MediaType type=MediaType.parseMediaType(request.getContentType());if(!"application".equals(type.getType())||!"json".equals(type.getSubtype())||type.getCharset()!=null&&!StandardCharsets.UTF_8.equals(type.getCharset()))throw bad();}catch(IllegalArgumentException invalid){throw bad();}
        ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=request.getInputStream();for(int b=in.read();b!=-1;b=in.read()){if(out.size()>=4096)throw bad();out.write(b);}if(out.size()!=length)throw bad();
        String json;try{json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}catch(CharacterCodingException invalid){throw bad();}
        Map<String,Object> values=new LinkedHashMap<String,Object>();
        // Streaming scalar-only schema. No tree parse, arrays, nested objects, nulls or coercions.
        try(JsonParser p=new JsonFactory().createParser(json)){
            if(p.nextToken()!=JsonToken.START_OBJECT)throw bad();JsonToken token;
            while((token=p.nextToken())!=JsonToken.END_OBJECT){if(token!=JsonToken.FIELD_NAME||values.size()>=13||values.containsKey(p.getCurrentName()))throw bad();String key=p.getCurrentName();token=p.nextToken();Object value;
                if(token==JsonToken.VALUE_STRING)value=p.getText();else if(token==JsonToken.VALUE_NUMBER_INT)value=p.getLongValue();else if(token==JsonToken.VALUE_TRUE)value=Boolean.TRUE;else if(token==JsonToken.VALUE_FALSE)value=Boolean.FALSE;else throw bad();values.put(key,value);}
            if(p.nextToken()!=null)throw bad();
        }catch(JsonProcessingException invalid){throw bad();}return values;
    }
    private static void keys(Map<String,Object> values,String... allowed){if(!new HashSet<String>(Arrays.asList(allowed)).containsAll(values.keySet()))throw bad();}
    private static void exact(Map<String,Object> values,String... expected){if(!values.keySet().equals(new HashSet<String>(Arrays.asList(expected))))throw bad();}
    private static String string(Map<String,Object> values,String key){if(!(values.get(key) instanceof String))throw bad();return (String)values.get(key);}
    private static long positive(Map<String,Object> values,String key){if(!(values.get(key) instanceof Long)||(Long)values.get(key)<=0)throw bad();return (Long)values.get(key);}
    private static String license(Map<String,Object> values){String s=string(values,"licenseId");if(!s.matches("w18-paid-[1-9][0-9]{0,18}"))throw bad();return s;}
    private static String requestId(Map<String,Object> values){String s=string(values,"requestId");if(!s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw bad();return s;}
    private static long actor(){Authentication auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof LoginAppUser))throw new ServiceException("AUTH_REQUIRED",401);
        LoginAppUser login=(LoginAppUser)auth.getPrincipal();if(login.getAppUser()==null||login.getAppUser().getUserId()==null||login.getAppUser().getUserId()<=0)throw new ServiceException("AUTH_REQUIRED",401);return login.getAppUser().getUserId();}
    private static ServiceException bad(){return new ServiceException("INVALID_REQUEST",400);}
    private static ResponseEntity<AjaxResult> failure(HttpStatus status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.error(status.value(),code).put("errorCode",code));}
}
