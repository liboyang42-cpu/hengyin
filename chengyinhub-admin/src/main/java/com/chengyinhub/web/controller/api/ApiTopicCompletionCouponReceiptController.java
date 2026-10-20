package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.coupon.TopicCompletionCouponReceiptService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** Reads recorded grant facts for the authenticated beneficiary. No eligibility or issue route. */
@RestController
@Profile("w17-topic-completion-receipts")
@RequestMapping("/api/rewards/topic-completion-coupons")
public final class ApiTopicCompletionCouponReceiptController {
    private final TopicCompletionCouponReceiptService service;
    public ApiTopicCompletionCouponReceiptController(TopicCompletionCouponReceiptService service){this.service=Objects.requireNonNull(service);}
    @GetMapping public ResponseEntity<AjaxResult> page(@RequestParam MultiValueMap<String,String> query){
        Long actor=actor();if(actor==null)return fail(401,"AUTH_REQUIRED");
        try {
            if(query==null || !query.containsKey("limit") || !new HashSet<String>(Arrays.asList("limit","cursor")).containsAll(query.keySet()))return fail(400,"INVALID_REQUEST");
            for(List<String> values:query.values())if(values==null || values.size()!=1 || values.get(0)==null)return fail(400,"INVALID_REQUEST");
            String limit=query.getFirst("limit"),cursor=query.getFirst("cursor");
            if(!limit.matches("[1-9][0-9]?") || cursor!=null && !cursor.matches("[1-9][0-9]{0,18}"))return fail(400,"INVALID_REQUEST");
            int count=Integer.parseInt(limit);if(count>50)return fail(400,"INVALID_REQUEST");
            return ok(service.ownedPage(actor,count,cursor==null?null:Long.valueOf(cursor)));
        }catch(IllegalArgumentException invalid){return fail(400,"INVALID_REQUEST");}
        catch(RuntimeException unavailable){return fail(503,"RECEIPT_UNAVAILABLE");}
    }
    @GetMapping("/{logId}") public ResponseEntity<AjaxResult> detail(@PathVariable("logId") String id,@RequestParam MultiValueMap<String,String> query){
        Long actor=actor();if(actor==null)return fail(401,"AUTH_REQUIRED");
        try {
            if(query==null || !query.isEmpty() || id==null || !id.matches("[1-9][0-9]{0,18}"))return fail(400,"INVALID_REQUEST");
            Map<String,Object> value=service.owned(actor,Long.parseLong(id));return value==null?fail(404,"RECEIPT_NOT_FOUND"):ok(value);
        }catch(IllegalArgumentException invalid){return fail(400,"INVALID_REQUEST");}
        catch(RuntimeException unavailable){return fail(503,"RECEIPT_UNAVAILABLE");}
    }
    private static Long actor(){Authentication a=SecurityContextHolder.getContext().getAuthentication();if(a==null || !a.isAuthenticated() || !(a.getPrincipal() instanceof LoginAppUser))return null;LoginAppUser u=(LoginAppUser)a.getPrincipal();return u.getAppUser()!=null && u.getAppUser().getUserId()!=null && u.getAppUser().getUserId()>0?u.getAppUser().getUserId():null;}
    private static ResponseEntity<AjaxResult> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(value));}
    private static ResponseEntity<AjaxResult> fail(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AjaxResult.error(status,code).put("errorCode",code));}
}
