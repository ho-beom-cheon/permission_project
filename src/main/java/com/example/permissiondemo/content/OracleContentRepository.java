package com.example.permissiondemo.content;

import java.util.List;
import java.util.Optional;
import com.example.permissiondemo.common.PageQuery;
import com.example.permissiondemo.common.PageResult;
import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("oracle")
public class OracleContentRepository implements ContentRepository {
    private final ContentMapper mapper;
    public OracleContentRepository(ContentMapper mapper) { this.mapper = mapper; }
    public long nextId() { return mapper.nextId(); }
    public Optional<ContentPost> findById(long id) { return Optional.ofNullable(mapper.selectById(id)); }
    public PageResult<ContentPost> search(String field, String query, PageQuery page) {
        String pattern = "%" + query.toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        long count = mapper.count(field, pattern);
        return new PageResult<>(mapper.search(field, pattern, (long) page.page() * page.size(), page.size()),
                page.page(), page.size(), count, (int) Math.ceil((double) count / page.size()));
    }
    public void insert(ContentPost post) { mapper.insert(post); }
    @Transactional
    public void updateAll(List<ContentPost> changes) {
        // ID 순서로 잠금을 획득해 동시 일괄 게시의 교착 가능성을 줄인다.
        for (ContentPost post : changes.stream().sorted(java.util.Comparator.comparingLong(ContentPost::id)).toList()) {
            if (mapper.update(post) != 1) throw new ApiException(ErrorCode.CONFLICT);
        }
    }
}
