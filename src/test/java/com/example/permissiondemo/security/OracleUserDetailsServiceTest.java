package com.example.permissiondemo.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class OracleUserDetailsServiceTest {

    @Test
    void loadsPasswordHashAndAccountStateFromOracleMapper() {
        LocalAccountMapper mapper = mock(LocalAccountMapper.class);
        when(mapper.selectByUsername("manager")).thenReturn(Optional.of(
                new LocalAccountMapper.LocalAccountRow(
                        "manager", "{bcrypt}$2a$10$hash", true)));

        var details = new OracleUserDetailsService(mapper).loadUserByUsername("manager");

        assertThat(details.getUsername()).isEqualTo("manager");
        assertThat(details.getPassword()).isEqualTo("{bcrypt}$2a$10$hash");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_AUTHENTICATED_USER");
    }

    @Test
    void hidesWhetherUnknownAccountExists() {
        LocalAccountMapper mapper = mock(LocalAccountMapper.class);
        when(mapper.selectByUsername("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                new OracleUserDetailsService(mapper).loadUserByUsername("missing"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage("계정을 찾을 수 없습니다.");
    }
}
