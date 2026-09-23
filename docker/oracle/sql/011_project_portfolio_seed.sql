-- 운영 화면에서 상태·역할·보관·이력 시나리오를 확인할 수 있는 프로젝트 포트폴리오 시드.
-- 기존 3개 프로젝트는 변경하지 않고 9개를 추가하며, 모든 MERGE는 반복 실행해도 중복되지 않는다.

WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET SQLBLANKLINES ON

MERGE INTO PM_PROJECT target
USING (
    SELECT 'PRJ_AUDIT_AUTOMATION' PROJECT_ID, '감사 증적 자동화' PROJECT_NAME,
           '권한 변경과 접근 기록의 정기 점검 자동화' DESCRIPTION,
           'COMPLETED' PROJECT_STATUS, DATE '2026-01-15' START_DATE,
           DATE '2026-07-31' END_DATE, 'manager' OWNER_USERNAME,
           'Y' ACTIVE_YN, 3 VERSION_NO FROM DUAL
    UNION ALL
    SELECT 'PRJ_CUSTOMER_INSIGHT', '고객 통합 분석',
           '채널별 고객 활동 지표와 분석 대시보드 구축',
           'ACTIVE', DATE '2026-07-01', DATE '2027-01-31', 'admin', 'Y', 2 FROM DUAL
    UNION ALL
    SELECT 'PRJ_LEGACY_MIGRATION', '레거시 자료 이관',
           '기존 시스템 자료 이관이 완료된 보관 프로젝트',
           'COMPLETED', DATE '2025-09-01', DATE '2026-04-30', 'manager', 'N', 3 FROM DUAL
    UNION ALL
    SELECT 'PRJ_ZERO_TRUST', '제로 트러스트 전환',
           '관리 시스템 접근 경로와 단계별 인증 정책 재설계',
           'PLANNED', DATE '2026-11-01', DATE '2027-04-30', 'authorityManager', 'Y', 1 FROM DUAL
    UNION ALL
    SELECT 'PRJ_PROGRAM_CATALOG', '업무 프로그램 카탈로그 정비',
           '메뉴·프로그램·기능 원장의 중복 제거와 책임자 정비',
           'ACTIVE', DATE '2026-05-15', DATE '2026-10-31', 'programManager', 'Y', 2 FROM DUAL
    UNION ALL
    SELECT 'PRJ_PROJECT_PORTFOLIO', '전사 프로젝트 포트폴리오',
           '프로젝트 일정·책임자·참여 현황을 한 화면으로 통합',
           'ACTIVE', DATE '2026-07-15', DATE '2026-12-20', 'projectManager', 'Y', 2 FROM DUAL
    UNION ALL
    SELECT 'PRJ_CODE_GOVERNANCE', '공통코드 거버넌스',
           '코드 소유 부서와 변경 승인 기준을 표준화',
           'ON_HOLD', DATE '2026-04-01', DATE '2026-09-30', 'codeManager', 'Y', 3 FROM DUAL
    UNION ALL
    SELECT 'PRJ_REGIONAL_ROLLOUT', '지역 운영 표준 확산',
           '서울 운영 모델을 지역 조직에 단계적으로 적용',
           'PLANNED', DATE '2027-01-05', DATE '2027-06-30', 'seoulOperator', 'Y', 1 FROM DUAL
    UNION ALL
    SELECT 'PRJ_DISASTER_RECOVERY', '업무 연속성 복구 체계',
           '장애 대응 절차와 복구 훈련을 완료한 보관 프로젝트',
           'COMPLETED', DATE '2025-11-01', DATE '2026-03-31', 'backupAdmin', 'N', 4 FROM DUAL
) source
ON (target.PROJECT_ID = source.PROJECT_ID)
WHEN NOT MATCHED THEN INSERT (
    PROJECT_ID,
    PROJECT_NAME,
    DESCRIPTION,
    PROJECT_STATUS,
    START_DATE,
    END_DATE,
    OWNER_USERNAME,
    ACTIVE_YN,
    VERSION_NO,
    CREATED_BY,
    UPDATED_BY
) VALUES (
    source.PROJECT_ID,
    source.PROJECT_NAME,
    source.DESCRIPTION,
    source.PROJECT_STATUS,
    source.START_DATE,
    source.END_DATE,
    source.OWNER_USERNAME,
    source.ACTIVE_YN,
    source.VERSION_NO,
    'SYSTEM',
    'SYSTEM'
);

MERGE INTO PM_PROJECT_MEMBER target
USING (
    SELECT 'PRJ_ACCESS_RENEWAL' PROJECT_ID, 'admin' USERNAME, 'OWNER' PROJECT_ROLE FROM DUAL
    UNION ALL SELECT 'PRJ_ACCESS_RENEWAL', 'manager', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_ACCESS_RENEWAL', 'authorityManager', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_ACCESS_RENEWAL', 'auditor', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_DATA_HUB', 'manager', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_DATA_HUB', 'codeManager', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_DATA_HUB', 'contentEditor', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_DATA_HUB', 'busanViewer', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_MOBILE_PORTAL', 'admin', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_MOBILE_PORTAL', 'programManager', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_MOBILE_PORTAL', 'delegate', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_MOBILE_PORTAL', 'busanViewer', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_AUDIT_AUTOMATION', 'manager', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_AUDIT_AUTOMATION', 'auditor', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_AUDIT_AUTOMATION', 'delegate', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_AUDIT_AUTOMATION', 'viewer', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_CUSTOMER_INSIGHT', 'admin', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_CUSTOMER_INSIGHT', 'manager', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_CUSTOMER_INSIGHT', 'delegate', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_CUSTOMER_INSIGHT', 'busanViewer', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_LEGACY_MIGRATION', 'manager', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_LEGACY_MIGRATION', 'backupAdmin', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_LEGACY_MIGRATION', 'contentEditor', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_LEGACY_MIGRATION', 'auditor', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_ZERO_TRUST', 'authorityManager', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_ZERO_TRUST', 'admin', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_ZERO_TRUST', 'auditor', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_ZERO_TRUST', 'viewer', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_PROGRAM_CATALOG', 'programManager', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_PROGRAM_CATALOG', 'contentEditor', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_PROGRAM_CATALOG', 'codeManager', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_PROGRAM_CATALOG', 'auditor', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_PROJECT_PORTFOLIO', 'projectManager', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_PROJECT_PORTFOLIO', 'seoulOperator', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_PROJECT_PORTFOLIO', 'programManager', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_PROJECT_PORTFOLIO', 'busanViewer', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_CODE_GOVERNANCE', 'codeManager', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_CODE_GOVERNANCE', 'programManager', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_CODE_GOVERNANCE', 'authorityManager', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_CODE_GOVERNANCE', 'auditor', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_REGIONAL_ROLLOUT', 'seoulOperator', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_REGIONAL_ROLLOUT', 'projectManager', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_REGIONAL_ROLLOUT', 'contentEditor', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_REGIONAL_ROLLOUT', 'busanViewer', 'VIEWER' FROM DUAL

    UNION ALL SELECT 'PRJ_DISASTER_RECOVERY', 'backupAdmin', 'OWNER' FROM DUAL
    UNION ALL SELECT 'PRJ_DISASTER_RECOVERY', 'admin', 'MANAGER' FROM DUAL
    UNION ALL SELECT 'PRJ_DISASTER_RECOVERY', 'codeManager', 'MEMBER' FROM DUAL
    UNION ALL SELECT 'PRJ_DISASTER_RECOVERY', 'auditor', 'VIEWER' FROM DUAL
) source
ON (target.PROJECT_ID = source.PROJECT_ID AND target.USERNAME = source.USERNAME)
WHEN NOT MATCHED THEN INSERT (
    PROJECT_ID,
    USERNAME,
    PROJECT_ROLE,
    ACTIVE_YN,
    CREATED_BY,
    UPDATED_BY
) VALUES (
    source.PROJECT_ID,
    source.USERNAME,
    source.PROJECT_ROLE,
    'Y',
    'SYSTEM',
    'SYSTEM'
);

MERGE INTO PM_PROJECT_STATUS_HISTORY target
USING (
    SELECT 'PRJ_AUDIT_AUTOMATION' PROJECT_ID, 1 SEQUENCE_NO,
           CAST(NULL AS VARCHAR2(20)) FROM_STATUS, 'PLANNED' TO_STATUS,
           'manager' CHANGED_BY, '사업 범위와 일정 계획 승인' CHANGE_REASON,
           TO_TIMESTAMP_TZ('2026-01-01 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') CHANGED_AT FROM DUAL
    UNION ALL
    SELECT 'PRJ_AUDIT_AUTOMATION', 2, 'PLANNED', 'ACTIVE', 'manager',
           '감사 통제 항목 확정 후 개발 착수',
           TO_TIMESTAMP_TZ('2026-01-15 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_AUDIT_AUTOMATION', 3, 'ACTIVE', 'COMPLETED', 'auditor',
           '정기 점검 자동화 운영 이관 완료',
           TO_TIMESTAMP_TZ('2026-07-31 18:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL

    UNION ALL
    SELECT 'PRJ_CUSTOMER_INSIGHT', 1, CAST(NULL AS VARCHAR2(20)), 'PLANNED', 'admin',
           '고객 분석 범위와 추진 일정 승인',
           TO_TIMESTAMP_TZ('2026-06-17 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_CUSTOMER_INSIGHT', 2, 'PLANNED', 'ACTIVE', 'admin',
           '분석 범위와 지표 정의 확정',
           TO_TIMESTAMP_TZ('2026-07-01 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL

    UNION ALL
    SELECT 'PRJ_LEGACY_MIGRATION', 1, CAST(NULL AS VARCHAR2(20)), 'PLANNED', 'manager',
           '자료 이관 범위와 보존 정책 승인',
           TO_TIMESTAMP_TZ('2025-08-18 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_LEGACY_MIGRATION', 2, 'PLANNED', 'ACTIVE', 'manager',
           '이관 대상과 검증 기준 확정',
           TO_TIMESTAMP_TZ('2025-09-01 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_LEGACY_MIGRATION', 3, 'ACTIVE', 'COMPLETED', 'manager',
           '데이터 대사와 사용자 검증 완료',
           TO_TIMESTAMP_TZ('2026-04-30 18:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL

    UNION ALL
    SELECT 'PRJ_ZERO_TRUST', 1, CAST(NULL AS VARCHAR2(20)), 'PLANNED', 'authorityManager',
           '접근 통제 진단과 단계별 전환 계획 승인',
           TO_TIMESTAMP_TZ('2026-10-18 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL

    UNION ALL
    SELECT 'PRJ_PROGRAM_CATALOG', 1, CAST(NULL AS VARCHAR2(20)), 'PLANNED', 'programManager',
           '프로그램 원장 정비 범위 승인',
           TO_TIMESTAMP_TZ('2026-05-01 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_PROGRAM_CATALOG', 2, 'PLANNED', 'ACTIVE', 'programManager',
           '중복 기능 식별과 책임 부서 협의 완료',
           TO_TIMESTAMP_TZ('2026-05-15 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL

    UNION ALL
    SELECT 'PRJ_PROJECT_PORTFOLIO', 1, CAST(NULL AS VARCHAR2(20)), 'PLANNED', 'projectManager',
           '전사 프로젝트 현황 통합 계획 승인',
           TO_TIMESTAMP_TZ('2026-07-01 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_PROJECT_PORTFOLIO', 2, 'PLANNED', 'ACTIVE', 'projectManager',
           '포트폴리오 운영 지표 확정',
           TO_TIMESTAMP_TZ('2026-07-15 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL

    UNION ALL
    SELECT 'PRJ_CODE_GOVERNANCE', 1, CAST(NULL AS VARCHAR2(20)), 'PLANNED', 'codeManager',
           '공통코드 표준화 추진 계획 승인',
           TO_TIMESTAMP_TZ('2026-03-18 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_CODE_GOVERNANCE', 2, 'PLANNED', 'ACTIVE', 'codeManager',
           '공통코드 현황 조사 완료',
           TO_TIMESTAMP_TZ('2026-04-01 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_CODE_GOVERNANCE', 3, 'ACTIVE', 'ON_HOLD', 'codeManager',
           '부서별 코드 소유권 조정 대기',
           TO_TIMESTAMP_TZ('2026-07-20 14:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL

    UNION ALL
    SELECT 'PRJ_REGIONAL_ROLLOUT', 1, CAST(NULL AS VARCHAR2(20)), 'PLANNED', 'seoulOperator',
           '지역별 적용 순서와 지원 인력 계획 승인',
           TO_TIMESTAMP_TZ('2026-12-22 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL

    UNION ALL
    SELECT 'PRJ_DISASTER_RECOVERY', 1, CAST(NULL AS VARCHAR2(20)), 'PLANNED', 'backupAdmin',
           '업무 연속성 복구 체계 구축 계획 승인',
           TO_TIMESTAMP_TZ('2025-10-18 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_DISASTER_RECOVERY', 2, 'PLANNED', 'ACTIVE', 'backupAdmin',
           '복구 목표와 모의훈련 범위 확정',
           TO_TIMESTAMP_TZ('2025-11-01 09:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
    UNION ALL
    SELECT 'PRJ_DISASTER_RECOVERY', 3, 'ACTIVE', 'COMPLETED', 'backupAdmin',
           '복구 훈련과 개선 조치 검증 완료',
           TO_TIMESTAMP_TZ('2026-03-31 18:00:00 +09:00',
                           'YYYY-MM-DD HH24:MI:SS TZH:TZM') FROM DUAL
) source
ON (target.PROJECT_ID = source.PROJECT_ID AND target.SEQUENCE_NO = source.SEQUENCE_NO)
WHEN NOT MATCHED THEN INSERT (
    PROJECT_ID,
    SEQUENCE_NO,
    FROM_STATUS,
    TO_STATUS,
    CHANGED_BY,
    CHANGE_REASON,
    CHANGED_AT
) VALUES (
    source.PROJECT_ID,
    source.SEQUENCE_NO,
    source.FROM_STATUS,
    source.TO_STATUS,
    source.CHANGED_BY,
    source.CHANGE_REASON,
    source.CHANGED_AT
);

COMMIT;
