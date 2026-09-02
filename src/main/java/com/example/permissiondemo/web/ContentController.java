package com.example.permissiondemo.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.example.permissiondemo.content.ContentService;
import com.example.permissiondemo.content.AttachmentRepository;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@StateBoundary
@RequestMapping("/api")
public class ContentController {
    private final ContentService service;
    public ContentController(ContentService service){this.service=service;}
    @GetMapping("/notice-feed") public ApiResponse<PageResult<ContentService.Post>> noticeFeed(@RequestParam(required=false)String query,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer size){return ApiResponse.ok(service.noticeFeed(query,PageQuery.of(page,size)));}
    @GetMapping("/boards/{board}") public ApiResponse<PageResult<ContentService.Post>> list(@PathVariable String board,@RequestParam(required=false)String query,@RequestParam(required=false)Integer page,@RequestParam(required=false)Integer size){return ApiResponse.ok(service.list(board,query,PageQuery.of(page,size)));}
    @GetMapping("/posts/{id}/views") public ApiResponse<Long> views(@PathVariable long id){return ApiResponse.ok(service.views(id));}
    @PostMapping("/posts/{id}/views") public ApiResponse<Long> recordView(@PathVariable long id){return ApiResponse.ok(service.recordView(id));}
    @GetMapping("/posts/{id}") public ApiResponse<ContentService.Post> get(@PathVariable long id){return ApiResponse.ok(service.get(id));}
    @PostMapping("/posts") public ApiResponse<ContentService.Post> create(@RequestBody ContentService.WritePost value){return ApiResponse.ok(service.save(0,value));}
    @PutMapping("/posts/{id}") public ApiResponse<ContentService.Post> update(@PathVariable long id,@RequestBody ContentService.WritePost value){return ApiResponse.ok(service.save(id,value));}
    @PostMapping("/posts/{id}/answer") public ApiResponse<ContentService.Post> answer(@PathVariable long id,@RequestBody AnswerRequest value){return ApiResponse.ok(service.answer(id,value.answer(),value.version()));}
    @DeleteMapping("/posts/{id}") public ApiResponse<Void> delete(@PathVariable long id,@RequestParam long version){service.delete(id,version);return ApiResponse.ok(null);}
    @PostMapping("/attachments") public ApiResponse<AttachmentRepository.Metadata> upload(@RequestParam MultipartFile file)throws IOException{return ApiResponse.ok(service.upload(file.getOriginalFilename(),file.getBytes()));}
    @GetMapping("/attachments/{id}") public ResponseEntity<byte[]> download(@PathVariable String id){
        var file=service.download(id);return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.metadata().name(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").body(file.data());
    }
    @DeleteMapping("/attachments/{id}") public ApiResponse<Void> deleteFile(@PathVariable String id){service.deleteUnlinkedUpload(id);return ApiResponse.ok(null);}
    @PostMapping("/terms/{id}/agreement") public ApiResponse<ContentService.Agreement> agree(@PathVariable long id){return ApiResponse.ok(service.agree(id));}
    @GetMapping("/me/agreements") public ApiResponse<List<ContentService.Agreement>> agreements(){return ApiResponse.ok(service.myAgreements());}
    public record AnswerRequest(String answer,long version) { }
}
