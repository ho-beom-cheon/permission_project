package com.example.permissiondemo.web;
import java.util.Map;
import com.example.permissiondemo.common.*;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.web.bind.annotation.*;

@RestController
@StateBoundary
@RequestMapping("/api/me/notices")
public class InboxController {
    private final InboxService inbox;
    public InboxController(InboxService inbox){this.inbox=inbox;}
    @GetMapping public ApiResponse<PageResult<InboxService.Notice>> list(@RequestParam(defaultValue="false")boolean unreadOnly,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer size){return ApiResponse.ok(inbox.list(unreadOnly,PageQuery.of(page,size)));}
    @GetMapping("/unread-count") public ApiResponse<Map<String,Long>> count(){return ApiResponse.ok(Map.of("count",inbox.unreadCount()));}
    @PostMapping("/{id}/read") public ApiResponse<InboxService.Notice> read(@PathVariable long id){return ApiResponse.ok(inbox.markRead(id));}
}
