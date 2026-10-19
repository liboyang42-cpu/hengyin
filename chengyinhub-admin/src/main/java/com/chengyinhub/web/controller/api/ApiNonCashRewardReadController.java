package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.reward.NonCashRewardReadService;
import com.chengyinhub.business.reward.NonCashRewardContracts.Scope;
import com.chengyinhub.business.reward.NonCashRewardContracts.ScopeType;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import java.util.*;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** Owner-only query boundary; requires explicit evaluation profile AND read-service bean.
 * No production factory, Anonymous marker, security filter change or mutation endpoint.
 */
@RestController
@Profile("w17-reward-read-contract")
@ConditionalOnBean(NonCashRewardReadService.class)
@RequestMapping("/api/rewards/noncash")
public final class ApiNonCashRewardReadController {
    private final NonCashRewardReadService service;
    public ApiNonCashRewardReadController(NonCashRewardReadService service){this.service=Objects.requireNonNull(service);}
    @GetMapping
    public ResponseEntity<AjaxResult> list(@RequestParam MultiValueMap<String,String> query){
        return respond(actor->{
            parameters(query,new HashSet<>(Arrays.asList("limit","cursor")),Collections.singleton("limit"));
            String limit=query.getFirst("limit");if(!limit.matches("[1-9][0-9]?"))throw new InvalidRequest();
            return service.list(actor,Integer.parseInt(limit),query.getFirst("cursor"));
        });
    }
    @GetMapping("/{awardId}")
    public ResponseEntity<AjaxResult> detail(@PathVariable("awardId") String awardId,@RequestParam MultiValueMap<String,String> query){
        return respond(actor->{
            Set<String> fields=new HashSet<>(Arrays.asList("contextType","contextId","releaseId","instanceId"));
            parameters(query,fields,fields);Scope scope;
            try{scope=new Scope(ScopeType.valueOf(query.getFirst("contextType")),query.getFirst("contextId"),query.getFirst("releaseId"),query.getFirst("instanceId"));}
            catch(RuntimeException malformed){throw new InvalidRequest();}
            return service.detail(actor,awardId,scope);
        });
    }
    private ResponseEntity<AjaxResult> respond(Function<Long,Object> operation){
        try{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(operation.apply(actor())));}
        catch(InsufficientAuthenticationException denied){return failure(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","请先登录");}
        catch(InvalidRequest malformed){return failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","请求参数无效");}
        catch(NonCashRewardReadService.Rejection rejected){
            switch(rejected.getCode()){
                case "AUTH_REQUIRED":return failure(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","请先登录");
                case "INVALID_REQUEST":return failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","请求参数无效");
                case "NOT_FOUND":return failure(HttpStatus.NOT_FOUND,"NOT_FOUND","未找到该奖励");
                default:return unavailable();
            }
        }catch(RuntimeException unavailable){return unavailable();}
    }
    private long actor(){
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null||!authentication.isAuthenticated()||!(authentication.getPrincipal() instanceof LoginAppUser))throw new InsufficientAuthenticationException("APP_SESSION_REQUIRED");
        LoginAppUser login=(LoginAppUser)authentication.getPrincipal();
        // Existing BaseController/W16 convention: nested App user ID, never query or outer login ID.
        if(login.getAppUser()==null||login.getAppUser().getUserId()==null||login.getAppUser().getUserId()<=0)throw new InsufficientAuthenticationException("APP_SESSION_REQUIRED");
        return login.getAppUser().getUserId();
    }
    private static void parameters(MultiValueMap<String,String> query,Set<String> allowed,Set<String> required){
        if(query==null||!allowed.containsAll(query.keySet())||!query.keySet().containsAll(required))throw new InvalidRequest();
        for(List<String> values:query.values())if(values==null||values.size()!=1||values.get(0)==null)throw new InvalidRequest();
    }
    private static final class InvalidRequest extends IllegalArgumentException{private static final long serialVersionUID=1L;}
    private static ResponseEntity<AjaxResult> unavailable(){return failure(HttpStatus.SERVICE_UNAVAILABLE,"REWARDS_UNAVAILABLE","奖励查询暂时不可用，请稍后重试");}
    private static ResponseEntity<AjaxResult> failure(HttpStatus status,String code,String message){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(AjaxResult.error(status.value(),message).put("errorCode",code));}
}
