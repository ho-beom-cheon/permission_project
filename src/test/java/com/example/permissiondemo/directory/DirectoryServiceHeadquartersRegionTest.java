package com.example.permissiondemo.directory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.AuthorizationCatalog;
import org.junit.jupiter.api.Test;

class DirectoryServiceHeadquartersRegionTest {
    @Test
    void office002CanViewAndAssignEveryActiveRegion() {
        var service = new DirectoryService(new AuthorizationCatalog(), new AuditEventService());
        service.saveOffice(new DirectoryService.Office("002", "본부", "", "", "", true));
        service.saveOffice(new DirectoryService.Office("101", "사업소", "", "", "", true));
        service.saveRegion(new DirectoryService.Region("R_ACTIVE", "101", "D1", "구", "활성 지역", true));
        service.saveRegion(new DirectoryService.Region("R_INACTIVE", "101", "D1", "구", "미사용 지역", false));

        service.savePerson(new DirectoryService.Person("hq002", "본부 사용자", "002", "", "RANK",
                "0212345678", "", "hq002@example.test", "업무", true, Set.of("R_ACTIVE")));

        assertThat(service.applicant("hq002").availableRegions())
                .extracting(DirectoryService.Region::code).containsExactly("R_ACTIVE");
        assertThat(service.updateApplicant("hq002", new DirectoryService.ApplicantUpdate("RANK", "0212345678",
                "", "hq002@example.test", "업무 변경", Set.of("R_ACTIVE"))).regionIds())
                .containsExactly("R_ACTIVE");
    }
}
