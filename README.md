# 권한·메뉴·프로그램·공통코드 독립 테스트 프로젝트

ARISUINFO의 권한 및 공통 처리 개념을 Nexacro 없이 확인할 수 있도록 만든 Java 17 / Spring Boot 샘플입니다.

- 대상 경로: `C:\Users\이명주\Desktop\cheon\test`
- 원본 `C:\arisuinfo`와 `C:\arisuccs`는 수정하지 않습니다.
- DB, Nexacro Runtime, XFDL, Dataset, `BaseController`, 사내 SSO가 없어도 실행됩니다.
- 모든 업무 권한은 요청 파라미터가 아닌 로그인 세션의 사용자명과 현재 조직으로 서버가 계산합니다.
- 사용자·메뉴·기능 권한·관심 메뉴·공통코드를 `/api/bootstrap` 한 번으로 초기화합니다.
- 오류 응답에는 공통 오류 코드, 요청 경로와 `traceId`가 포함됩니다.
- 권한 마스터, 직접 부여·위임·회수, 메뉴 속성·권한 매핑, 프로그램·기능·권한 매핑과 공통코드 편집 화면을 제공합니다.
- 로그인 후 실제 업무형 사용자 포털로 진입하며 권한별 메뉴·기능 버튼·공통코드 상태를 화면에 반영합니다.
- 관리 변경은 사용자 포털에서 15초마다 자동 확인하거나 `권한 새로고침`으로 즉시 반영할 수 있습니다.
- 로그인·로그아웃·인가 거부·권한·메뉴·관심 메뉴·공통코드 변경을 인메모리 감사 이력으로 확인할 수 있습니다.
- 알려진 데모 계정 보호를 위해 기본 프로필은 `demo`, 바인딩 주소는 `127.0.0.1`이며 다른 프로필은 기동에 실패합니다.

원본 구조와 차이점은 [ARISUINFO_PERMISSION_ANALYSIS.md](ARISUINFO_PERMISSION_ANALYSIS.md)에 상세히 정리했습니다.

## 매뉴얼

- [사용자 매뉴얼](USER_MANUAL.md): 실행, 로그인, 계정별 테스트, 화면 사용법, 오류 확인
- [개발자 매뉴얼](DEVELOPER_MANUAL.md): 구조, 권한 판정, API, 보안, 테스트, 실제 DB 전환 방법
- [INFO 대비 보강 명세](INFO_UPGRADE_COMPARISON.md): 기존 INFO 처리, 개선 이유, 현재 구현 및 남은 범위 비교
- [DB 구축 준비서](DATABASE_PREPARATION.md): Oracle 연결 설정, 테이블·컬럼·코멘트, 초기 데이터와 전환 순서

## 1. 실행

필수 환경:

- Java 17
- Maven 3.9 이상

```powershell
cd 'C:\Users\이명주\Desktop\cheon\test'
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

브라우저에서 `http://127.0.0.1:8080`을 엽니다. 자체 로그인 화면을 거쳐 기본적으로 `/portal.html` 사용자 업무 포털로 이동합니다. `admin`은 포털 왼쪽 아래의 `시스템 관리 콘솔` 또는 `/` 주소로 관리 화면을 열 수 있습니다.

## 2. 테스트 계정

| 계정 | 비밀번호 | 직접 권한 | 위임 | 기대 결과 |
|---|---|---|---|---|
| `admin` | `admin123!` | `AUTH_SYSTEM_ADMIN` | 없음 | 시스템 메뉴와 전체 기능 허용 |
| `manager` | `manager123!` | `AUTH_CONTENT_MANAGER` | 없음 | 콘텐츠 조회·저장·게시 허용 |
| `viewer` | `viewer123!` | `AUTH_VIEWER` | 승인 대기 위임 1건 | 조회만 허용, 대기 위임 제외 |
| `delegate` | `delegate123!` | `AUTH_VIEWER` | 유효한 콘텐츠 관리자 위임 | 조회·저장·게시 허용 |
| `expired` | `expired123!` | `AUTH_VIEWER` | 2023년에 만료된 관리자 위임 | 조회만 허용 |

위 비밀번호는 로컬 데모 전용입니다. 운영 환경에서는 계정과 비밀번호를 코드에 두지 않아야 합니다.

## 3. 처리 흐름

```text
폼 로그인
  → Spring Security가 사용자 인증 및 세션 생성
  → CurrentUserContext가 사용자·현재 조직·IP·traceId 제공
  → EffectiveAuthorityService가 사용자명·현재 조직으로 최신 이력 선택
  → 승인·기간·활성·위임 원천 검증
  → MenuAuthorizationService가 공용 메뉴와 권한 메뉴 합집합 구성
  → 허용된 자식의 상위 컨테이너 보존 및 정렬
  → ProgramAuthorizationService가 menuId + programId + actionId 판정
  → BootstrapService가 사용자·메뉴·기능·관심 메뉴·공통코드를 한 응답으로 구성
  → portal.js가 허용 메뉴·기능 버튼·공통코드 상태를 실제 사용자 홈페이지에 표시
  → 15초 자동 확인 또는 수동 새로고침으로 관리 변경 버전을 다시 조회
  → 실제 업무 API는 @PreAuthorize로 같은 권한을 다시 검사
  → 로그인·거부·변경 이벤트를 AuditEventService에 기록
```

클라이언트는 `authIds`, `role`, `permissionIds`를 보내지 않습니다. 임의 값을 추가해도 서버가 읽지 않습니다.

## 4. 구현 구성

| 파일 | 역할 |
|---|---|
| `AuthorizationCatalog` | 수정 가능한 권한·메뉴·프로그램·기능 마스터와 사용자·권한 매핑의 인메모리 기준 데이터 |
| `AdminService` | 권한·메뉴·프로그램 마스터 저장, 사용자 권한과 권한별 메뉴·기능 매핑 및 감사 기록 조정 |
| `EffectiveAuthorityService` | 현재 조직의 최신 이력, 승인 상태, 유효기간, 활성 권한, 위임자 원천 권한을 확인해 유효 권한 합집합 계산 |
| `MenuAuthorizationService` | 공용 메뉴 + 권한 메뉴, 숨김/비활성 제외, 상위 메뉴 보존, 순서 정렬 |
| `ProgramAuthorizationService` | 메뉴·프로그램 문맥을 포함한 기능 권한과 화면 컴포넌트 ID 반환 |
| `CurrentUserContext` | 인증 사용자, 현재 조직, 원격 IP, 요청 `traceId`를 공통 제공 |
| `BootstrapService` | 사용자·메뉴·전체 허용 기능·관심 메뉴·공통코드·버전의 일관된 초기 스냅샷 구성 |
| `FavoriteMenuService` | 현재 접근 가능한 메뉴만 사용자 관심 메뉴로 등록·조회·삭제 |
| `AuditEventService` | 보안·변경 이벤트를 최근 500건까지 보관하고 공통 페이징으로 조회 |
| `SecurityConfig` | 세션 로그인, CSRF, 인증/인가 실패 JSON, 데모 사용자 구성 |
| `LoginController` | GET `/login`을 자체 로그인 화면에 연결하고 POST `/login`은 Spring Security에 위임 |
| `CommonCodeService` | 계층·적용기간·활성 상태·그룹 버전·조회 캐시를 포함한 인메모리 공통코드 |
| `ApiExceptionHandler` | 검증·인가·리소스·예상하지 못한 오류의 공통 JSON 응답 처리 |
| `TraceIdFilter` | 모든 HTTP 요청에 추적 ID를 부여하고 응답 헤더와 오류 본문에 반환 |
| `PageQuery` / `PageResult` | 최대 100건으로 제한된 0 기반 공통 페이징 계약 |
| `PermissionApiController` | 현재 사용자, 메뉴, 기능 권한, 공통코드, CSRF API |
| `BootstrapController` | 부트스트랩, 관심 메뉴, 계층 코드 뷰, 감사 이력 API |
| `AdminController` | 권한·메뉴·프로그램·기능·공통코드 관리자 조회 및 변경 API |
| `DemoBusinessController` | 조회·저장·게시·권한관리 서버 재검증 예제 |
| `login.html` / `login.js` | 자체 로그인 UI와 익명 CSRF 토큰 준비 |
| `portal.html` / `portal.js` | 실제 사용자 업무 홈, 권한 메뉴·버튼·공통코드 반영, 신규 메뉴 일반 화면 자동 생성 |
| `index.html` / `app.js` | 관리자용 권한·사용자 권한·메뉴·프로그램·기능·공통코드 관리 콘솔 |
| `*-polish.css` | 기존 공통 스타일을 건드리지 않고 로그인·포털·관리자 화면의 한글 가독성, 44px 이상 조작 영역과 모바일 배치를 보정 |

## 5. API

| Method | URL | 설명 | 추가 권한 |
|---|---|---|---|
| GET | `/api/csrf` | 로그인 폼과 현재 세션의 CSRF 토큰 | 익명 허용 |
| GET | `/api/bootstrap` | 사용자·메뉴·기능·관심 메뉴·공통코드 초기 스냅샷 | 로그인 |
| GET | `/api/me` | 현재 사용자와 유효 업무 권한 | 로그인 |
| GET | `/api/menus` | 권한별 메뉴 트리 | 로그인 |
| GET | `/api/menus/{menuId}/programs/{programId}/actions` | 해당 메뉴·프로그램의 허용 기능 | 로그인 |
| GET | `/api/common-codes/{groupCode}` | 활성 공통코드 | 로그인 |
| GET | `/api/common-codes/{groupCode}/view` | 계층 조건·활성 조건·그룹 버전을 포함한 코드 조회 | 로그인 |
| POST | `/api/common-codes/{groupCode}/items` | 계층·기간을 포함한 공통코드 추가/수정 | `COMMON_CODE_SAVE` + CSRF |
| GET | `/api/me/favorite-menus` | 현재 접근 가능한 관심 메뉴 조회 | 로그인 |
| POST | `/api/me/favorite-menus/{menuId}` | 접근 권한 재검증 후 관심 메뉴 등록 | 로그인 + CSRF |
| DELETE | `/api/me/favorite-menus/{menuId}` | 관심 메뉴 삭제 | 로그인 + CSRF |
| GET | `/api/audit-events?page=0&size=20` | 최근 감사 이벤트 페이징 조회 | `AUTHORITY_READ` |
| GET | `/api/content/preview` | 콘텐츠 조회 예제 | `CONTENT_READ` |
| POST | `/api/content/save` | 콘텐츠 저장 예제 | `CONTENT_SAVE` + CSRF |
| POST | `/api/content/publish` | 콘텐츠 게시 예제 | `CONTENT_PUBLISH` + CSRF |
| GET | `/api/admin/authorities` | 권한 마스터 조회 예제 | `AUTHORITY_READ` |
| GET | `/api/admin/authority-view` | 사용자별 유효 권한과 전체 이력 | `AUTHORITY_READ` |
| POST | `/api/admin/authorities/{authorityId}` | 권한 마스터 등록·수정 | `AUTHORITY_UPDATE` + CSRF |
| POST | `/api/admin/users/{username}/authorities` | 직접·위임 권한 부여 | `AUTHORITY_UPDATE` + CSRF |
| DELETE | `/api/admin/users/{username}/authorities/{authorityId}` | 유형별 권한 회수 | `AUTHORITY_UPDATE` + CSRF |
| GET | `/api/admin/menu-view` | 메뉴 마스터와 권한별 매핑 | `AUTHORITY_READ` |
| POST | `/api/admin/menus/{menuId}` | 메뉴 등록·수정 | `AUTHORITY_UPDATE` + CSRF |
| PUT | `/api/admin/authorities/{authorityId}/menus/{menuId}` | 권한-메뉴 허용 변경 | `AUTHORITY_UPDATE` + CSRF |
| GET | `/api/admin/program-view` | 프로그램·기능·권한 매핑 전체 조회 | `AUTHORITY_READ` |
| POST | `/api/admin/programs/{programId}` | 프로그램 마스터 등록·수정 | `AUTHORITY_UPDATE` + CSRF |
| POST | `/api/admin/program-actions/{menuId}/{programId}/{actionId}` | 프로그램 기능 등록·수정 | `AUTHORITY_UPDATE` + CSRF |
| PUT | `/api/admin/authorities/{authorityId}/program-actions/{menuId}/{programId}/{actionId}` | 권한-기능 허용 변경 | `AUTHORITY_UPDATE` + CSRF |
| GET | `/api/admin/common-code-view` | 공통코드 그룹별 건수·버전 | `COMMON_CODE_SAVE` |

## 6. 화면 테스트 시나리오

1. `viewer`로 로그인해 실제 사용자 포털로 이동하는지 확인합니다.
2. 홈과 콘텐츠 조회 메뉴만 표시되고 시스템 관리 콘솔 링크가 숨겨지는지 확인합니다.
3. 콘텐츠 화면에서 조회만 활성화되고 작성·게시가 비활성인지 확인합니다.
4. 관리자가 `AUTH_CONTENT_MANAGER`를 부여한 뒤 사용자 포털의 `권한 새로고침`을 누르면 작성·게시가 활성화되는지 확인합니다.
5. 로그아웃 후 `manager`로 로그인합니다.
6. 콘텐츠 저장·게시 버튼이 활성화되고 API가 성공하는지 확인합니다.
7. `delegate`도 `manager`와 같은 콘텐츠 기능을 사용할 수 있는지 확인합니다.
8. `expired`는 만료된 위임이 제외되어 조회만 가능한지 확인합니다.
9. `admin`으로 로그인해 사용자 포털에 시스템 관리 메뉴와 관리 콘솔 링크가 표시되는지 확인합니다.
10. `viewer`에게 콘텐츠 관리자 직접 권한을 부여한 뒤 유효 권한 반영과 회수를 확인합니다.
11. 새 메뉴를 저장하고 `AUTH_VIEWER`에 연결한 뒤 사용자 포털의 사이드바·빠른 서비스·자동 생성 화면에 나타나는지 확인합니다.
12. 새 권한·프로그램·기능을 저장하고 권한-기능을 연결한 뒤 대상 계정의 기능 API가 허용되는지 확인합니다.
13. `ARTICLE_STATUS` 코드명을 수정한 뒤 사용자 홈과 콘텐츠 목록의 상태명 및 버전이 바뀌는지 확인합니다.
14. 관심 메뉴를 등록·삭제하고 접근할 수 없는 메뉴 등록이 403인지 확인합니다.
15. 서울을 선택하면 강남구·마포구가 표시되는지 확인합니다.
16. 관리자로 감사 이력을 조회해 권한·메뉴·프로그램·기능 변경 이벤트를 확인합니다.

브라우저 개발자 도구에서 버튼의 `disabled`를 제거해도 서버 API는 권한이 없으면 403을 반환합니다.

## 7. 자동 테스트

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress test
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

테스트 대상:

- 승인된 직접 권한과 유효 위임 합집합
- 과거 승인 뒤 최신 회수, 이전 조직 권한 제외
- 만료 위임, 승인 대기 위임, 비활성 권한 제외
- 위임자의 원천 직접 권한 검증
- 공용 메뉴와 허용 메뉴의 상위 노드 보존
- 숨김 메뉴 제외와 형제 정렬
- `menuId + programId + actionId` 문맥 검증
- 권한·프로그램·기능 신규 등록과 권한-기능 매핑의 실제 사용자 인가 반영
- 클라이언트의 `authIds`·`role` 조작 무시
- 미인증 API 401
- 조회 사용자의 쓰기 API 403
- 쓰기 권한이 있어도 CSRF가 없으면 403
- 유효 권한과 CSRF가 모두 있을 때만 쓰기 성공
- 공통코드 활성 필터·정렬·입력 검증
- 사용자·메뉴·기능·공통코드의 단일 부트스트랩 스냅샷
- 상·하위 공통코드, 적용 기간, 그룹 버전과 캐시 무효화
- 관심 메뉴 접근 권한 재검증과 등록·삭제
- 표준 검증 오류의 필드 목록, 요청 경로와 `traceId`
- 감사 이력 기록과 최대 크기가 제한된 공통 페이징

## 8. Git에 올리기 전

이 폴더는 자동으로 Git 저장소를 만들지 않았습니다. 필요할 때 다음처럼 초기화할 수 있습니다.

```powershell
git init -b main
git add .gitattributes .gitignore .mvn mvnw mvnw.cmd pom.xml *.md src
git update-index --chmod=+x mvnw
git diff --cached --check
git status --short
git commit -m "feat: add permission and menu demo"
```

`.idea/`, `target/`, 사용자 Maven 설정과 로컬 저장소는 Git 대상에서 제외합니다.
Linux CI에서는 `./mvnw --batch-mode --no-transfer-progress clean verify`를 사용하면 됩니다.

## 9. 실제 DB 연계 시 교체 지점

현재 데이터는 재시작 시 초기화됩니다. 실제 적용에서는 다음만 Repository/Mapper로 교체하고 서비스 계약은 유지하는 방식을 권장합니다.

1. `AuthorizationCatalog.authorities()` → 권한 마스터 조회
2. `assignmentsFor(username)` → 현재 조직의 사용자 최신 승인 이력 + 유효 위임 조회
3. `menuGrantsFor(authorityId)` → 권한-메뉴 매핑 조회
4. `programs()` / `programActions()` / `actionGrantsFor()` → 프로그램·기능 마스터 및 권한 매핑 조회
5. `CommonCodeService` 저장소 → 공통코드 테이블
6. `FavoriteMenuService` 저장소 → 사용자-관심 메뉴 테이블
7. `AuditEventService` 저장소 → 변경 불가능한 감사 이력 테이블 또는 로그 수집 시스템

권한 부여·회수, 하위 정리, 고아 권한 제거, 이력 기록은 하나의 서버 트랜잭션으로 처리해야 합니다.
메뉴·권한·공통코드를 캐시할 때는 변경 버전과 캐시 무효화를 같은 트랜잭션 경계에서 처리해야 합니다.
