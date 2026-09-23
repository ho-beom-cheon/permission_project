# 통합 관리 시스템 개발자 매뉴얼

## 1. 목표와 경계

이 애플리케이션은 권한관리, 프로그램관리, 프로젝트관리, 공통코드관리를 핵심 도메인으로 구현한 Java 17·Spring Boot 3.5.7 단일 서버입니다. 인증은 Spring Security 폼 로그인과 서버 세션을 사용합니다. 외부 통합 로그인, 분산 세션, Redis와 다중 서버 캐시 동기화는 현재 구현 범위가 아닙니다.

서버는 정확히 하나의 실행 프로필을 사용합니다.

- `demo`: DB 없는 인메모리 실행과 빠른 기능 검증
- `oracle`: Oracle·MyBatis 영속화와 DB 로컬 계정 인증

`DemoProfileGuard`는 두 프로필의 동시 활성화 또는 둘 다 아닌 구성을 거부합니다. 기본 프로필은 `demo`이고 서버는 기본적으로 `127.0.0.1:8080`에 바인딩됩니다.

## 2. 빌드와 실행

### 전체 단위·웹 테스트

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

### demo 실행

```powershell
.\mvnw.cmd spring-boot:run
```

### oracle 실행

```powershell
$env:SPRING_PROFILES_ACTIVE='oracle'
$env:PM_DB_URL='jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1'
$env:PM_DB_USERNAME='PM_APP'
$env:PM_DB_PASSWORD='로컬에서_설정한_암호'
.\mvnw.cmd spring-boot:run
```

Oracle 연동 테스트는 세 DB 환경변수가 모두 있을 때만 활성화됩니다.

```powershell
$env:PM_DB_URL='jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1'
$env:PM_DB_USERNAME='PM_APP'
$env:PM_DB_PASSWORD='로컬에서_설정한_암호'
.\mvnw.cmd --batch-mode --no-transfer-progress test
```

## 3. 계층과 주요 패키지

```text
com.example.permissiondemo
├─ security       폼 로그인, 현재 사용자, 프로필 가드, Oracle 계정 조회
├─ authorization  권한 원장, 유효 권한, 메뉴·프로그램 판정, 관리 서비스
│  ├─ persistence demo/oracle 권한 원장과 관심 메뉴 어댑터
│  └─ workflow    권한 신청·승인 모델, 서비스, 저장소
├─ project        프로젝트 모델, 검색, 역할 판정, 저장소와 MyBatis 매퍼
├─ common         공통코드, 페이징, 부트스트랩
├─ content        공유 콘텐츠, 기대 버전, Oracle CLOB와 인메모리 저장소
├─ audit          감사 이벤트 서비스와 저장소
└─ web            REST 컨트롤러, 공통 응답·오류·traceId
```

MyBatis XML은 `src/main/resources/mappers`에 있으며 Java Mapper 인터페이스와 메서드·파라미터 이름을 맞춰야 합니다.

## 4. 프로필별 구현 매트릭스

| 영역 | demo | oracle |
|---|---|---|
| 로그인 계정 | `InMemoryUserDetailsManager` | `OracleUserDetailsService` + `PM_LOCAL_ACCOUNT` |
| 권한·조직·메뉴·프로그램 | 메모리 카탈로그 | `MyBatisAuthorizationCatalogPersistence` |
| 권한 신청 | `InMemoryAuthorityRequestRepository` | `MyBatisAuthorityRequestRepository` |
| 프로젝트 | `InMemoryProjectRepository` | `MyBatisProjectRepository` |
| 공통코드 | `InMemoryCommonCodePersistence` | `OracleCommonCodePersistence` |
| 콘텐츠 | `InMemoryContentRepository` | `OracleContentRepository` + `PM_CONTENT` |
| 관심 메뉴 | 인메모리 저장소 | MyBatis 저장소 |
| 감사 | 최근 메모리 이벤트 | `MyBatisAuditEventRepository` |

서비스와 컨트롤러 계약은 프로필에 관계없이 같습니다. 새 기능을 추가할 때는 두 저장소 구현과 테스트를 함께 변경해야 합니다.

## 5. 인증·인가·오류 계약

### 폼 로그인과 세션

`SecurityConfig`는 `/login` 폼 로그인, 세션 고정 공격 방지, 최대 세션 수와 로그아웃을 구성합니다. 세션 쿠키는 HTTP-only와 SameSite 정책을 사용합니다. 변경 요청은 CSRF 토큰이 필요하며 프런트는 `/api/csrf` 또는 부트스트랩 응답에서 받은 값을 전송합니다.

Oracle에서도 `LoginController`가 활성화되어 동일한 폼 로그인 화면을 제공합니다. 암호는 `PM_LOCAL_ACCOUNT.PASSWORD_HASH`의 BCrypt 값과 비교합니다.

### 2단계 권한 판정

관리 API는 `ProgramAuthorizationService`를 통해 `menuId + programId + actionId` 조합을 검사합니다. 프로젝트 API는 여기에 프로젝트별 역할에서 계산한 capability를 추가로 검사합니다. 프런트의 버튼 표시 여부는 편의 기능일 뿐이며 서버 검사를 우회하지 못합니다.

### 공통 응답

성공과 실패 모두 `ApiResponse`를 사용합니다.

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

오류에는 `code`, `message`, `path`, `traceId`, `fieldErrors`가 포함됩니다. `TraceIdFilter`가 요청별 식별자를 관리하고 `ApiExceptionHandler`가 검증·도메인·보안 오류를 같은 형식으로 변환합니다.

## 6. 권한 도메인

### 유효 권한 계산

`EffectiveAuthorityService`는 다음 조건을 모두 만족하는 권한만 반환합니다.

1. 권한 마스터가 활성이다.
2. 권한 이력이 `APPROVED` 상태다.
3. 현재 날짜가 `validFrom`과 `validTo` 사이에 있다.
4. 사용자 현재 조직과 권한 이력 조직이 일치한다.
5. `DELEGATED`이면 위임자가 같은 권한을 유효한 직접 권한으로 보유한다.

직접 부여와 위임 부여는 `DIRECT`, `DELEGATED`로 구분합니다. 회수는 이력을 지우지 않고 상태를 변경해 추적 가능성을 유지합니다.

### 신청·승인 워크플로

`AuthorityRequestService`는 `REQUESTED → APPROVED | REJECTED | CANCELLED` 전이를 관리합니다. 신청, 승인, 반려, 취소는 `version`을 이용한 낙관적 잠금을 적용합니다. 자기 승인·반려, 중복 대기 신청, 잘못된 기간과 유효하지 않은 위임 원천을 거부합니다. 승인 트랜잭션은 신청 상태 갱신과 실제 권한 이력 생성을 함께 처리합니다.

## 7. 프로그램 도메인

프로그램 기능의 식별자는 단일 action ID가 아니라 다음 복합 키입니다.

```text
(menuId, programId, actionId)
```

권한별 메뉴 매핑과 권한별 기능 매핑은 별도입니다. 프로그램이나 메뉴를 비활성화하면 연결된 허용 매핑이 있어도 접근할 수 없습니다. 상위 메뉴 삭제, 연결된 프로그램·기능 삭제와 권한 매핑 변경은 `AdminService`의 검증과 감사 기록을 거쳐야 합니다.

## 8. 프로젝트 도메인

### 저장 모델

`ProjectModels.Project`는 ID, 이름, 설명, 상태, 시작·종료일, 오너, 활성 여부와 버전을 가집니다. 상태는 `PLANNED`, `ACTIVE`, `ON_HOLD`, `COMPLETED`입니다. `ProjectMember` 역할은 `OWNER`, `MANAGER`, `MEMBER`, `VIEWER`입니다.

역할별 capability는 다음과 같습니다.

| 역할 | capability |
|---|---|
| `OWNER` | 전체 (`VIEW`, `UPDATE`, `MANAGE_MEMBERS`, `TRANSFER_OWNERSHIP`, `ARCHIVE`, `RESTORE`) |
| `MANAGER` | `VIEW`, `UPDATE`, `MANAGE_MEMBERS` |
| `MEMBER`, `VIEWER` | `VIEW` |

비활성 프로젝트에서는 일반 참여자는 조회만 가능하고, 글로벌 프로젝트 관리자 또는 오너만 복원 capability를 가질 수 있습니다. 완료된 프로젝트에서도 변경 capability가 제한됩니다.

### 핵심 불변식

- 프로젝트 ID와 사용자명 형식을 정규화하고 길이를 제한한다.
- 시작일은 종료일보다 늦을 수 없다.
- 상태 전이 규칙을 위반할 수 없다.
- 현재 오너는 활성 `OWNER` 참여자 한 명으로 일치해야 한다.
- 일반 참여자 저장으로 오너를 추가·해제할 수 없다.
- 오너 이전은 최신 버전, 새 오너와 필수 사유를 받아 원자적으로 처리한다.
- 보관은 `active=false`인 소프트 삭제이며 복원은 전용 명령으로 수행한다.
- 상태 변경은 `PM_PROJECT_STATUS_HISTORY`에 이전·이후 상태, 행위자, 사유와 시각을 남긴다.

### 검색 계약

`ProjectSearchCriteria`는 `keyword`, 복수 `statuses`, `ownerUsername`, `active`, `sort`, `direction`, `page`, `size`를 저장소에 전달합니다. `size`는 1~100이고 허용 정렬 필드만 SQL에 매핑합니다. `ProjectPage`는 `projects`, `totalElements`, `page`, `size`, `totalPages`를 반환합니다.

`ProjectMapper.xml`은 demo 조회와 같은 의미의 필터·정렬·페이징, 전체 건수, 오너 중복 역할 강등과 상태 이력 조회·삽입을 구현합니다. 새 필드를 추가할 때 Java record, 두 repository, Mapper 인터페이스/XML, DDL과 테스트를 동시에 맞추십시오.

## 9. 공통코드 도메인

`CommonCodeService`는 그룹 메타데이터와 계층형 코드 항목을 관리합니다. 주요 검증은 다음과 같습니다.

- 그룹·코드 형식, 이름 길이와 정렬 범위
- 적용 시작일과 종료일 순서
- 상위 코드 존재 여부, 자기 참조와 순환 참조 차단
- 시스템 그룹 삭제 금지
- 사용자 정의 그룹은 비활성·빈 상태에서만 삭제
- 사용자 정의 그룹의 비활성 말단 코드만 물리 삭제
- 최대 100건 일괄 저장의 전체 선검증

그룹별 version은 조회 캐시 무효화 기준입니다. Oracle에서는 항목 변경과 `PM_CODE_GROUP.VERSION_NO` 갱신을 같은 트랜잭션에서 처리합니다. 단일 서버 전제이므로 애플리케이션 로컬 캐시를 사용합니다.

## 10. 감사 의미

권한, 메뉴, 프로그램, 프로젝트, 공통코드와 권한 신청의 주요 변경은 `AuditEventService`에 행위자, 작업, 대상 종류·ID, 결과와 세부 정보를 전달합니다. Oracle 저장소는 `PM_AUDIT_EVENT`에 추가 전용으로 기록합니다. 민감한 암호나 세션 값은 감사 세부 정보에 넣지 마십시오.

## 11. 주요 API

모든 경로는 폼 로그인 세션과 필요한 프로그램 기능 권한을 전제로 합니다.

### 사용자·부트스트랩

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET | `/api/me` | 현재 사용자와 유효 권한 |
| GET | `/api/bootstrap` | 메뉴·기능·공통 버전 초기 상태 |
| GET | `/api/menus` | 접근 가능한 메뉴 |
| GET | `/api/menus/{menuId}/programs/{programId}/actions` | 허용 기능 |
| GET | `/api/csrf` | CSRF 토큰 |
| GET/POST/DELETE | `/api/me/favorite-menus...` | 관심 메뉴 |
| GET | `/api/audit-events` | 감사 이벤트 검색 |

### 권한 신청

| 메서드 | 경로 | 핵심 입력 |
|---|---|---|
| GET | `/api/authority-requests/options` | 활성 권한·유형 |
| POST | `/api/authority-requests` | `authorityId`, `assignmentType`, `validFrom`, `validTo`, `delegatedBy`, `reason` |
| GET | `/api/authority-requests/mine` | 상태·권한·정렬·페이징 |
| POST | `/api/authority-requests/{requestId}/cancel` | `version`, `reason` |
| GET | `/api/admin/authority-requests` | 상태·사용자·권한·정렬·페이징 |
| POST | `/api/admin/authority-requests/{requestId}/approve` | `version`, `reason` |
| POST | `/api/admin/authority-requests/{requestId}/reject` | `version`, `reason` |

### 관리 화면과 원장

| 영역 | 조회 | 변경 |
|---|---|---|
| 권한 | `/api/admin/authority-view`, `/organizations`, `/authorities`, `/user-authorities` | 권한 저장·삭제, 사용자 권한 부여·회수 |
| 메뉴 | `/api/admin/menu-view`, `/menus` | 메뉴 저장·삭제, 권한-메뉴 허용 |
| 프로그램 | `/api/admin/program-view`, `/programs`, `/program-actions` | 프로그램·기능 저장·삭제, 권한-기능 허용 |
| 프로젝트 | `/api/admin/project-view`, `/projects`, `/projects/{projectId}` | 저장, 오너 이전, 보관·복원, 참여자 관리 |
| 공통코드 | `/api/admin/common-code-view`, `/common-code-groups`, `/common-codes/{groupCode}` | 그룹·항목 저장·삭제, 일괄 저장·비활성화 |

프로젝트 검색 요청은 `statuses`를 반복 쿼리 파라미터로 전달할 수 있습니다. 프로젝트 저장 본문은 `name`, `description`, `status`, `startDate`, `endDate`, `ownerUsername`, `active`, `version`, `statusChangeReason`입니다. 오너 이전과 보관·복원은 반드시 최신 `version`과 `reason`을 보냅니다.

### 콘텐츠

실제 저장 API는 `/api/contents`입니다. 검색·상세에는 `CONTENT_READ`, 작성·수정에는 `CONTENT_SAVE`, 게시에는 `CONTENT_PUBLISH`를 요구합니다. 이미 게시된 콘텐츠 수정에도 저장·게시 권한을 함께 검사합니다. 기존 단수 `/api/content/preview|save|publish`는 권한 진단 예제로 남아 있으며 콘텐츠를 저장하지 않습니다.

`WorkspaceContentService`는 입력·활성 상태 코드·기대 버전을 검사하고 저장과 감사를 하나의 트랜잭션에서 처리합니다. Oracle의 버전 조건부 UPDATE가 다른 연결의 변경을 감지하며, 일괄 게시 중 하나라도 실패하면 전체 롤백합니다. ID·버전은 JSON 문자열입니다. API 필드와 예시는 [콘텐츠 저장 계약](CONTENT_PERSISTENCE_PLAN.md)을 참고하십시오.

## 12. Oracle 초기화와 변경 규칙

새 스키마 초기화는 `docker/oracle/init/001_initialize_app_schema.sh`가 다음 SQL을 순서대로 실행합니다.

1. `001_schema.sql`
2. `002_seed.sql`
3. `003_project_domain.sql`
4. `004_authority_workflow.sql`
5. `005_product_seed.sql`
6. `006_local_account.sql`
7. `007_project_history.sql`
8. `008_common_code_upgrade.sql`
9. `009_management_catalog_seed.sql`
10. `010_local_account_seed.sql`
11. `011_project_portfolio_seed.sql`
12. `012_column_comments.sql`
13. `013_workspace_configuration.sql`
14. `014_workspace_content.sql`

`001`과 `002`는 새 스키마 최초 생성용이며 `003`~`014`는 기존 개발 볼륨에도 재적용할 수 있게 작성되어 있습니다. 현재 애플리케이션 실행 전 `013`의 메뉴 영역·프로그램 화면 컬럼과 `014`의 콘텐츠 테이블·시퀀스가 필요합니다. 운영 변경은 별도 마이그레이션 도구와 변경 이력을 사용하고 컨테이너 초기화 스크립트를 즉석 배포 수단으로 사용하지 마십시오.

## 13. 테스트 전략

주요 테스트 묶음은 다음과 같습니다.

- 유효 권한, 메뉴·프로그램 권한과 관리 서비스 단위 테스트
- 권한 신청 상태 전이·낙관적 잠금 테스트
- 프로젝트 검색, 상태 전이, 역할 capability, 오너 이전, 보관·복원 테스트
- MyBatis 프로젝트 저장소 계약 테스트
- 공통코드 계층·일괄 저장·캐시 버전 테스트
- 로그인·CSRF·역할별 관리 API 보안 테스트
- 환경변수로 활성화되는 Oracle 프로젝트·공통코드·관리 영속성 통합 테스트

변경 시 최소 검증 순서는 다음과 같습니다.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress test
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Oracle 매퍼나 DDL을 바꿨다면 새 스키마 초기화, 기존 스키마 재적용과 Oracle 통합 테스트를 모두 확인하십시오.

## 14. 확장 체크리스트

- 핵심 네 도메인 중 어느 책임인지 먼저 정했는가
- demo와 oracle 구현 계약이 동일한가
- 컨트롤러 입력에 Bean Validation과 허용 정렬 목록을 적용했는가
- 서비스에서 권한, 상태 전이와 불변식을 다시 검사하는가
- 복합 키와 낙관적 잠금 버전을 유지하는가
- 변경과 감사, 도메인 버전 갱신이 같은 트랜잭션 경계에 있는가
- Mapper 인터페이스, XML, DDL, 초기화 순서가 일치하는가
- 단위·웹·Oracle 통합 테스트가 경계 조건을 포함하는가
- 단일 서버와 폼 로그인이라는 현재 운영 범위를 문서와 설정에 유지하는가
