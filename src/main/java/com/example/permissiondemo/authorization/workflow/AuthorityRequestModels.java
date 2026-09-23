package com.example.permissiondemo.authorization.workflow;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.example.permissiondemo.authorization.AuthorizationCatalog;

/** 권한 신청·승인 워크플로에서 저장소와 API가 공유하는 값 객체다. */
public final class AuthorityRequestModels {

    private AuthorityRequestModels() {
    }

    public enum RequestStatus {
        REQUESTED,
        APPROVED,
        REJECTED,
        CANCELLED
    }

    /** 신청 목록에서 외부에 노출하는 안정적인 정렬 필드다. */
    public enum RequestSort {
        REQUESTED_AT,
        USERNAME,
        AUTHORITY,
        STATUS,
        VALID_FROM
    }

    /** 승인 전 신청 정보와 결정 이력, 낙관적 잠금 버전을 함께 보존한다. */
    public record AuthorityRequest(
            long id,
            String requesterUsername,
            String targetUsername,
            String organizationId,
            String authorityId,
            AuthorizationCatalog.AssignmentType assignmentType,
            RequestStatus status,
            LocalDate validFrom,
            LocalDate validTo,
            String delegatedBy,
            String requestReason,
            String decisionReason,
            long version,
            LocalDateTime requestedAt,
            LocalDateTime decidedAt,
            String decidedBy) {
    }

    /** 신청 화면에서 받는 변경 가능한 값이다. 신청자·조직·상태는 서버가 결정한다. */
    public record SubmitCommand(
            String authorityId,
            AuthorizationCatalog.AssignmentType assignmentType,
            LocalDate validFrom,
            LocalDate validTo,
            String delegatedBy,
            String reason) {
    }
}
