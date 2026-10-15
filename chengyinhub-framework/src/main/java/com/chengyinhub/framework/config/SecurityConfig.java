package com.chengyinhub.framework.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.util.matcher.RequestMatcher;
import com.chengyinhub.framework.security.matcher.LegacyAntRequestMatcher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.web.filter.CorsFilter;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.security.handle.AuthenticationEntryPointImpl;
import com.chengyinhub.framework.security.handle.LogoutSuccessHandlerImpl;

/**
 * spring security配置
 * 
 * @author ruoyi
 */
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
@Configuration
public class SecurityConfig
{
    /**
     * 自定义用户认证逻辑
     */
    @Autowired
    private UserDetailsService userDetailsService;
    
    /**
     * 认证失败处理类
     */
    @Autowired
    private AuthenticationEntryPointImpl unauthorizedHandler;

    /**
     * 退出处理类
     */
    @Autowired
    private LogoutSuccessHandlerImpl logoutSuccessHandler;

    /**
     * token认证过滤器
     */
    @Autowired
    private JwtAuthenticationTokenFilter authenticationTokenFilter;
    
    /**
     * 跨域过滤器
     */
    @Autowired
    private CorsFilter corsFilter;

    /**
     * 允许匿名访问的地址
     */
    @Autowired
    private PermitAllUrlProperties permitAllUrl;

    /**
     * 身份验证实现
     */
    @Bean
    public AuthenticationManager authenticationManager()
    {
        DaoAuthenticationProvider daoAuthenticationProvider = new DaoAuthenticationProvider(userDetailsService);
        daoAuthenticationProvider.setPasswordEncoder(bCryptPasswordEncoder());
        return new ProviderManager(daoAuthenticationProvider);
    }

    /**
     * anyRequest          |   匹配所有请求路径
     * access              |   SpringEl表达式结果为true时可以访问
     * anonymous           |   匿名可以访问
     * denyAll             |   用户不能访问
     * fullyAuthenticated  |   用户完全认证可以访问（非remember-me下自动登录）
     * hasAnyAuthority     |   如果有参数，参数表示权限，则其中任何一个权限可以访问
     * hasAnyRole          |   如果有参数，参数表示角色，则其中任何一个角色可以访问
     * hasAuthority        |   如果有参数，参数表示权限，则其权限可以访问
     * hasIpAddress        |   如果有参数，参数表示IP地址，如果用户IP和参数匹配，则可以访问
     * hasRole             |   如果有参数，参数表示角色，则其角色可以访问
     * permitAll           |   用户可以任意访问
     * rememberMe          |   允许通过remember-me登录的用户访问
     * authenticated       |   用户登录后可访问
     * 访问白名单配置
     */
    @Bean
    protected SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception
    {
        return httpSecurity
            // CSRF禁用，因为不使用session
            .csrf(csrf -> csrf.disable())
            // 禁用HTTP响应标头
            .headers((headersCustomizer) -> {
                headersCustomizer.cacheControl(cache -> cache.disable())
                        .frameOptions(options -> options.sameOrigin())
                        .referrerPolicy(policy -> policy.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
            })
            // 认证失败处理类
            .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedHandler))
            // 基于token，所以不需要session
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 注解标记允许匿名访问的url
            .authorizeHttpRequests((requests) -> {
                permitAllUrl.getUrls().forEach(url -> requests.requestMatchers(legacyMatchers(url)).permitAll());

//                requests
//                        .requestMatchers(legacyMatchers("/**")).permitAll() // 临时放行所有请求
//                        .anyRequest().permitAll();
                // 对于登录login 注册register 验证码captchaImage 允许匿名访问
                requests.requestMatchers(legacyMatchers("/login", "/register", "/captchaImage")).permitAll()

                    // 静态资源，可匿名访问
                    .requestMatchers(legacyMatchers(HttpMethod.GET, "/", "/*.html", "/**/*.html", "/**/*.css", "/**/*.js", "/profile/**")).permitAll()
                    .requestMatchers(legacyMatchers("/swagger-ui.html", "/swagger-resources/**", "/webjars/**", "/*/api-docs", "/druid/**")).permitAll()
                        .requestMatchers(legacyMatchers("/dev-api/v3/api-docs/**",
                                "/dev-api/swagger-ui/**",
                                "/dev-api/webjars/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/webjars/**")).permitAll()
                        // P0-28 安全:原结算触发端点 /api/common/{activity,topic}_clear 已下线
                        // （仅 .authenticated() 挡不住任意 App 会员越权触发全平台结算,且零调用方）。
                        // 上传会消耗存储并产出公开资源，不能随 /api/common/** 匿名放行。
                        .requestMatchers(legacyMatchers("/api/common/upload", "/api/common/upload/", "/api/common/uploads", "/api/common/uploads/", "/api/common/uploadOSS", "/api/common/uploadOSS/")).authenticated()
                        // 对于APP登录login 允许匿名访问
                        .requestMatchers(legacyMatchers("/api/creativesquare/list","/api/product/list")).permitAll()
                        // 首页/广场公开展示列表，Controller 内部按游客 member_id=0 做降级。
                        .requestMatchers(legacyMatchers("/api/activity/list", "/api/topic/list")).permitAll()
                        // 模板广场公开读:只放行 App/小程序真实使用的聚合、列表、详情和分类。
                        // 严禁改成 /api/template/**，否则 publish/draft/my-list/myinfo 会被匿名放行。
                        .requestMatchers(legacyMatchers(HttpMethod.POST, "/api/template/homeData", "/api/template/list",
                                "/api/template/info", "/api/template/topic-template/list",
                                "/api/template/topic-template/info", "/api/category/list")).permitAll()
                        // 官方活动公开展示:仅 GET 列表/详情匿名可访问(uid() 无登录降级0,Service 仅 memberId>0 查个人态,mapper 过滤草稿,VO 无 PII)。
                        // 精确+单层通配,绝不用 /events/**(会误伤 POST /events/{id}/signup、/complete 刷XP资损)。
                        .requestMatchers(legacyMatchers(HttpMethod.GET, "/api/official/events", "/api/official/events/*")).permitAll()
                        // [P0-安全] 由 /api/login/** 收窄为具体登录入口:/api/login/** 会连带放行任何
                        // 未来新增的 /api/login/xxx(曾放行越权的 /api/login/withopenid)。当前 /api/login/
                        // 下只有 jscode2session 的 /api/login/code 需匿名;密码登录 /api/login 已单列。
                        .requestMatchers(legacyMatchers("/api/common/**","/api/register","/api/login/code","/api/openid","/api/login","/api/login/phone","/api/login/wechat/app","/api/login/apple","/api/sms/send","/api/findpass","/api/comment/list")).permitAll()
                        // 商家公开主页(脱敏后)匿名可访问
                        .requestMatchers(legacyMatchers("/api/merchant/public-detail")).permitAll()
                        // 商家/会员公开资料(白名单 VO,方法内不要求登录):历史分享链接匿名冷启动用。
                        // 精确到两条路径,绝不写成 /api/merchant/** 或 /api/user/**——那会连带放行
                        // 未来新增的任何同前缀接口(/api/user/update、/api/merchant/decor/save …)。
                        .requestMatchers(legacyMatchers("/api/merchant/public-home", "/api/user/public-info")).permitAll()
                        // 足迹分享落地页(第二轮拍板 17):只凭随机令牌读已裁剪快照,方法内不取登录态。精确 GET 一条。
                        .requestMatchers(legacyMatchers(HttpMethod.GET, "/api/roam/share/snapshot")).permitAll()
                        // 发现商家公开列表(脱敏同 public-detail,方法内不取登录态,强制 status=1):仅精确 POST /list。
                        .requestMatchers(legacyMatchers(HttpMethod.POST, "/api/merchant/list")).permitAll()

                        // 除上面外的所有请求全部需要鉴权认证
                    .anyRequest().authenticated();
            })
            // 添加Logout filter
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessHandler(logoutSuccessHandler))
            // 添加JWT filter
            .addFilterBefore(authenticationTokenFilter, UsernamePasswordAuthenticationFilter.class)
            // 添加CORS filter
            .addFilterBefore(corsFilter, JwtAuthenticationTokenFilter.class)
            .addFilterBefore(corsFilter, LogoutFilter.class)
            .build();
    }

    private static RequestMatcher[] legacyMatchers(String... patterns)
    {
        return legacyMatchers(null, patterns);
    }

    private static RequestMatcher[] legacyMatchers(HttpMethod method, String... patterns)
    {
        return java.util.Arrays.stream(patterns)
                .map(pattern -> new LegacyAntRequestMatcher(pattern, method))
                .toArray(RequestMatcher[]::new);
    }

    /**
     * 强散列哈希加密实现
     */
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder()
    {
        return new BCryptPasswordEncoder();
    }
}
