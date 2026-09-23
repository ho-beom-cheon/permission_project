package com.example.permissiondemo.authorization.workflow;

import java.util.List;
import java.util.Optional;

/** 권한 신청 원장의 프로필별 저장 계약이다. */
public interface AuthorityRequestRepository {

    long nextId();

    AuthorityRequestModels.AuthorityRequest insert(AuthorityRequestModels.AuthorityRequest request);

    Optional<AuthorityRequestModels.AuthorityRequest> findById(long requestId);

    List<AuthorityRequestModels.AuthorityRequest> findAll();

    AuthorityRequestModels.AuthorityRequest updateDecision(
            AuthorityRequestModels.AuthorityRequest request,
            long expectedVersion);
}
