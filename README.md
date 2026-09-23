# 통합 관리 시스템

권한관리, 프로그램관리, 프로젝트관리, 공통코드관리를 하나의 관리 콘솔과 서버 권한 체계로 제공하는 Java 17 / Spring Boot 애플리케이션입니다. 사용자 업무공간에서는 관리자가 지정한 메뉴·프로그램·기능 권한을 확인하고 공유 콘텐츠를 작성·조회·게시합니다.

## 운영 범위

- 단일 Spring Boot 서버와 Oracle Database를 기준으로 합니다.
- 외부 SSO는 현재 범위에서 제외합니다.
- 로그인은 Spring Security 폼 로그인과 서버 세션을 사용합니다.
- `demo` 프로필은 DB 없이 인메모리 저장소로 빠르게 기능을 확인합니다.
- `oracle` 프로필은 로그인 계정, 권한·메뉴·프로그램 원장, 권한 신청, 프로젝트, 공통코드, 콘텐츠, 관심 메뉴와 감사 이력을 Oracle에 저장합니다.
- 클라이언트가 보낸 역할이나 권한 ID는 신뢰하지 않습니다. 서버가 로그인 사용자, 현재 조직, 최신 권한 이력과 프로젝트 역할로 다시 계산합니다.
- 단일 서버이므로 로컬 캐시와 DB 도메인 버전을 사용하며 Redis나 분산 캐시는 사용하지 않습니다.

## 핵심 기능

### 권한관리

- 조직·사용자·권한 마스터 검색과 관리
- 직접 권한과 위임 권한의 적용 기간, 최신 이력, 회수·만료 판정
- 자기 권한 신청, 신청 취소, 관리자 승인·반려
- 자기 승인·반려 금지, 요청 버전을 사용한 중복 결정 방지
- 권한별 메뉴와 프로그램 기능 매핑
- 변경 전후 값, 행위자, 조직, trace ID가 포함된 감사 이력

### 프로그램관리

- 계층 메뉴와 표시·활성·공용 속성 관리
- 프로그램 마스터와 메뉴별 기능 관리
- `menuId + programId + actionId` 복합 문맥으로 실행 권한 판정
- 권한-메뉴 및 권한-기능 매핑
- 비활성·미사용 항목의 안전한 삭제와 고아 매핑 정리
- 검색·필터·정렬·제한된 페이징

### 프로젝트관리

- 프로젝트 상태·기간·오너·활성 상태 관리
- 키워드·상태·오너·활성 조건 검색과 Oracle 네이티브 페이징
- `OWNER`, `MANAGER`, `MEMBER`, `VIEWER` 역할과 프로젝트별 capability 계산
- 상태 전이 사유와 행위자 이력
- 오너 이전 시 신규 오너 승격과 기존 오너 강등을 한 트랜잭션으로 처리
- 물리 삭제 대신 보관·복원, 낙관적 잠금 버전 검사

### 공통코드관리

- 코드 그룹과 상세 코드 전체 관리
- 계층, 정렬, 활성 상태와 적용 기간
- 자기 참조와 직·간접 순환 방지
- 시스템 그룹 보호, 사용 중 항목의 안전한 비활성화·삭제 규칙
- 그룹별 버전과 저장 성공 후 조회 캐시 무효화

## 기술 구성

| 구분 | 구성 |
|---|---|
| 언어 | Java 17 |
| 애플리케이션 | Spring Boot 3.5.7 |
| 보안 | Spring Security 폼 로그인, 세션, CSRF, 메서드 인가 |
| 검증 | Jakarta Validation |
| 영속화 | MyBatis 3.0.5, Oracle JDBC |
| DB | Oracle Database Free 23.26.1 |
| 테스트 | JUnit 5, AssertJ, Spring Security Test, MockMvc |

## 문서

- [사용자 매뉴얼](USER_MANUAL.md): 계정별 로그인과 네 관리 화면 사용법
- [개발자 매뉴얼](DEVELOPER_MANUAL.md): 구조, 도메인 규칙, API, 트랜잭션과 테스트
- [Oracle DB 구축 준비서](DATABASE_PREPARATION.md): 테이블·스크립트·정합성·배포 준비
- [로컬 Oracle 가이드](LOCAL_ORACLE_SETUP.md): Docker, DBeaver, 환경변수와 마이그레이션
- [관리 시스템 설계 기준](MANAGEMENT_SYSTEM_DESIGN.md): 제품 목적과 공통 완료 조건
- [콘텐츠 저장 계약과 검증](CONTENT_PERSISTENCE_PLAN.md): 이슈 #5의 API, 데이터 보존, 충돌 처리와 검증 범위

## 빠른 실행

필수 환경은 Java 17입니다. Maven Wrapper가 Maven 배포본과 의존성을 내려받을 수 있어야 합니다.

### demo 프로필

기본 프로필이 `demo`이므로 별도 DB 없이 실행할 수 있습니다.

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

브라우저에서 `http://127.0.0.1:8080/login`을 엽니다. 로그인 성공 후 `/portal.html`로 이동합니다. `/`와 `/portal`도 사용자 업무공간이며, 관리 콘솔은 `/admin`으로 분리됩니다. 관리 기능 권한이 없는 사용자는 `/admin`과 기존 `/index.html`에 직접 접근할 수 없습니다.

사용자 홈에서는 본인의 유효 권한명, 접근 가능한 업무 메뉴, 연결 프로그램과 기능별 `허용됨 / 권한 없음 / 사용 중지` 상태를 확인합니다. 관리 설정은 권한 새로고침과 15초 주기 확인으로 반영됩니다. 메뉴의 **화면 영역**을 사용자 업무공간 또는 관리자 콘솔로 지정하며, 부모와 자식은 같은 영역을 사용합니다. 관리자 화면에는 관리 영역 메뉴만 표시합니다.

프로그램의 **실행 화면**에서 `콘텐츠 관리` 또는 `내 권한 신청`을 연결할 수 있습니다. 사용자 카드의 실행 버튼은 프로그램 기능과 연결 대상 화면의 권한이 모두 있을 때 활성화됩니다. `연결 안 함`은 등록 정보만 표시합니다. 콘텐츠는 서버에서 검색·상세 조회·작성·수정·게시하며, `oracle`에서는 서버 재시작 후에도 유지됩니다. `demo`는 브라우저 새로고침에는 유지되지만 서버 재시작 시 초기화됩니다. 프로그램 등록은 기존 화면을 연결하는 기능입니다.

기존 Oracle 스키마는 애플리케이션 실행 전에 `013_workspace_configuration.sql`과 `014_workspace_content.sql`까지 적용해야 합니다. 메뉴 영역·실행 화면 컬럼과 콘텐츠 테이블·시퀀스를 추가합니다. 적용 순서는 [로컬 Oracle 가이드](LOCAL_ORACLE_SETUP.md)를 참고하십시오.

### oracle 프로필

먼저 `.env.oracle.example`을 `.env.oracle.local`로 복사하고 로컬 비밀번호를 입력합니다. 이 파일은 Git에서 제외됩니다.

```powershell
Copy-Item .env.oracle.example .env.oracle.local
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml up -d
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml ps
```

애플리케이션 프로세스에는 다음 환경변수를 설정합니다.

```powershell
$env:PM_DB_URL='jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1'
$env:PM_DB_USERNAME='PM_APP'
$env:PM_DB_PASSWORD='<로컬 애플리케이션 비밀번호>'
$env:SPRING_PROFILES_ACTIVE='oracle'
.\mvnw.cmd spring-boot:run
```

자세한 내용은 [LOCAL_ORACLE_SETUP.md](LOCAL_ORACLE_SETUP.md)를 참고합니다. 비밀번호 원문은 문서, 소스, 커밋이나 채팅에 넣지 않습니다.

## 역할별 기본 계정

아래 계정은 로컬 검증 전용이며 `demo`와 초기화가 완료된 `oracle` 프로필에서 같은 비밀번호를 사용합니다.

| 계정 | 비밀번호 | 대표 역할 |
|---|---|---|
| `admin` | `admin123!` | 시스템 관리자 |
| `backupAdmin` | `backup123!` | 예비 시스템 관리자 |
| `authorityManager` | `authority123!` | 권한 마스터·신청 승인 관리자 |
| `programManager` | `program123!` | 메뉴·프로그램·기능 관리자 |
| `projectManager` | `project123!` | 프로젝트 관리자 |
| `codeManager` | `code123!` | 공통코드 관리자 |
| `auditor` | `auditor123!` | 감사 조회자 |
| `contentEditor` | `content123!` | 콘텐츠 관리자 |
| `seoulOperator` | `seoul123!` | 서울 조직 프로젝트 운영자 |
| `busanViewer` | `busan123!` | 부산 조직 조회 사용자 |

기존 권한 판정의 만료·회수·조직 이동·위임 사례를 검증하는 추가 계정도 유지됩니다. 전체 목록은 [USER_MANUAL.md](USER_MANUAL.md)를 참고합니다.

## 처리 흐름

```text
폼 로그인
  → 세션 사용자와 현재 조직 조회
  → 최신 직접·위임 권한 이력 선택
  → 승인 상태·적용 기간·활성 권한·위임 원천 검증
  → 접근 가능한 메뉴 트리 계산
  → menuId + programId + actionId 기능 권한 계산
  → 프로젝트 요청이면 프로젝트 참여 역할 capability 추가 검사
  → 변경 요청이면 CSRF와 기대 버전 검사
  → 업무 데이터·버전·감사 이력을 트랜잭션으로 저장
  → 성공 후 로컬 캐시 무효화
```

## 주요 API

모든 JSON은 `{ "success": true, "data": ..., "error": null }` 형식의 공통 응답을 사용합니다. 변경 API에는 로그인 세션과 CSRF 헤더가 필요합니다.

| 영역 | Method | URL | 설명 |
|---|---|---|---|
| 공통 | GET | `/api/csrf` | 로그인 폼과 세션용 CSRF 토큰 |
| 공통 | GET | `/api/bootstrap` | 사용자·메뉴·기능·관심 메뉴·공통코드 스냅샷 |
| 사용자 | GET | `/api/portal/bootstrap` | 본인의 업무 메뉴·프로그램·기능 허용 여부·권한명 |
| 관리자 | GET | `/api/admin/bootstrap` | 관리 기능 권한을 검증한 관리자 전용 메뉴·기능 |
| 콘텐츠 | GET | `/api/contents` | 제목·작성자·상태 코드 검색과 페이징 |
| 콘텐츠 | GET | `/api/contents/{id}` | 본문·작성자·버전 상세 |
| 콘텐츠 | POST / PUT | `/api/contents`, `/api/contents/{id}` | 작성·기대 버전을 사용한 수정 |
| 콘텐츠 | POST | `/api/contents/publish` | 최대 50건 원자적 게시 |
| 권한 신청 | POST | `/api/authority-requests` | 자기 권한 신청 |
| 권한 신청 | GET | `/api/authority-requests/mine` | 내 신청 검색·페이징 |
| 권한 승인 | GET | `/api/admin/authority-requests` | 관리자 승인 큐 |
| 권한 승인 | POST | `/api/admin/authority-requests/{id}/approve` | 신청 승인 |
| 권한 승인 | POST | `/api/admin/authority-requests/{id}/reject` | 신청 반려 |
| 권한 | GET | `/api/admin/authorities` | 권한 마스터 검색·페이징 |
| 권한 | GET | `/api/admin/user-authorities` | 사용자 권한 검색·페이징 |
| 메뉴 | GET | `/api/admin/menus` | 메뉴 검색·페이징 |
| 프로그램 | GET | `/api/admin/programs` | 프로그램 검색·페이징 |
| 프로그램 | GET | `/api/admin/program-actions` | 기능 검색·페이징 |
| 프로젝트 | GET | `/api/admin/projects` | 프로젝트 검색·페이징 |
| 프로젝트 | GET | `/api/admin/projects/{projectId}` | 프로젝트·참여자·상태 이력 상세 |
| 프로젝트 | POST | `/api/admin/projects/{projectId}/owner-transfer` | 오너 이전 |
| 프로젝트 | POST | `/api/admin/projects/{projectId}/archive` | 프로젝트 보관 |
| 프로젝트 | POST | `/api/admin/projects/{projectId}/restore` | 프로젝트 복원 |
| 공통코드 | GET | `/api/admin/common-code-groups` | 코드 그룹 검색 |
| 공통코드 | GET | `/api/admin/common-code-groups/{groupCode}/items` | 상세 코드 검색 |
| 공통코드 | POST | `/api/admin/common-code-groups/{groupCode}/items/bulk` | 최대 100건 원자적 일괄 저장 |
| 감사 | GET | `/api/audit-events` | 감사 이력 검색·페이징 |

세부 요청 필드와 권한 키는 [DEVELOPER_MANUAL.md](DEVELOPER_MANUAL.md)에 정리되어 있습니다.

## Oracle 초기화 순서

컨테이너는 새 데이터 볼륨을 만들 때 다음 SQL을 순서대로 실행합니다.

1. `001_schema.sql` — 핵심 관리 원장
2. `002_seed.sql` — 기본 조직·사용자·권한·메뉴·코드
3. `003_project_domain.sql` — 프로젝트와 참여자
4. `004_authority_workflow.sql` — 권한 신청·승인
5. `005_product_seed.sql` — 연결된 관리 시나리오 데이터
6. `006_local_account.sql` — 폼 로그인 계정 테이블
7. `007_project_history.sql` — 프로젝트 상태 이력
8. `008_common_code_upgrade.sql` — 코드 그룹 관리 속성
9. `009_management_catalog_seed.sql` — 역할별 관리 원장 확장 시드
10. `010_local_account_seed.sql` — 역할별 로컬 계정 암호 해시
11. `011_project_portfolio_seed.sql` — 12개 프로젝트·50개 참여자·상태 이력 포트폴리오
12. `012_column_comments.sql` — 컬럼 설명
13. `013_workspace_configuration.sql` — 메뉴 영역·프로그램 실행 화면
14. `014_workspace_content.sql` — 공유 콘텐츠 테이블·시퀀스 (초기 콘텐츠 없음)

`003` 이후 스크립트는 기존 로컬 볼륨에 재적용할 수 있도록 멱등 구문으로 작성되어 있습니다. `001`과 `002`는 새 스키마 최초 생성용입니다.

## 테스트

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress test
.\mvnw.cmd --batch-mode --no-transfer-progress verify
node --test src/test/js/portal-status.test.cjs
```

실제 Oracle 통합 테스트는 `PM_DB_URL`, `PM_DB_USERNAME`, `PM_DB_PASSWORD`가 있을 때 실행됩니다. 프로젝트 상태 이력 테스트는 트랜잭션을 롤백하므로 검증 데이터가 남지 않습니다.

주요 검증 범위:

- 직접·위임·회수·만료·조직 이동 권한 판정
- 자기 승인 금지와 신청 버전 충돌
- 메뉴 조상 보존과 프로그램 복합 권한
- 프로젝트 검색·오너 이전·역할 capability·보관·상태 이력
- 공통코드 계층·기간·순환·그룹 보호·캐시 무효화
- 미인증 401, 인가·CSRF 실패 403과 표준 오류 응답
- MyBatis XML 파싱과 실제 Oracle 왕복
- 콘텐츠 기능 권한·CSRF·상태 코드·동시 수정 충돌·일괄 게시 롤백·Oracle CLOB 저장

2026-09-24 검증: Java 124건(실제 Oracle 6건 포함, 건너뜀 0), JavaScript 5건 통과. 별도 JAR 패키징과 Oracle 8092 브라우저의 작성·수정·게시·재시작 후 유지도 확인했습니다. 권한 신청의 Oracle 별도 연결 간 승인·취소 경합 테스트는 미실행이며 콘텐츠 동시 수정 테스트와 구분합니다.

## 보안 및 배포 주의

- 현재 구현은 로컬·단일 서버용입니다. `DemoProfileGuard`가 `demo` 또는 `oracle` 하나만 허용합니다.
- 서버는 기본적으로 `127.0.0.1:8080`에만 바인딩합니다.
- 로컬 계정은 개발·검증용입니다. 외부 노출 전에는 계정 수명주기, 비밀번호 정책, HTTPS 프록시와 백업 정책을 별도로 확정해야 합니다.
- SSO 연동, 다중 서버 캐시 동기화와 분산 세션은 현재 범위가 아닙니다.
- Oracle 비밀번호와 `.env.oracle.local`은 절대 커밋하지 않습니다.
