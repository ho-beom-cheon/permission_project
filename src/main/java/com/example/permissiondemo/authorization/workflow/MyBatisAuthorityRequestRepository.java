package com.example.permissiondemo.authorization.workflow;

import java.util.List;
import java.util.Optional;

import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** oracle 프로필에서 권한 신청 원장을 낙관적 잠금으로 저장한다. */
@Repository
@Profile("oracle")
public class MyBatisAuthorityRequestRepository implements AuthorityRequestRepository {

    private final AuthorityRequestMapper mapper;

    public MyBatisAuthorityRequestRepository(AuthorityRequestMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long nextId() {
        return mapper.nextId();
    }

    @Override
    public AuthorityRequestModels.AuthorityRequest insert(
            AuthorityRequestModels.AuthorityRequest request) {
        try {
            mapper.insert(request);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(ErrorCode.CONFLICT, Long.toString(request.id()));
        }
        return findById(request.id())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, Long.toString(request.id())));
    }

    @Override
    public Optional<AuthorityRequestModels.AuthorityRequest> findById(long requestId) {
        return Optional.ofNullable(mapper.selectById(requestId));
    }

    @Override
    public List<AuthorityRequestModels.AuthorityRequest> findAll() {
        return mapper.selectAll();
    }

    @Override
    public AuthorityRequestModels.AuthorityRequest updateDecision(
            AuthorityRequestModels.AuthorityRequest request,
            long expectedVersion) {
        if (mapper.updateDecision(request, expectedVersion) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, Long.toString(request.id()));
        }
        return findById(request.id())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, Long.toString(request.id())));
    }
}
