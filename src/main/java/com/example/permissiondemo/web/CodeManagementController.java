package com.example.permissiondemo.web;

import java.util.List;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@StateBoundary
@RequestMapping("/api/admin/codes")
@PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_SAVE')")
public class CodeManagementController {
    private final CommonCodeService codes;
    public CodeManagementController(CommonCodeService codes) { this.codes = codes; }
    @GetMapping public ApiResponse<List<CommonCodeService.CodeGroup>> groups() { return ApiResponse.ok(codes.definitions()); }
    @PostMapping public ApiResponse<CommonCodeService.CodeGroup> saveGroup(@RequestBody GroupRequest value) { return ApiResponse.ok(codes.saveGroup(value.group(), value.version())); }
    @DeleteMapping("/{group}") public ApiResponse<Void> deleteGroup(@PathVariable String group, @RequestParam long version) { codes.deleteGroup(group, version); return ApiResponse.ok(null); }
    @GetMapping("/{group}") public ApiResponse<CommonCodeService.CodeGroupView> items(@PathVariable String group) { return ApiResponse.ok(codes.findGroupView(group, false, null)); }
    @PostMapping("/{group}") public ApiResponse<CommonCodeService.CommonCodeItem> saveItem(@PathVariable String group, @RequestBody ItemRequest value) { return ApiResponse.ok(codes.saveRich(group, value.item(), value.version())); }
    @DeleteMapping("/{group}/{code}") public ApiResponse<Void> deleteItem(@PathVariable String group, @PathVariable String code, @RequestParam(required=false) String parentCode, @RequestParam long version) { codes.deleteItem(group, code, parentCode, version); return ApiResponse.ok(null); }
    public record GroupRequest(CommonCodeService.CodeGroup group, long version) { }
    public record ItemRequest(CommonCodeService.CommonCodeItem item, long version) { }
}
