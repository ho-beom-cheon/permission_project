package com.example.permissiondemo.content;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** demo에서도 HTTP 요청 간 데이터를 유지하며 전체 검증 후 한 번에 변경한다. */
@Repository
@Profile("demo")
public class InMemoryContentRepository implements ContentRepository {
    private final Map<Long, ContentPost> posts = new HashMap<>();
    private long sequence;
    public synchronized long nextId() { return ++sequence; }
    public synchronized Optional<ContentPost> findById(long id) { return Optional.ofNullable(posts.get(id)); }
    public synchronized PageResult<ContentPost> search(String field, String query, PageQuery page) {
        String keyword = query.toLowerCase(Locale.ROOT);
        return PageResult.of(posts.values().stream().filter(post -> {
            String value = switch (field) {
                case "author" -> post.author();
                case "status" -> post.status();
                default -> post.title();
            };
            return value.toLowerCase(Locale.ROOT).contains(keyword);
        }).sorted(Comparator.comparingLong(ContentPost::id).reversed()).toList(), page);
    }
    public synchronized void insert(ContentPost post) {
        if (posts.putIfAbsent(post.id(), post) != null) throw new ApiException(ErrorCode.CONFLICT);
    }
    public synchronized void updateAll(List<ContentPost> changes) {
        for (ContentPost post : changes) {
            ContentPost current = posts.get(post.id());
            if (current == null || current.version() != post.version() - 1) throw new ApiException(ErrorCode.CONFLICT);
        }
        changes.forEach(post -> posts.put(post.id(), post));
    }
}
