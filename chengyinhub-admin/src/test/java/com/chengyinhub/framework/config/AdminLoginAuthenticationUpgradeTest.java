package com.chengyinhub.framework.config;

import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdminLoginAuthenticationUpgradeTest {
    private LoginUser user;
    private AuthenticationManager manager;

    @BeforeEach
    void setUp() {
        SysUser account = new SysUser();
        account.setUserId(42L);
        account.setUserName("upgrade-test");
        account.setPassword(new BCryptPasswordEncoder(4).encode("correct-test-password"));
        user = new LoginUser(42L, 1L, account, Set.of("business:audit:list"));
        SecurityConfig config = new SecurityConfig();
        UserDetailsService details = name -> {
            if (!account.getUserName().equals(name)) throw new UsernameNotFoundException(name);
            return user;
        };
        ReflectionTestUtils.setField(config, "userDetailsService", details);
        manager = config.authenticationManager();
    }

    @Test
    void correctPasswordAuthenticatesWithoutGrantingAdditionalAuthorities() {
        Authentication result = manager.authenticate(
                new UsernamePasswordAuthenticationToken("upgrade-test", "correct-test-password"));
        assertTrue(result.isAuthenticated());
        assertSame(user, result.getPrincipal());
        assertNotNull(result.getAuthorities());
        assertEquals(Set.of(FactorGrantedAuthority.PASSWORD_AUTHORITY), result.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet()));
        assertTrue(user.getAuthorities().isEmpty());
        assertEquals(Set.of("business:audit:list"), user.getPermissions());
        Authentication cached = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        assertTrue(cached.getAuthorities().isEmpty());
        assertSame(user, cached.getPrincipal());
    }

    @Test
    void wrongPasswordRemainsRejected() {
        assertThrows(BadCredentialsException.class, () -> manager.authenticate(
                new UsernamePasswordAuthenticationToken("upgrade-test", "wrong-test-password")));
    }

    @Test
    void unknownUserRemainsRejected() {
        assertThrows(BadCredentialsException.class, () -> manager.authenticate(
                new UsernamePasswordAuthenticationToken("unknown-test", "correct-test-password")));
    }
}
