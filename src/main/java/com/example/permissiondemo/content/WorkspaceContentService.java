package com.example.permissiondemo.content;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.permissiondemo.audit.AuditEventService;
import com.example.permissiondemo.authorization.ProgramAuthorizationService;
import com.example.permissiondemo.common.CommonCodeService;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.security.CurrentUserContext;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공유 업무 콘텐츠를 기능 권한·공통코드·기대 버전으로 검증한 후 저장한다. */
@Service
public class WorkspaceContentService {
    private final ContentRepository repository;
    private final CommonCodeService codes;
    private final CurrentUserContext current;
    private final ProgramAuthorizationService authorization;
    private final AuditEventService audit;
    private final Clock clock;
    public WorkspaceContentService(ContentRepository repository, CommonCodeService codes, CurrentUserContext current,
            ProgramAuthorizationService authorization, AuditEventService audit, Clock clock) {
        this.repository = repository; this.codes = codes; this.current = current;
        this.authorization = authorization; this.audit = audit; this.clock = clock;
    }
    public PageResult<ContentPost> search(String field, String query, PageQuery page) {
        requireAction("CONTENT_READ");
        if (!Set.of("title", "author", "status").contains(field)) throw new IllegalArgumentException("검색 항목을 확인해 주세요.");
        String keyword = query == null ? "" : query.trim();
        if (keyword.length() > 100) throw new IllegalArgumentException("검색어는 100자 이내로 입력해 주세요.");
        return repository.search(field, keyword, page);
    }
    public ContentPost get(long id) { requireAction("CONTENT_READ"); return requirePost(id); }

    @Transactional
    public synchronized ContentPost save(Long id, WriteCommand command) {
        requireAction("CONTENT_SAVE");
        String type = text(command.type(), 20, "구분");
        if (!Set.of("업무", "공지", "자료", "소식").contains(type)) throw new IllegalArgumentException("구분을 확인해 주세요.");
        String title = text(command.title(), 100, "제목");
        String body = text(command.body(), 10000, "본문");
        String status = text(command.status(), 50, "상태");
        requireStatus(status);
        ContentPost previous = id == null ? null : requirePost(id);
        if (previous != null && previous.version() != command.version()) throw new ApiException(ErrorCode.CONFLICT);
        if (previous == null && command.version() != 0) throw new ApiException(ErrorCode.CONFLICT);
        // 저장 API로 게시하거나 게시 중인 내용을 바꾸는 경우도 게시 권한을 요구한다.
        if ("PUBLISHED".equals(status) || previous != null && "PUBLISHED".equals(previous.status())) requireAction("CONTENT_PUBLISH");
        Instant now = clock.instant();
        ContentPost post = new ContentPost(previous == null ? repository.nextId() : id, type, title, body, status,
                previous == null ? current.require().username() : previous.author(),
                previous == null ? now : previous.createdAt(), now, previous == null ? 1 : previous.version() + 1);
        if (previous == null) repository.insert(post); else repository.updateAll(List.of(post));
        audit.record(previous == null ? "CONTENT_CREATED" : "CONTENT_UPDATED", "CONTENT", Long.toString(post.id()),
                "SUCCESS", Map.of("status", status, "version", post.version()));
        return post;
    }

    @Transactional
    public synchronized List<ContentPost> publish(List<VersionReference> items) {
        requireAction("CONTENT_PUBLISH");
        requireStatus("PUBLISHED");
        if (items == null || items.isEmpty() || items.size() > 50) throw new IllegalArgumentException("게시 대상을 1~50건 선택해 주세요.");
        Set<Long> ids = new HashSet<>();
        Instant now = clock.instant();
        List<ContentPost> changed = items.stream().map(item -> {
            if (item == null || !ids.add(item.id())) throw new IllegalArgumentException("중복되거나 잘못된 게시 대상입니다.");
            ContentPost post = requirePost(item.id());
            if (post.version() != item.version()) throw new ApiException(ErrorCode.CONFLICT);
            return new ContentPost(post.id(), post.type(), post.title(), post.body(), "PUBLISHED", post.author(),
                    post.createdAt(), now, post.version() + 1);
        }).toList();
        repository.updateAll(changed);
        changed.forEach(post -> audit.record("CONTENT_PUBLISHED", "CONTENT", Long.toString(post.id()), "SUCCESS",
                Map.of("version", post.version())));
        return changed;
    }
    private ContentPost requirePost(long id) {
        return repository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    private void requireAction(String action) {
        if (!authorization.isAllowed(current.authentication(), "CONTENT_LIST", "CONTENT", action)) throw new ApiException(ErrorCode.ACCESS_DENIED);
    }
    private void requireStatus(String status) {
        if (codes.findActiveItems("ARTICLE_STATUS").stream().noneMatch(item -> item.code().equals(status))) throw new ApiException(ErrorCode.CONFLICT);
    }
    private String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new IllegalArgumentException(label + "은(는) 1~" + max + "자로 입력해 주세요.");
        return value.trim();
    }
    public record WriteCommand(String type, String title, String body, String status, long version) {}
    public record VersionReference(long id, long version) {}
}
