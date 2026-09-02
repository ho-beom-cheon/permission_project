package com.example.permissiondemo.web;
import java.util.List;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.common.ReferenceDataService.Definition;
import com.example.permissiondemo.operations.OperationWorkService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.web.bind.annotation.*;

@RestController
@StateBoundary
@RequestMapping("/api/admin/operations")
public class OperationWorkController {
    private final OperationWorkService service;
    public OperationWorkController(OperationWorkService service){this.service=service;}
    @GetMapping("/definitions") public ApiResponse<List<Definition>> definitions(){return ApiResponse.ok(service.definitions());}
    @GetMapping public ApiResponse<PageResult<OperationWorkService.Work>> list(@RequestParam String type,@RequestParam(required=false)String query,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer size){return ApiResponse.ok(service.list(type,query,PageQuery.of(page,size)));}
    @PostMapping public ApiResponse<OperationWorkService.Work> create(@RequestBody OperationWorkService.WriteWork value){return ApiResponse.ok(service.save(0,value));}
    @PutMapping("/{id}") public ApiResponse<OperationWorkService.Work> update(@PathVariable long id,@RequestBody OperationWorkService.WriteWork value){return ApiResponse.ok(service.save(id,value));}
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable long id,@RequestParam long version){service.delete(id,version);return ApiResponse.ok(null);}
    @GetMapping("/{id}/history") public ApiResponse<List<OperationWorkService.Change>> history(@PathVariable long id){return ApiResponse.ok(service.history(id));}
}
