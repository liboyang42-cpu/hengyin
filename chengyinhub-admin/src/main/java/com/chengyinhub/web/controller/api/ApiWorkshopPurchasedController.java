package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.marketplace.paid.read.PurchasedLicenseReading;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** NEW, independently disabled purchased-version metadata endpoint. No checkout, payment, source or install writer. */
@RestController
@Profile("w18-purchased-library-contract")
@RequestMapping("/api/workshop/purchased")
public final class ApiWorkshopPurchasedController {
    @Autowired(required=false) private PurchasedLicenseReading reader;
    @PostMapping("/list") public ResponseEntity<AjaxResult> list(HttpServletRequest request){return read(request,false);}
    @PostMapping("/detail") public ResponseEntity<AjaxResult> detail(HttpServletRequest request){return read(request,true);}
    private ResponseEntity<AjaxResult> read(HttpServletRequest request,boolean detail){
        try {
            long actor=actor();Map<String,String> values=form(request,detail?"license_id":"before_order_line_id",detail);
            String id=values.get("license_id");Long cursor=values.containsKey("before_order_line_id")?positive(values.get("before_order_line_id")):null;
            if(detail&&(id==null||!id.matches("w18-paid-[1-9][0-9]{0,18}")))throw bad();
            if(reader==null)return failure(HttpStatus.SERVICE_UNAVAILABLE,"PURCHASED_LIBRARY_NOT_ENABLED");
            Object result=detail?reader.detail(actor,id):reader.list(actor,cursor);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.success(Objects.requireNonNull(result)));
        }catch(ServiceException rejected){Integer code=rejected.getCode();
            if(Integer.valueOf(401).equals(code))return failure(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED");
            if(Integer.valueOf(400).equals(code))return failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST");
            if(Integer.valueOf(404).equals(code))return failure(HttpStatus.NOT_FOUND,"NOT_FOUND");
            return failure(HttpStatus.SERVICE_UNAVAILABLE,"PURCHASED_LIBRARY_UNAVAILABLE");
        }catch(Exception unavailable){return failure(HttpStatus.SERVICE_UNAVAILABLE,"PURCHASED_LIBRARY_UNAVAILABLE");}
    }
    private static long actor(){
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!auth.isAuthenticated()||!(auth.getPrincipal() instanceof LoginAppUser))throw new ServiceException("AUTH_REQUIRED",401);
        LoginAppUser login=(LoginAppUser)auth.getPrincipal();if(login.getAppUser()==null||login.getAppUser().getUserId()==null||login.getAppUser().getUserId()<=0)throw new ServiceException("AUTH_REQUIRED",401);
        return login.getAppUser().getUserId();
    }
    private static Map<String,String> form(HttpServletRequest request,String key,boolean required)throws IOException{
        long length=request.getContentLengthLong();
        if(request.getContentType()==null||request.getQueryString()!=null||length<0||length>512||request.getHeader("Transfer-Encoding")!=null)throw bad();
        try{MediaType type=MediaType.parseMediaType(request.getContentType());
            if(!"application".equals(type.getType())||!"x-www-form-urlencoded".equals(type.getSubtype())||type.getCharset()!=null&&!StandardCharsets.UTF_8.equals(type.getCharset()))throw bad();
        }catch(IllegalArgumentException invalid){throw bad();}
        ByteArrayOutputStream out=new ByteArrayOutputStream();InputStream in=request.getInputStream();
        for(int b=in.read();b!=-1;b=in.read()){if(b>127||out.size()>=512)throw bad();out.write(b);}
        if(out.size()!=length)throw bad();
        if(length==0){if(required)throw bad();return Collections.emptyMap();}
        String[] pair=new String(out.toByteArray(),StandardCharsets.US_ASCII).split("=",-1);
        if(pair.length!=2||!key.equals(pair[0])||pair[1].isEmpty()||!pair[1].matches("[A-Za-z0-9_-]+"))throw bad();
        return Collections.singletonMap(pair[0],pair[1]);
    }
    private static long positive(String text){if(text==null||!text.matches("[1-9][0-9]{0,18}"))throw bad();try{return Long.parseLong(text);}catch(NumberFormatException e){throw bad();}}
    private static ServiceException bad(){return new ServiceException("INVALID_REQUEST",400);}
    private static ResponseEntity<AjaxResult> failure(HttpStatus status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(AjaxResult.error(status.value(),code).put("errorCode",code));}
}
