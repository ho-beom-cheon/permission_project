package com.example.permissiondemo.web;
import java.util.*;
import com.example.permissiondemo.content.HelpGuideService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.web.bind.annotation.*;

@RestController
@StateBoundary
@RequestMapping("/api/help")
public class HelpGuideController {
    private final HelpGuideService help;
    public HelpGuideController(HelpGuideService help){this.help=help;}
    @GetMapping public ApiResponse<PageResult<HelpGuideService.Guide>> list(@RequestParam(required=false)String menuId,@RequestParam(required=false)String query,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer size){return ApiResponse.ok(help.list(menuId,query,PageQuery.of(page,size)));}
    @GetMapping("/{id}") public ApiResponse<HelpGuideService.Guide> get(@PathVariable long id){return ApiResponse.ok(help.get(id));}
    @PostMapping public ApiResponse<HelpGuideService.Guide> create(@RequestBody HelpGuideService.WriteGuide value){return ApiResponse.ok(help.save(0,value));}
    @PutMapping("/{id}") public ApiResponse<HelpGuideService.Guide> update(@PathVariable long id,@RequestBody HelpGuideService.WriteGuide value){return ApiResponse.ok(help.save(id,value));}
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable long id,@RequestParam long version){help.delete(id,version);return ApiResponse.ok(null);}
    @GetMapping("/{id}/history") public ApiResponse<List<HelpGuideService.Guide>> history(@PathVariable long id){return ApiResponse.ok(help.history(id));}
    @PostMapping("/{id}/use") public ApiResponse<Map<String,Long>> recordUse(@PathVariable long id){return ApiResponse.ok(Map.of("count",help.recordUse(id)));}
    @GetMapping("/summary") public ApiResponse<Map<Long,Long>> summary(){return ApiResponse.ok(help.summary());}
}
