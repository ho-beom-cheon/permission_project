package com.example.permissiondemo.web;

import java.util.List;
import java.util.Map;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.ReferenceDataService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reference-data")
@StateBoundary
@PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_SAVE')")
public class ReferenceDataController {
    private final ReferenceDataService service;
    public ReferenceDataController(ReferenceDataService service) { this.service = service; }
    @GetMapping public ApiResponse<List<ReferenceDataService.Definition>> definitions() { return ApiResponse.ok(service.definitions()); }
    @GetMapping("/{module}") public ApiResponse<PageResult<ReferenceDataService.Entry>> list(@PathVariable String module,
            @RequestParam(required=false) String query, @RequestParam(required=false) Integer page, @RequestParam(required=false) Integer size) {
        return ApiResponse.ok(service.list(module, query, PageQuery.of(page, size)));
    }
    @PostMapping("/{module}") public ApiResponse<ReferenceDataService.Entry> save(@PathVariable String module, @RequestBody SaveRequest request) {
        return ApiResponse.ok(service.save(module, request.values(), request.expectedVersion()));
    }
    @DeleteMapping("/{module}/{id}") public ApiResponse<Void> delete(@PathVariable String module, @PathVariable String id, @RequestParam long version) {
        service.delete(module, id, version); return ApiResponse.ok(null);
    }
    @GetMapping("/{module}/export") public ResponseEntity<String> export(@PathVariable String module) {
        String csv = service.exportCsv(module);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reference.csv\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv);
    }
    public record SaveRequest(Map<String,String> values, long expectedVersion) { }
}
