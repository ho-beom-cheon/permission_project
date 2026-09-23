package com.example.permissiondemo.authorization.workflow;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Oracle 권한 신청 SQL을 XML로 분리하는 MyBatis Mapper 계약이다. */
@Mapper
public interface AuthorityRequestMapper {

    long nextId();

    int insert(@Param("request") AuthorityRequestModels.AuthorityRequest request);

    AuthorityRequestModels.AuthorityRequest selectById(@Param("requestId") long requestId);

    List<AuthorityRequestModels.AuthorityRequest> selectAll();

    int updateDecision(
            @Param("request") AuthorityRequestModels.AuthorityRequest request,
            @Param("expectedVersion") long expectedVersion);
}
