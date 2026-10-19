package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.marketplace.paid.*;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** NEW independently gated owner confirmation/history endpoints. Profile and handler are absent
 * by default; existing metadata/body grants never satisfy this write port. Prerequisites include
 * the reviewed paid precision fix and complete legacy dual-sentinel guards. No production enable. */
@RestController
@Profile("w18-paid-professional-materialization-contract")
@RequestMapping("/api/workshop/purchased/professional-materialization")
public final class ApiWorkshopPaidMaterializationController {
    @Autowired(required=false) private PaidProfessionalMaterializationHandling materialization;
    // Match the existing personal-template draft write rate, not a license-use quota.
    @RateLimiter(time=60,count=30,limitType=LimitType.MEMBER)
    @PostMapping("/submit") public ResponseEntity<AjaxResult> submit(HttpServletRequest request){return handle(request,true);}
    @PostMapping("/status") public ResponseEntity<AjaxResult> status(HttpServletRequest request){return handle(request,false);}
    private ResponseEntity<AjaxResult> handle(HttpServletRequest request,boolean submit){
        try{
            long actor=actor();Map<String,Object> values=read(request);Object result;
            if(submit){
                exact(values,"schema","requestId","installationRequestId","ownedDraftId","licenseId","moduleId","purchasedVersionId","contentHash","termsHash","componentHash","currentDraftRevision","currentDraftPayloadHash","targetTopicId","targetOwnerModeFingerprint","confirmedMode");
                if(!PaidProfessionalMaterializationCommand.SCHEMA.equals(string(values,"schema")))throw bad();
                PaidProfessionalMaterializationCommand intent;
                try{intent=new PaidProfessionalMaterializationCommand(string(values,"requestId"),string(values,"installationRequestId"),positive(values,"ownedDraftId"),
                        string(values,"licenseId"),string(values,"moduleId"),string(values,"purchasedVersionId"),string(values,"contentHash"),string(values,"termsHash"),string(values,"componentHash"),
                        positive(values,"currentDraftRevision"),string(values,"currentDraftPayloadHash"),positive(values,"targetTopicId"),string(values,"targetOwnerModeFingerprint"),
                        PaidProfessionalMaterializationCommand.Mode.valueOf(string(values,"confirmedMode")));}
                catch(IllegalArgumentException invalid){throw bad();}
                if(materialization==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_MATERIALIZATION_NOT_ENABLED");
                result=materialization.materialize(actor,intent);
            }else{
                exact(values,"requestId");String requestId=string(values,"requestId");if(!requestId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw bad();
                if(materialization==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PAID_MATERIALIZATION_NOT_ENABLED");
                result=materialization.status(actor,requestId);
            }
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.success(Objects.requireNonNull(result)));
        }catch(ServiceException invalid){boolean unauthorized=Integer.valueOf(401).equals(invalid.getCode());return failure(unauthorized?HttpStatus.UNAUTHORIZED:HttpStatus.BAD_REQUEST,unauthorized?"AUTH_REQUIRED":"INVALID_REQUEST");}
        catch(Exception unavailable){
            // A connection failure may follow physical commit. Recover the SAME requestId through
            // status; never imply rollback or silently generate a replacement operation.
            return failure(HttpStatus.SERVICE_UNAVAILABLE,submit?"MATERIALIZATION_OUTCOME_NOT_CONFIRMED":"MATERIALIZATION_HISTORY_UNAVAILABLE");
        }
    }
    private static Map<String,Object> read(HttpServletRequest request)throws IOException{
        long length=request.getContentLengthLong();if(request.getQueryString()!=null||length<2||length>4096||request.getHeader("Transfer-Encoding")!=null||request.getContentType()==null)throw bad();
        try{MediaType type=MediaType.parseMediaType(request.getContentType());if(!"application".equals(type.getType())||!"json".equals(type.getSubtype())||type.getCharset()!=null&&!StandardCharsets.UTF_8.equals(type.getCharset()))throw bad();}catch(IllegalArgumentException invalid){throw bad();}
        ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=request.getInputStream();for(int b=in.read();b!=-1;b=in.read()){if(out.size()>=4096)throw bad();out.write(b);}if(out.size()!=length)throw bad();
        String json;try{json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString();}catch(CharacterCodingException invalid){throw bad();}
        Map<String,Object> values=new LinkedHashMap<String,Object>();
        // A fixed scalar-only schema: no tree parsing, nested input, duplicate keys or coercion.
        try(JsonParser parser=new JsonFactory().createParser(json)){
            if(parser.nextToken()!=JsonToken.START_OBJECT)throw bad();JsonToken token;
            while((token=parser.nextToken())!=JsonToken.END_OBJECT){if(token!=JsonToken.FIELD_NAME||values.size()>=15||values.containsKey(parser.currentName()))throw bad();String key=parser.currentName();token=parser.nextToken();
                if(token==JsonToken.VALUE_STRING)values.put(key,parser.getText());else if(token==JsonToken.VALUE_NUMBER_INT)values.put(key,parser.getLongValue());else throw bad();}
            if(parser.nextToken()!=null)throw bad();
        }catch(JsonProcessingException invalid){throw bad();}return values;
    }
    private static void exact(Map<String,Object> values,String... keys){if(!values.keySet().equals(new HashSet<String>(Arrays.asList(keys))))throw bad();}
    private static String string(Map<String,Object> values,String key){if(!(values.get(key) instanceof String))throw bad();return (String)values.get(key);}
    private static long positive(Map<String,Object> values,String key){if(!(values.get(key) instanceof Long)||(Long)values.get(key)<=0)throw bad();return (Long)values.get(key);}
    private static long actor(){Authentication auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof LoginAppUser))throw new ServiceException("AUTH_REQUIRED",401);
        LoginAppUser user=(LoginAppUser)auth.getPrincipal();if(user.getAppUser()==null||user.getAppUser().getUserId()==null||user.getAppUser().getUserId()<=0)throw new ServiceException("AUTH_REQUIRED",401);return user.getAppUser().getUserId();}
    private static ServiceException bad(){return new ServiceException("INVALID_REQUEST",400);}
    private static ResponseEntity<AjaxResult> failure(HttpStatus status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.error(status.value(),code).put("errorCode",code));}
}
