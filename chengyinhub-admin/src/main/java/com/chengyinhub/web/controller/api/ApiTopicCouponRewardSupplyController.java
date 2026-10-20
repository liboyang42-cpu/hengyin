package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.coupon.TopicCouponRewardSupplyService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.exception.ServiceException;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** Pure owner supply read; no issuance, reservation, random, cash or anonymous fallback. */
@RestController
@Profile("w17-topic-coupon-supply-read")
@RequestMapping("/api/rewards/topic-coupon-options")
public final class ApiTopicCouponRewardSupplyController {
    private final TopicCouponRewardSupplyService service;
    public ApiTopicCouponRewardSupplyController(TopicCouponRewardSupplyService service){this.service=Objects.requireNonNull(service);}
    @GetMapping public ResponseEntity<AjaxResult> options(@RequestParam MultiValueMap<String,String> query) {
        Authentication auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof LoginAppUser))return fail(401,"AUTH_REQUIRED");
        LoginAppUser user=(LoginAppUser)auth.getPrincipal();
        if(user.getAppUser()==null || user.getAppUser().getUserId()==null || user.getAppUser().getUserId()<=0)return fail(401,"AUTH_REQUIRED");
        try {
            Set<String> allowed=new HashSet<String>(Arrays.asList("limit","cursor"));
            if(query==null || !allowed.containsAll(query.keySet()) || !query.containsKey("limit"))return fail(400,"INVALID_REQUEST");
            for(List<String> values:query.values())if(values==null || values.size()!=1 || values.get(0)==null)return fail(400,"INVALID_REQUEST");
            String raw=query.getFirst("limit"),cursor=query.getFirst("cursor");
            if(!raw.matches("[1-9][0-9]?") || cursor!=null && !cursor.matches("[1-9][0-9]{0,18}"))return fail(400,"INVALID_REQUEST");
            int limit=Integer.parseInt(raw);if(limit>50)return fail(400,"INVALID_REQUEST");
            Long after=cursor==null?null:Long.valueOf(cursor);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(service.list(user.getAppUser().getUserId(),limit,after)));
        }catch(NumberFormatException malformed){return fail(400,"INVALID_REQUEST");}
        catch(ServiceException denied){return fail(403,"SUPPLY_FORBIDDEN");}
        catch(IllegalArgumentException denied){return fail(403,"SUPPLY_FORBIDDEN");}
        catch(RuntimeException unavailable){return fail(503,"SUPPLY_UNAVAILABLE");}
    }
    private static ResponseEntity<AjaxResult> fail(int status,String code){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AjaxResult.error(status,code).put("errorCode",code));}
}
