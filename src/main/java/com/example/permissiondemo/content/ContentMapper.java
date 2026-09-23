package com.example.permissiondemo.content;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContentMapper {
    long nextId();
    ContentPost selectById(@Param("id") long id);
    long count(@Param("field") String field, @Param("query") String query);
    List<ContentPost> search(@Param("field") String field, @Param("query") String query,
            @Param("offset") long offset, @Param("size") int size);
    int insert(@Param("post") ContentPost post);
    int update(@Param("post") ContentPost post);
}
