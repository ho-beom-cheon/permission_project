package com.example.permissiondemo.directory;

import java.util.LinkedHashMap;
import java.util.Map;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import com.example.permissiondemo.storage.StateBoundary;
import com.example.permissiondemo.storage.StateParticipant;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 신규 로컬 사용자의 해시된 인증 정보를 저장한다. API 응답에는 비밀번호/해시를 포함하지 않는다. */
@Service
@StateBoundary
public class LocalAccountService implements UserDetailsService, StateParticipant {
    private final Map<String, String> passwords = new LinkedHashMap<>();
    private final AuthorizationCatalog catalog;
    private final PasswordEncoder encoder;
    private final AuditEventService audit;
    public LocalAccountService(AuthorizationCatalog catalog, PasswordEncoder encoder, AuditEventService audit) {
        this.catalog = catalog; this.encoder = encoder; this.audit = audit;
        for (String name : new String[]{"admin", "manager", "viewer", "delegate", "expired"}) {
            passwords.put(name, encoder.encode(name + "123!"));
        }
    }
    @Override public UserDetails loadUserByUsername(String username) {
        var profile = catalog.findUser(username).orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
        String password = passwords.get(username);
        if (password == null) throw new UsernameNotFoundException("로컬 로그인 계정이 없습니다.");
        return User.withUsername(username).password(password).roles("AUTHENTICATED_USER").disabled(!profile.active()).build();
    }
    public void create(String username, String password) {
        if (passwords.containsKey(username)) throw new ApiException(ErrorCode.CONFLICT, username);
        if (catalog.findUser(username).isEmpty()) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, username);
        validatePassword(password);
        passwords.put(username, encoder.encode(password));
        audit.record("LOCAL_ACCOUNT_CREATED", "USER", username, "SUCCESS", Map.of());
    }
    public void changePassword(String username, String currentPassword, String newPassword) {
        String stored = passwords.get(username);
        if (stored == null || currentPassword == null || !encoder.matches(currentPassword, stored)) throw new ApiException(ErrorCode.ACCESS_DENIED);
        validatePassword(newPassword);
        passwords.put(username, encoder.encode(newPassword));
        audit.record("PASSWORD_CHANGED", "USER", username, "SUCCESS", Map.of());
    }
    private void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("비밀번호는 12자 이상, UTF-8 기준 72바이트 이하여야 합니다.");
    }
    @Override public String stateKey() { return "local-accounts"; }
    @Override public Class<?> stateType() { return StoredState.class; }
    @Override public Object snapshotState() { return new StoredState(Map.copyOf(passwords)); }
    @Override public void restoreState(Object raw) { passwords.clear(); passwords.putAll(((StoredState) raw).passwords()); }
    public record StoredState(Map<String, String> passwords) { }
}
