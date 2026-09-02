package com.example.permissiondemo.web;

import java.util.List;
import com.example.permissiondemo.content.BoardService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.web.bind.annotation.*;

@RestController
@StateBoundary
@RequestMapping("/api/boards")
public class BoardController {
    private final BoardService boards;
    public BoardController(BoardService boards){this.boards=boards;}
    @GetMapping public ApiResponse<List<BoardService.Definition>> list(){return ApiResponse.ok(boards.list());}
    @PostMapping public ApiResponse<BoardService.Definition> save(@RequestBody BoardService.WriteDefinition value){return ApiResponse.ok(boards.save(value));}
    @DeleteMapping("/{id}") public ApiResponse<Void> deactivate(@PathVariable String id,@RequestParam long version){boards.deactivate(id,version);return ApiResponse.ok(null);}
}
