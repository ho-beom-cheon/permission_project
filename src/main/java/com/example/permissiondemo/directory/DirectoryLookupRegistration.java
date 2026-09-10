package com.example.permissiondemo.directory;

import com.example.permissiondemo.authorization.AuthorizationCatalog;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 기존 저장소에도 담당자 조회 기능 정의를 추가하되 사용자 권한은 자동 부여하지 않는다. */
@Component
public class DirectoryLookupRegistration {
    private final AuthorizationCatalog catalog;

    public DirectoryLookupRegistration(AuthorizationCatalog catalog) { this.catalog = catalog; }

    @EventListener(ApplicationReadyEvent.class)
    public void registerMissingAction() {
        var key = new AuthorizationCatalog.ProgramActionKey("SYSTEM_AUTH", "AUTHORITY", "DIRECTORY_LOOKUP");
        if (catalog.programActions().stream().noneMatch(action -> action.key().equals(key))) {
            catalog.saveAction(new AuthorizationCatalog.ProgramActionDefinition(
                    "SYSTEM_AUTH", "AUTHORITY", "DIRECTORY_LOOKUP", "업무담당자 조회", "btnDirectoryLookup", 30, true));
        }
    }
}
