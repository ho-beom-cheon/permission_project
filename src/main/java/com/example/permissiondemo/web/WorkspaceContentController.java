package com.example.permissiondemo.web;

import java.util.List;
import com.example.permissiondemo.content.ContentPost;
import com.example.permissiondemo.content.WorkspaceContentService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import org.springframework.web.bind.annotation.*;

/** 실제 저장 콘텐츠 API. 기존 /api/content/* 권한 진단 예제와 경로를 구분한다. */
@RestController
@RequestMapping("/api/contents")
public class WorkspaceContentController {
    private final WorkspaceContentService service;
    public WorkspaceContentController(WorkspaceContentService service) { this.service = service; }
    @GetMapping
    public ApiResponse<PageResult<ContentPost>> search(@RequestParam(defaultValue = "title") String field,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.search(field, query, PageQuery.of(page, size)));
    }
    @GetMapping("/{id}")
    public ApiResponse<ContentPost> get(@PathVariable long id) { return ApiResponse.ok(service.get(id)); }
    @PostMapping
    public ApiResponse<ContentPost> create(@RequestBody WorkspaceContentService.WriteCommand command) {
        return ApiResponse.ok(service.save(null, command));
    }
    @PutMapping("/{id}")
    public ApiResponse<ContentPost> update(@PathVariable long id, @RequestBody WorkspaceContentService.WriteCommand command) {
        return ApiResponse.ok(service.save(id, command));
    }
    @PostMapping("/publish")
    public ApiResponse<List<ContentPost>> publish(@RequestBody PublishCommand command) {
        return ApiResponse.ok(service.publish(command.items()));
    }
    public record PublishCommand(List<WorkspaceContentService.VersionReference> items) {}
}
