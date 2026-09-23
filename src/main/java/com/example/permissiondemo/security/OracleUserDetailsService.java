package com.example.permissiondemo.security;

import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** SSO 없이 단일 서버로 운영할 때 Oracle의 암호 해시 계정을 사용하는 인증 서비스다. */
@Service
@Profile("oracle")
public class OracleUserDetailsService implements UserDetailsService {

    private final LocalAccountMapper mapper;

    public OracleUserDetailsService(LocalAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        LocalAccountMapper.LocalAccountRow account = mapper.selectByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("계정을 찾을 수 없습니다."));
        return User.withUsername(account.username())
                .password(account.passwordHash())
                .disabled(!account.enabled())
                .roles("AUTHENTICATED_USER")
                .build();
    }
}
