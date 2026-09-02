package com.example.permissiondemo.web;
import java.util.List;
import com.example.permissiondemo.security.SessionMonitorService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/sessions")
public class SessionMonitorController {
    private final SessionMonitorService sessions;
    public SessionMonitorController(SessionMonitorService sessions){this.sessions=sessions;}
    @GetMapping public ApiResponse<List<SessionMonitorService.SessionView>> list(){return ApiResponse.ok(sessions.list());}
    @PostMapping("/{reference}/expire") public ApiResponse<Void> expire(@PathVariable String reference){sessions.expire(reference);return ApiResponse.ok(null);}
}
