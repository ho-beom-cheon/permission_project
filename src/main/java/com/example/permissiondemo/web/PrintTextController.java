package com.example.permissiondemo.web;

import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.PrintTextService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@StateBoundary
@RequestMapping("/api/admin/print-texts")
@PreAuthorize("@programAuthorizationService.isAllowed(authentication, 'SYSTEM_CODE', 'COMMON_CODE', 'COMMON_CODE_SAVE')")
public class PrintTextController {
    private final PrintTextService service;
    public PrintTextController(PrintTextService service){this.service=service;}
    @GetMapping("/options") public ApiResponse<PrintTextService.Options> options(){return ApiResponse.ok(service.options());}
    @GetMapping public ApiResponse<PageResult<PrintTextService.Group>> list(@RequestParam(required=false)String officeCd,
            @RequestParam(required=false)String jobSeCd,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer size){
        return ApiResponse.ok(service.list(officeCd,jobSeCd,PageQuery.of(page,size)));
    }
    @GetMapping("/{id}") public ApiResponse<PrintTextService.Group> get(@PathVariable String id){return ApiResponse.ok(service.get(id));}
    @PostMapping public ApiResponse<PrintTextService.Group> save(@RequestBody PrintTextService.WriteGroup value){return ApiResponse.ok(service.save(value));}
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable String id,@RequestParam long version){service.delete(id,version);return ApiResponse.ok(null);}
}
