package com.example.permissiondemo.authorization.workflow;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import com.example.permissiondemo.web.ApiException;
import com.example.permissiondemo.web.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** demo 프로필에서 권한 신청 상태 전이와 버전 충돌을 검증하는 저장소다. */
@Repository
@Profile("demo")
public class InMemoryAuthorityRequestRepository implements AuthorityRequestRepository {

    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<Long, AuthorityRequestModels.AuthorityRequest> requests =
            new ConcurrentHashMap<>();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public AuthorityRequestModels.AuthorityRequest insert(
            AuthorityRequestModels.AuthorityRequest request) {
        if (requests.putIfAbsent(request.id(), request) != null) {
            throw new ApiException(ErrorCode.CONFLICT, Long.toString(request.id()));
        }
        return request;
    }

    @Override
    public Optional<AuthorityRequestModels.AuthorityRequest> findById(long requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    @Override
    public List<AuthorityRequestModels.AuthorityRequest> findAll() {
        return requests.values().stream()
                .sorted(Comparator.comparing(
                                AuthorityRequestModels.AuthorityRequest::requestedAt)
                        .reversed()
                        .thenComparing(
                                AuthorityRequestModels.AuthorityRequest::id,
                                Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public synchronized AuthorityRequestModels.AuthorityRequest updateDecision(
            AuthorityRequestModels.AuthorityRequest request,
            long expectedVersion) {
        AuthorityRequestModels.AuthorityRequest current = requests.get(request.id());
        if (current == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, Long.toString(request.id()));
        }
        if (current.version() != expectedVersion) {
            throw new ApiException(ErrorCode.CONFLICT, Long.toString(request.id()));
        }
        requests.put(request.id(), request);
        return request;
    }
}
