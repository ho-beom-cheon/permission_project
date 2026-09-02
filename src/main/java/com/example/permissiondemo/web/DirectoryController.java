package com.example.permissiondemo.web;

import com.example.permissiondemo.directory.DirectoryService;
import com.example.permissiondemo.directory.LocalAccountService;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.storage.StateBoundary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 사용자·조직 관리와 사용자 자신의 정보/비밀번호 변경을 구분한다. */
@RestController
@StateBoundary
public class DirectoryController {
    private final DirectoryService directory;
    private final LocalAccountService accounts;
    private final CurrentUserContext current;
    public DirectoryController(DirectoryService directory, LocalAccountService accounts, CurrentUserContext current) {
        this.directory = directory; this.accounts = accounts; this.current = current;
    }
    @GetMapping("/api/admin/directory")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_READ')")
    public ApiResponse<DirectoryService.DirectoryView> view() { return ApiResponse.ok(directory.view()); }

    @PostMapping("/api/admin/directory/offices")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<DirectoryService.Office> office(@RequestBody DirectoryService.Office value) { return ApiResponse.ok(directory.saveOffice(value)); }
    @PostMapping("/api/admin/directory/departments")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<DirectoryService.Department> department(@RequestBody DirectoryService.Department value) { return ApiResponse.ok(directory.saveDepartment(value)); }
    @PostMapping("/api/admin/directory/regions")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<DirectoryService.Region> region(@RequestBody DirectoryService.Region value) { return ApiResponse.ok(directory.saveRegion(value)); }
    @PostMapping("/api/admin/directory/people")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<DirectoryService.Person> person(@Valid @RequestBody PersonRequest request) {
        var person = directory.savePerson(request.person());
        if (request.initialPassword() != null && !request.initialPassword().isBlank()) accounts.create(person.username(), request.initialPassword());
        return ApiResponse.ok(person);
    }
    @PostMapping("/api/admin/directory/jobs")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<DirectoryService.Job> job(@RequestBody DirectoryService.Job value) { return ApiResponse.ok(directory.saveJob(value)); }
    @DeleteMapping("/api/admin/directory/jobs/{id}")
    @PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_AUTH', 'AUTHORITY', 'AUTHORITY_UPDATE')")
    public ApiResponse<Void> deleteJob(@PathVariable String id) { directory.deleteJob(id); return ApiResponse.ok(null); }

    @GetMapping("/api/me/profile")
    public ApiResponse<DirectoryService.Person> mine() { return ApiResponse.ok(directory.person(current.require().username())); }
    @PostMapping("/api/me/profile")
    public ApiResponse<DirectoryService.Person> updateMine(@Valid @RequestBody MyProfileRequest request) {
        var old = directory.person(current.require().username());
        return ApiResponse.ok(directory.savePerson(new DirectoryService.Person(old.username(), old.name(), old.officeCode(), old.departmentCode(),
                old.rankCode(), request.telephone(), request.mobile(), request.email(), request.jobDescription(), old.active(), old.regionIds())));
    }
    @PostMapping("/api/me/password")
    public ApiResponse<Void> password(@Valid @RequestBody PasswordRequest request) {
        accounts.changePassword(current.require().username(), request.currentPassword(), request.newPassword()); return ApiResponse.ok(null);
    }
    public record PersonRequest(@NotNull DirectoryService.Person person, @Size(max=72) String initialPassword) { }
    public record MyProfileRequest(@Size(max=30) String telephone, @Size(max=30) String mobile, @Size(max=150) String email, @Size(max=1000) String jobDescription) { }
    public record PasswordRequest(@NotBlank @Size(max=72) String currentPassword, @NotBlank @Size(min=12,max=72) String newPassword) { }
}
