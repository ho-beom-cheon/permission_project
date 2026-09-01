# 권한·메뉴·공통코드 테스트 개발자 매뉴얼

## 1. 문서 목적

이 문서는 `permission-menu-demo`의 구조를 이해하고 권한, 메뉴, 프로그램 기능, 공통코드 처리 방식을 확장하거나 실제 저장소에 연결하려는 개발자를 위한 문서입니다.

이 프로젝트의 핵심 목표는 다음과 같습니다.

- Nexacro, XFDL, Dataset 없이 권한 처리 규칙을 웹 표준 환경에서 재현
- 클라이언트 입력이 아닌 로그인 세션을 신뢰 기준으로 사용
- 메뉴 노출과 서버 기능 인가를 분리
- 직접 권한과 위임 권한의 유효성 검증
- 공통코드 계층·기간·버전·캐시 갱신 예제 제공
- CSRF, 표준 오류, traceId, 감사 이력을 포함한 독립 실행형 보안 예제 제공

이 구현은 학습 및 로컬 검증용입니다. 운영용 사용자 저장소, SSO, DB, 분산 캐시 또는 영구 감사 저장소를 포함하지 않습니다.

기존 ARISUINFO의 처리와 이 프로젝트에서 보강한 내용의 항목별 비교는 [INFO 대비 보강 명세](INFO_UPGRADE_COMPARISON.md)를 참조합니다.

## 2. 기술 구성

| 항목 | 사용 기술 |
|---|---|
| 언어 | Java 17 |
| 서버 | Spring Boot 3.5.7 |
| 웹 | Spring MVC |
| 인증·인가 | Spring Security, Method Security |
| 입력 검증 | Jakarta Bean Validation |
| 화면 | HTML5, CSS, Vanilla JavaScript |
| 빌드 | Maven Wrapper |
| 테스트 | JUnit 5, AssertJ, MockMvc, Spring Security Test |
| 저장 | 인메모리 컬렉션 |

## 3. 빌드와 실행

### 3.1 전체 검증

```powershell
cd "C:\Users\이명주\Desktop\cheon\test"
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

정상 기준은 다음과 같습니다.

- 운영 Java 30개 컴파일 성공
- 테스트 Java 7개 컴파일 성공
- 테스트 40개 성공
- 실행 가능한 Spring Boot JAR 생성

### 3.2 개발 실행

```powershell
.\mvnw.cmd spring-boot:run
```

### 3.3 패키지 실행

```powershell
java -jar target\permission-menu-demo-1.0.0-SNAPSHOT.jar
```

서버는 `127.0.0.1:8080`에만 바인딩됩니다. 기본 프로필은 `demo`이며 `DemoProfileGuard`가 다른 프로필 또는 혼합 프로필 기동을 거부합니다.

### 3.4 JavaScript 문법 확인

Node.js가 설치되어 있으면 다음 검사도 사용할 수 있습니다.

```powershell
node --check src\main\resources\static\app.js
node --check src\main\resources\static\login.js
node --check src\main\resources\static\portal.js
```

## 4. 디렉터리 구조

```text
src/main/java/com/example/permissiondemo
├─ PermissionDemoApplication.java
├─ audit
│  └─ AuditEventService.java
├─ authorization
│  ├─ AuthorizationCatalog.java
│  ├─ EffectiveAuthorityService.java
│  ├─ MenuAuthorizationService.java
│  ├─ ProgramAuthorizationService.java
│  └─ FavoriteMenuService.java
├─ common
│  ├─ BootstrapService.java
│  ├─ CommonCodeService.java
│  ├─ PageQuery.java
│  └─ PageResult.java
├─ security
│  ├─ CurrentUserContext.java
│  ├─ WebUserContext.java
│  ├─ DemoProfileGuard.java
│  └─ SecurityConfig.java
└─ web
   ├─ PermissionApiController.java
   ├─ BootstrapController.java
   ├─ DemoBusinessController.java
   ├─ ApiResponse.java
   ├─ ApiException.java
   ├─ ApiExceptionHandler.java
   ├─ ApiErrorFactory.java
   ├─ ApiErrorWriter.java
   ├─ ErrorCode.java
   ├─ MessageResolver.java
   ├─ TraceIdFilter.java
   ├─ LoginController.java
   └─ FaviconController.java

src/main/resources
├─ application.yml
├─ messages.properties
└─ static
   ├─ login.html / login.css / login.js
   ├─ portal.html / portal.css / portal.js
   ├─ index.html
   ├─ app.js
   └─ style.css
```

## 5. 전체 처리 구조

```text
브라우저
  │ 폼 로그인
  ▼
Spring Security 세션 인증
  │ username
  ▼
CurrentUserContext
  │ username + 현재 조직 + clientIp + traceId
  ▼
EffectiveAuthorityService
  │ 직접 권한 + 검증된 위임 권한
  ├─────────────┐
  ▼             ▼
MenuAuthorizationService    ProgramAuthorizationService
  │ 화면 메뉴 트리           │ menuId + programId + actionId
  └─────────────┬────────────┘
                ▼
          BootstrapService
                │ 사용자·메뉴·기능·관심 메뉴·공통코드
                ▼
       portal.js / app.js
                │ 사용자 포털 표현 / 관리자 콘솔 처리
                ▼
      @PreAuthorize 서버 재인가
                │
                ▼
        업무 처리 + 감사 이벤트
```

핵심 원칙은 화면에 보이는 상태를 보안 경계로 사용하지 않는 것입니다. `portal.js` 또는 `app.js`가 버튼을 활성화하더라도 각 업무 API가 동일한 프로그램 기능 권한을 다시 확인합니다.

## 6. 권한 기준 데이터

### 6.1 권한 마스터

`AuthorizationCatalog.AuthorityDefinition`은 다음 정보를 가집니다.

- `id`: 권한 식별자
- `name`: 표시명
- `active`: 권한 마스터 사용 여부

기본 권한은 다음과 같습니다.

| ID | 이름 | 활성 |
|---|---|---|
| `AUTH_SYSTEM_ADMIN` | 시스템 관리자 | 예 |
| `AUTH_CONTENT_MANAGER` | 콘텐츠 관리자 | 예 |
| `AUTH_VIEWER` | 조회 사용자 | 예 |
| `AUTH_DISABLED` | 사용 중지 권한 | 아니요 |

비활성 권한은 사용자 배정이 존재해도 최종 유효 권한에 포함되지 않습니다.

관리 API는 권한 마스터를 신규 등록하거나 표시명·활성 상태를 수정할 수 있습니다. `AUTH_SYSTEM_ADMIN` 비활성화는 관리자 자신이 전체 관리 기능을 잃는 변경이므로 서버에서 거부합니다.

### 6.2 사용자와 현재 조직

`UserProfile`은 `username`과 `organizationId`를 가집니다. 현재 조직은 요청 파라미터나 헤더에서 받지 않고 서버 카탈로그에서 조회합니다.

이 원칙은 사용자가 다른 조직 ID를 전송해 과거 조직 권한을 다시 활성화하는 문제를 방지합니다.

### 6.3 권한 이력

`AuthorityAssignment`의 주요 필드는 다음과 같습니다.

| 필드 | 의미 |
|---|---|
| `username` | 권한을 적용받는 사용자 |
| `organizationId` | 권한이 유효한 조직 |
| `authorityId` | 권한 마스터 ID |
| `type` | `DIRECT` 또는 `DELEGATED` |
| `status` | `PENDING`, `APPROVED`, `REVOKED` |
| `validFrom`, `validTo` | 적용 시작일과 종료일 |
| `delegatedBy` | 위임 권한의 원천 사용자 |
| `sequence` | 같은 권한 이력에서 최신 상태를 결정하는 순번 |

과거 승인 행을 삭제하지 않고 회수 이력을 추가하는 형태를 가정합니다.

## 7. 유효 권한 계산

`EffectiveAuthorityService`가 권한 계산의 단일 진입점입니다.

### 7.1 계산 순서

1. `Authentication`이 실제 로그인 사용자 상태인지 확인합니다.
2. 인증 객체의 `username`으로 서버 사용자 프로필을 조회합니다.
3. 사용자 프로필의 현재 조직을 가져옵니다.
4. 사용자·현재 조직에 속한 권한 이력만 남깁니다.
5. `authorityId + assignmentType`별로 `sequence`가 가장 큰 최신 행을 선택합니다.
6. 상태가 `APPROVED`인지 확인합니다.
7. 기준일이 `validFrom`과 `validTo` 사이인지 확인합니다.
8. 권한 마스터가 존재하고 활성 상태인지 확인합니다.
9. 직접 권한은 결과 집합에 추가합니다.
10. 위임 권한은 위임 원천 검증을 통과한 경우에만 추가합니다.

어떤 단계에서든 정보가 없거나 유효하지 않으면 권한을 추가하지 않는 fail-closed 방식입니다.

### 7.2 위임 원천 검증

위임 권한은 다음 조건을 모두 만족해야 합니다.

- `delegatedBy`가 존재합니다.
- 위임자가 현재 수임자와 같은 조직에 속합니다.
- 위임자가 같은 `authorityId`의 유효한 직접 권한을 보유합니다.
- 위임자의 원본 권한이 승인 상태이고 기간 내에 있습니다.
- 권한 마스터가 활성 상태입니다.

위임자가 가진 다른 위임 권한은 위임 원천으로 인정하지 않습니다. 따라서 연쇄 위임이 차단됩니다. 원천 직접 권한이 회수되면 기존 수임자의 위임도 최종 계산에서 제외됩니다.

### 7.3 신뢰하지 않는 입력

다음 값은 클라이언트가 보내더라도 권한 계산에 사용하지 않습니다.

- `authIds`
- `authorityIds`
- `role`
- `permissionIds`
- 조직 ID를 나타내는 임의 파라미터 또는 헤더

Spring Security의 `ROLE_AUTHENTICATED_USER`는 로그인 완료 여부만 표현합니다. 업무 권한 ID를 `GrantedAuthority`에 클라이언트 입력으로 주입하지 않습니다.

## 8. 메뉴 권한

### 8.1 메뉴 정의

`MenuDefinition`은 다음 정보를 가집니다.

- 메뉴 ID와 상위 메뉴 ID
- 표시명과 이동 경로
- 정렬 순서
- 활성 여부
- 화면 표시 여부
- 공용 메뉴 여부

### 8.2 실제 접근 가능 메뉴

`MenuAuthorizationService.findAccessibleMenuIds()`는 다음 합집합을 만듭니다.

```text
활성 공용 메뉴
+ 사용자의 모든 유효 권한에 연결된 메뉴
- 존재하지 않거나 비활성인 메뉴
```

`canAccessMenu()`는 URL 직접 호출, 기능 권한 검사, 관심 메뉴 등록에서 동일하게 재사용됩니다.

### 8.3 화면 메뉴 트리

`findAuthorizedMenuTree()`는 실제 접근 가능 메뉴에서 다음 추가 처리를 합니다.

1. 비활성 또는 `displayed=false` 메뉴를 화면 대상에서 제외합니다.
2. 허용된 자식 메뉴로 이동할 수 있도록 표시 가능한 조상 폴더를 추가합니다.
3. 부모별로 그룹화하고 `sortOrder`로 정렬합니다.
4. 재귀 구조로 `MenuNode`를 만듭니다.
5. 순환 참조가 발견되면 예외로 중단합니다.

상위 폴더가 트리에 포함되는 것은 탐색을 위한 것입니다. 상위 폴더 자체가 `canAccessMenu()` 결과에 자동 추가되는 것은 아닙니다.

숨김 메뉴는 화면 트리에서 제외될 수 있지만 실제 접근 권한 데이터는 별도로 판정됩니다. 화면 표시와 접근 인가를 같은 플래그로 처리하지 않는 예제입니다.

## 9. 프로그램 기능 권한

### 9.1 권한 키

기능은 단일 `actionId`가 아니라 다음 세 값의 조합으로 식별합니다.

```text
menuId + programId + actionId
```

예를 들어 `CONTENT_READ`라는 이름만 비교하지 않고 `CONTENT_LIST / CONTENT / CONTENT_READ` 전체 문맥을 확인합니다.

### 9.2 허용 조건

`ProgramAuthorizationService.isAllowed()`는 다음 조건을 모두 검사합니다.

- 인증 사용자의 유효 업무 권한이 존재합니다.
- 기능 정의가 활성 상태입니다.
- 프로그램 마스터가 활성 상태입니다.
- 요청한 메뉴·프로그램·기능 키가 정확히 일치합니다.
- 사용자가 해당 메뉴에 실제 접근할 수 있습니다.
- 유효 권한 중 하나가 해당 기능 키를 부여받았습니다.

입력이 null이거나 비어 있거나 정의가 없으면 `false`를 반환합니다.

### 9.3 기본 기능 정의

| 메뉴 | 프로그램 | 기능 | 화면 컴포넌트 |
|---|---|---|---|
| `CONTENT_LIST` | `CONTENT` | `CONTENT_READ` | `btnContentRead` |
| `CONTENT_LIST` | `CONTENT` | `CONTENT_SAVE` | `btnContentSave` |
| `CONTENT_LIST` | `CONTENT` | `CONTENT_PUBLISH` | `btnContentPublish` |
| `SYSTEM_AUTH` | `AUTHORITY` | `AUTHORITY_READ` | `btnAuthorityRead` |
| `SYSTEM_AUTH` | `AUTHORITY` | `AUTHORITY_UPDATE` | `btnAuthorityUpdate` |
| `SYSTEM_CODE` | `COMMON_CODE` | `COMMON_CODE_SAVE` | `btnCommonCodeSave` |

`CONTENT_DELETE_OLD`는 비활성 기능 예제이므로 권한 매핑이 있더라도 허용되지 않습니다.

### 9.4 화면과 서버의 동일 기준 사용

- `findAllAllowedActions()`: 부트스트랩 응답에서 화면 버튼 활성화에 사용
- `findAllowedActions()`: 특정 메뉴·프로그램의 허용 기능 목록 조회
- `isAllowed()`: `@PreAuthorize`에서 서버 API의 최종 인가에 사용
- `requireAction()`: 서비스 내부에서 명시적인 거부 예외가 필요할 때 사용

세 경로가 같은 카탈로그와 유효 권한 계산을 사용하므로 화면과 서버 판정의 불일치를 줄입니다.

### 9.5 관리 화면 저장

관리 콘솔은 프로그램 마스터, 프로그램 기능과 권한-기능 매핑을 각각 저장합니다. 기능 저장 시 메뉴와 프로그램 존재 여부를 검사하며, 권한-기능 매핑은 실제 정의된 복합 키만 허용합니다. 변경하면 `programVersion`이 증가하고 포털 버전 비교에 포함됩니다.

`AUTHORITY` 프로그램과 시스템 관리자의 `AUTHORITY_READ`, `AUTHORITY_UPDATE` 기능은 관리 화면 전체를 잠그지 않도록 비활성화 또는 매핑 해제가 차단됩니다.

## 10. 부트스트랩

`GET /api/bootstrap`은 초기 화면에 필요한 데이터를 한 번에 반환합니다.

포함 항목은 다음과 같습니다.

- 로그인 사용자명과 현재 조직
- 정렬된 유효 권한 ID
- 권한별 메뉴 트리
- 전체 허용 프로그램 기능
- 현재 접근 가능한 관심 메뉴
- 요청한 공통코드 그룹의 항목과 버전
- 권한·메뉴·기능·공통코드 버전 정보
- 현재 세션의 CSRF 헤더명, 파라미터명, 토큰

기본 공통코드 그룹은 `USE_YN,REGION`입니다. `codeGroup` 쿼리 파라미터를 반복하거나 쉼표 형식으로 전달해 필요한 그룹을 변경할 수 있습니다.

부트스트랩의 목적은 화면이 사용자, 메뉴, 버튼, 코드 데이터를 여러 시점에 따로 조회하면서 생기는 초기 상태 불일치를 줄이는 것입니다.

## 11. 공통코드

### 11.1 데이터 계약

`CommonCodeItem`은 다음 필드를 가집니다.

```text
code, name, parentCode, sortOrder, active, validFrom, validTo
```

활성 조회 결과에는 다음 조건이 적용됩니다.

- `active=true`
- 현재 날짜가 적용 기간 안에 포함
- 선택한 `parentCode`와 일치
- `sortOrder`, `code` 순서로 정렬

### 11.2 입력 검증

- 그룹 코드와 코드: 영문 대문자, 숫자, 밑줄, 최대 30자
- 입력 코드는 trim 후 대문자로 정규화
- 이름: 필수, 최대 100자
- 정렬 순서: 0~9999, 미입력 시 999
- 적용 종료일은 시작일보다 빠를 수 없음
- 상위 코드는 같은 그룹 안에 존재해야 함
- 자기 자신을 부모로 지정할 수 없음
- 간접 순환 관계를 만들 수 없음

컨트롤러의 Bean Validation과 서비스의 관계 검증을 모두 사용합니다. HTTP 외부에서 서비스를 직접 호출해도 핵심 무결성 검증이 유지됩니다.

### 11.3 캐시와 버전

조회 캐시 키는 다음 조합입니다.

```text
groupCode + activeOnly + parentCode
```

저장 성공 시 다음 순서로 처리합니다.

1. 모든 입력과 계층 관계를 검증합니다.
2. 코드 항목을 신규 등록하거나 갱신합니다.
3. 해당 그룹 버전을 1 증가시킵니다.
4. 해당 그룹에 속한 모든 조회 조건 캐시를 제거합니다.
5. `COMMON_CODE_SAVED` 감사 이벤트를 기록합니다.

화면은 저장 응답 한 건을 로컬 목록에 임의 추가하지 않고 그룹을 다시 조회해 최종 항목과 버전을 함께 갱신합니다.

## 12. 관심 메뉴

`FavoriteMenuService`는 사용자별 관심 메뉴 ID를 인메모리로 보관합니다.

- 등록 전에 `canAccessMenu()`로 현재 접근 가능 여부를 재검사합니다.
- 경로가 없는 폴더 메뉴는 등록 대상에서 제외합니다.
- 조회할 때도 현재 접근 가능한 메뉴와 다시 교차 검증합니다.
- 권한이 사라지면 저장된 ID가 남아 있어도 응답에서 제외됩니다.
- 등록과 삭제는 각각 `FAVORITE_MENU_ADDED`, `FAVORITE_MENU_REMOVED` 이벤트를 기록합니다.

관심 메뉴 데이터 자체를 권한 근거로 사용하면 안 됩니다.

## 13. 인증과 보안

### 13.1 세션 로그인

`SecurityConfig`는 데모 계정을 `InMemoryUserDetailsManager`에 등록합니다. 비밀번호는 Delegating Password Encoder로 인코딩해 저장합니다.

로그인 역할은 모두 `ROLE_AUTHENTICATED_USER`입니다. 실제 업무 권한은 로그인 사용자명을 이용해 `EffectiveAuthorityService`에서 계산합니다.

### 13.2 URL 정책

- 익명 허용: `/login`, 로그인 정적 파일, `/api/csrf`, `/error`, `/favicon.ico`
- 로그인 필수: `/api/csrf` 외 `/api/**` 및 사용자·관리 화면 리소스
- 세부 업무 인가: 컨트롤러의 `@PreAuthorize`

익명 API 요청은 로그인 HTML로 리다이렉트하지 않고 401 JSON 오류를 반환합니다.

### 13.3 CSRF

CSRF는 비활성화하지 않습니다.

- GET, HEAD, OPTIONS: 토큰 불필요
- POST, PUT, PATCH, DELETE: 유효한 CSRF 토큰 필요
- JavaScript: 부트스트랩의 `headerName`과 `token`을 요청 헤더에 자동 첨부
- 로그아웃: CSRF 파라미터를 포함한 form POST

CSRF 검증 성공은 업무 권한 허용을 의미하지 않습니다. 두 검사를 모두 통과해야 쓰기 API가 실행됩니다.

### 13.4 세션 쿠키와 바인딩

- `server.address=127.0.0.1`
- 세션 제한 시간 30분
- `HttpOnly=true`
- `SameSite=Lax`

운영 HTTPS 환경으로 전환할 경우 Secure 쿠키, 세션 저장소, 프록시 헤더 신뢰 범위를 별도로 구성해야 합니다.

### 13.5 traceId

`TraceIdFilter`는 모든 요청에 추적 ID를 부여합니다.

- 유효한 요청 추적 헤더가 있으면 정책에 따라 사용
- 없으면 새 UUID 생성
- 로깅 MDC에 등록
- 응답 헤더와 API 오류 본문에 포함
- 요청 종료 후 MDC 정리

사용자에게 서버 스택 트레이스를 노출하는 대신 traceId로 서버 로그를 찾습니다.

## 14. 오류 응답

성공 응답 형식:

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

실패 응답의 대표 구조:

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ACCESS_DENIED",
    "message": "요청한 기능을 실행할 권한이 없습니다.",
    "path": "/api/content/publish",
    "traceId": "요청 추적 ID",
    "fieldErrors": []
  }
}
```

`ApiExceptionHandler`는 다음 오류를 공통 변환합니다.

- Bean Validation과 잘못된 인자: 400
- 미인증: 401
- 접근 거부: 403
- 대상 없음: 404
- 상태 충돌: 409
- 예상하지 못한 예외: 500

예상하지 못한 예외의 내부 메시지와 스택 트레이스는 응답에 직접 노출하지 않습니다.

## 15. 감사 이벤트

`AuditEventService`는 최근 이벤트를 앞쪽에 저장하고 최대 500건까지만 유지합니다.

주요 이벤트 유형:

- `LOGIN_SUCCESS`
- `LOGIN_FAILURE`
- `LOGOUT`
- `AUTH_REQUIRED`
- `ACCESS_DENIED`
- `FAVORITE_MENU_ADDED`
- `FAVORITE_MENU_REMOVED`
- `COMMON_CODE_SAVED`
- `AUTHORITY_ASSIGNED`
- `AUTHORITY_REVOKED`
- `MENU_SAVED`
- `MENU_GRANT_CHANGED`
- `AUTHORITY_SAVED`
- `PROGRAM_SAVED`
- `PROGRAM_ACTION_SAVED`
- `PROGRAM_GRANT_CHANGED`

이벤트에는 행위자, 조직, 대상, 결과, IP, traceId, 발생 시각과 제한된 상세 정보가 포함됩니다. 비밀번호, CSRF 토큰, 세션 ID, 개인정보 원문은 `details`에 기록하면 안 됩니다.

조회는 `PageQuery`와 `PageResult`를 사용하며 페이지는 0부터 시작합니다. 페이지 크기는 기본 20, 최대 100으로 제한됩니다.

## 16. API 목록

| Method | URL | 주요 입력 | 권한 |
|---|---|---|---|
| GET | `/api/bootstrap` | `codeGroup` 선택 | 로그인 |
| GET | `/api/csrf` | 없음 | 익명 허용, 로그인 폼 CSRF 준비 |
| GET | `/api/me` | 없음 | 로그인 |
| GET | `/api/menus` | 없음 | 로그인 |
| GET | `/api/menus/{menuId}/programs/{programId}/actions` | 경로 ID | 로그인 |
| GET | `/api/common-codes/{groupCode}` | 그룹 ID | 로그인 |
| GET | `/api/common-codes/{groupCode}/view` | `activeOnly`, `parentCode` | 로그인 |
| POST | `/api/common-codes/{groupCode}/items` | JSON 코드 항목 | `COMMON_CODE_SAVE` + CSRF |
| GET | `/api/me/favorite-menus` | 없음 | 로그인 |
| POST | `/api/me/favorite-menus/{menuId}` | 메뉴 ID | 로그인 + 메뉴 접근 + CSRF |
| DELETE | `/api/me/favorite-menus/{menuId}` | 메뉴 ID | 로그인 + CSRF |
| GET | `/api/audit-events` | `page`, `size` | `AUTHORITY_READ` |
| GET | `/api/content/preview` | 없음 | `CONTENT_READ` |
| POST | `/api/content/save` | 없음 | `CONTENT_SAVE` + CSRF |
| POST | `/api/content/publish` | 없음 | `CONTENT_PUBLISH` + CSRF |
| GET | `/api/admin/authorities` | 없음 | `AUTHORITY_READ` |
| GET | `/api/admin/authority-view` | 권한·사용자·전체 이력 | `AUTHORITY_READ` |
| POST | `/api/admin/authorities/{authorityId}` | 권한명·활성 상태 | `AUTHORITY_UPDATE` + CSRF |
| POST | `/api/admin/users/{username}/authorities` | 유형·권한·기간·위임 원천 | `AUTHORITY_UPDATE` + CSRF |
| DELETE | `/api/admin/users/{username}/authorities/{authorityId}` | `type` | `AUTHORITY_UPDATE` + CSRF |
| GET | `/api/admin/menu-view` | 메뉴·권한별 매핑 | `AUTHORITY_READ` |
| POST | `/api/admin/menus/{menuId}` | JSON 메뉴 속성 | `AUTHORITY_UPDATE` + CSRF |
| PUT | `/api/admin/authorities/{authorityId}/menus/{menuId}` | `granted` | `AUTHORITY_UPDATE` + CSRF |
| GET | `/api/admin/program-view` | 프로그램·기능·권한 매핑 | `AUTHORITY_READ` |
| POST | `/api/admin/programs/{programId}` | 프로그램명·설명·활성 | `AUTHORITY_UPDATE` + CSRF |
| POST | `/api/admin/program-actions/{menuId}/{programId}/{actionId}` | 기능명·컴포넌트·정렬·활성 | `AUTHORITY_UPDATE` + CSRF |
| PUT | `/api/admin/authorities/{authorityId}/program-actions/{menuId}/{programId}/{actionId}` | `granted` | `AUTHORITY_UPDATE` + CSRF |
| GET | `/api/admin/common-code-view` | 그룹별 건수·버전 | `COMMON_CODE_SAVE` |

공통코드 저장 요청 예제:

```json
{
  "code": "NOTICE",
  "name": "공지",
  "parentCode": null,
  "sortOrder": 30,
  "active": true,
  "validFrom": "2026-01-01",
  "validTo": "2099-12-31"
}
```

## 17. 프런트엔드 처리

### 17.1 로그인 화면

`LoginController`는 GET `/login`을 `login.html`로 내부 전달합니다. `login.js`는 익명 접근 가능한 `/api/csrf`에서 토큰을 받아 로그인 폼의 hidden 필드에 넣고 토큰 준비 전에는 로그인 버튼을 비활성화합니다. POST `/login` 인증 자체는 Spring Security 필터가 처리합니다.

화면별 가독성 보정은 `login-polish.css`, `portal-polish.css`, `admin-polish.css`에 분리했습니다. 기존 `login.css`, `portal.css`, `theme.css`의 배치와 권한 관련 DOM 계약은 그대로 유지하며, 보정 파일을 뒤에서 로드해 한글 글자 크기, 행간, 44px 이상 클릭 영역, 표 밀도, 반응형 재배치와 키보드 본문 건너뛰기를 추가합니다. 로그인 보정 CSS는 인증 전에도 필요하므로 `SecurityConfig`의 익명 정적 리소스 허용 목록에 포함합니다.

### 17.2 사용자 업무 포털

`portal.js`는 `/api/bootstrap`과 `/api/common-codes/ARTICLE_STATUS/view`를 함께 조회합니다.

1. 사용자·조직과 유효 권한 칩 렌더링
2. 서버 허용 메뉴 트리와 빠른 서비스 렌더링
3. `programActions`로 조회·저장·게시 버튼 활성화
4. 활성 `ARTICLE_STATUS`를 홈 카드와 콘텐츠 목록 배지에 반영
5. `#home`, `#content` 외 허용 경로에 일반 업무 화면 자동 생성
6. 시스템 메뉴가 있을 때만 관리 콘솔 링크 표시
7. 권한·메뉴·프로그램·공통코드 버전을 15초마다 비교하고 변경 시 전체 화면 재구성
8. 수동 `권한 새로고침`으로 같은 동기화 즉시 실행

화면 재구성에 사용하는 메뉴와 기능은 모두 서버 응답에서 오며 브라우저에 정적 관리자 권한 목록을 두지 않습니다.

### 17.3 관리자 콘솔

`app.js`의 초기 처리 순서는 다음과 같습니다.

1. DOM 이벤트 연결
2. `/api/bootstrap` 호출
3. CSRF 정보 보관
4. 사용자와 유효 권한 표시
5. 메뉴 트리 재귀 렌더링
6. 모든 보호 버튼을 기본 비활성화
7. 서버가 반환한 기능만 `componentId` 또는 `actionId`로 활성화
8. 관리자이면 권한·메뉴·프로그램·기능·공통코드 관리 원본 조회
9. 권한 마스터, 사용자 이력, 메뉴·기능 매핑과 그룹별 전체 코드 렌더링
10. 계층 지역과 관심 메뉴 렌더링

`requestJson()`은 다음 처리를 공통화합니다.

- `Accept: application/json`
- 객체 본문의 JSON 직렬화
- 변경 메서드의 CSRF 헤더 첨부
- `credentials: same-origin`
- 401 응답 시 로그인 화면 이동
- 표준 오류 봉투를 JavaScript `Error`에 보존

프런트 코드에 권한 판정 규칙이나 비밀 값을 추가하지 않습니다. 프런트는 서버 판정 결과를 표현하는 역할만 담당합니다.

## 18. 테스트 구성

| 테스트 | 검증 범위 |
|---|---|
| `EffectiveAuthorityServiceTest` | 직접·위임 합산, 만료, 대기, 회수, 조직 이동, 원천 권한 |
| `MenuAuthorizationServiceTest` | 공용 메뉴, 조상 보존, 숨김 메뉴, 표시와 접근 분리 |
| `ProgramAuthorizationServiceTest` | 조회·쓰기 권한, 위임, 메뉴·프로그램 문맥, 비활성 기능 |
| `CommonCodeServiceTest` | 활성 필터, 정렬, 정규화, 계층, 기간, 버전 |
| `AdminServiceTest` | 권한·프로그램·기능 저장, 매핑, 핵심 관리 기능 보호, 부여·회수와 메뉴 순환 차단 |
| `DemoProfileGuardTest` | demo 단독 허용, 운영 및 혼합 프로필 차단 |
| `PermissionApiSecurityTest` | 401, 403, CSRF, 권한·메뉴·프로그램·기능 저장과 실제 인가 반영, 실제 로그인·로그아웃 |

현재 전체 자동 테스트는 40건이며 로그인 화면·사용자 포털 접근, 권한·메뉴·프로그램 변경 테스트는 각 테스트 후 Spring 컨텍스트를 초기화해 다른 테스트에 인메모리 변경이 섞이지 않게 합니다.

권한 로직을 수정하면 최소한 다음 명령을 실행합니다.

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

화면을 수정하면 계정별 수동 시나리오도 함께 확인합니다.

## 19. 새 메뉴와 기능 추가 절차

### 19.1 메뉴 추가

1. 관리 콘솔 또는 `AuthorizationCatalog`에 고유한 `MenuDefinition`을 추가합니다.
2. 상위 메뉴 ID가 실제로 존재하는지 확인합니다.
3. 표시 여부, 활성 여부, 공용 여부를 결정합니다.
4. 필요한 권한의 메뉴 허용 집합에 메뉴 ID를 추가합니다.
5. 계층 순서와 상위 폴더 보존 테스트를 추가합니다.

관리 콘솔에서 런타임 메뉴를 추가할 수도 있습니다. `#home`, `#content` 외 해시 경로는 사용자 포털에 기본 업무 화면이 자동 생성되므로 권한 매핑 직후 탐색과 접근을 검증할 수 있습니다. 실제 고유 업무 기능이 필요하면 `portal.html`에 해당 `data-route` 섹션을 만들고 `portal.js`의 API 연결을 확장합니다.

### 19.2 프로그램 기능 추가

1. `menuId + programId + actionId` 조합을 결정합니다.
2. 관리 콘솔에서 프로그램 마스터와 `ProgramActionDefinition`을 저장합니다.
3. 관리 콘솔에서 필요한 권한에 같은 기능 복합 키를 허용합니다.
4. 업무 API에 동일한 키의 `@PreAuthorize`를 적용합니다.
5. 화면 버튼에 `data-protected`를 지정합니다.
6. 허용 계정과 거부 계정의 서비스 및 MockMvc 테스트를 추가합니다.

`actionId` 문자열만 복사하고 메뉴·프로그램 문맥을 다르게 쓰면 권한이 허용되지 않는 것이 정상입니다.

## 20. 실제 DB로 전환하기

Oracle 기준 물리 테이블, 컬럼 코멘트, 제약조건, 인덱스, 초기 데이터와 연결 설정의 상세 설계는 [DB 구축 준비서](DATABASE_PREPARATION.md)를 참조합니다.

### 20.1 교체 대상

| 현재 구현 | 실제 구현 후보 |
|---|---|
| `AuthorizationCatalog.authorities()` | 권한 마스터 Repository/Mapper |
| `findUser(username)` | 사용자·현재 조직 조회 |
| `assignmentsFor(username)`, `saveAssignment()`, `revokeAssignment()` | 권한 승인·회수·위임 이력 Repository/Mapper |
| `menuGrantsFor(authorityId)`, `setMenuGrant()` | 권한-메뉴 매핑 Repository/Mapper |
| `menus()`, `saveMenu()` | 메뉴 마스터 Repository/Mapper |
| `programs()`, `saveProgram()` | 프로그램 마스터 Repository/Mapper |
| `programActions()`, `saveAction()` | 메뉴-프로그램-기능 마스터 Repository/Mapper |
| `actionGrantsFor(authorityId)`, `setActionGrant()` | 권한-기능 매핑 Repository/Mapper |
| `CommonCodeService` 내부 Map | 공통코드 Repository/Mapper |
| `FavoriteMenuService` 내부 Map | 사용자 관심 메뉴 저장소 |
| `AuditEventService` 내부 Deque | append-only 감사 테이블 또는 로그 수집기 |

서비스의 공개 반환형과 fail-closed 규칙을 유지하면 컨트롤러와 화면 변경을 최소화할 수 있습니다.

### 20.2 권장 조회 방식

권한 이력을 DB에서 조회할 때는 다음 조건을 명확히 구현합니다.

- 인증 사용자명 조건
- 서버에서 결정한 현재 조직 조건
- 권한 ID와 부여 유형별 최신 이력 선택
- 승인 상태와 적용 기간
- 권한 마스터 활성 상태
- 위임 원천 사용자의 현재 조직과 유효 직접 권한

최신 이력을 SQL에서 선택하든 서비스에서 선택하든 동률 처리 기준과 정렬 기준을 고정해야 합니다.

### 20.3 트랜잭션 경계

다음 작업은 하나의 서버 트랜잭션으로 묶는 것을 권장합니다.

- 권한 부여 또는 회수 + 이력 기록
- 권한 회수 + 관련 위임 효력 정리
- 메뉴 또는 기능 매핑 변경 + 버전 증가
- 공통코드 저장 + 그룹 버전 증가
- 저장 성공 + 캐시 무효화 이벤트 발행

DB 커밋 전에 외부 캐시를 먼저 지우면 롤백 후 캐시와 DB가 어긋날 수 있습니다. 트랜잭션 완료 후 무효화 또는 버전 기반 캐시 키를 사용합니다.

### 20.4 운영 전 필수 교체

- 하드코딩 데모 계정 제거
- 사내 인증 또는 사용자 저장소 연결
- 비밀번호 정책과 계정 잠금 적용
- HTTPS 및 Secure 쿠키 적용
- 영구 세션 저장소 검토
- 감사 로그의 변경 불가능성 및 보존 정책 적용
- 개인정보 마스킹과 접근 통제
- 프록시 사용 시 신뢰할 `X-Forwarded-For` 범위 제한
- 다중 인스턴스 환경의 캐시와 버전 동기화
- 권한 변경 즉시 기존 세션에 반영할 정책 결정

## 21. 개발 시 금지 사항

- 요청 파라미터의 권한 ID를 서버 권한으로 신뢰하지 않습니다.
- 화면에서 버튼을 숨긴 것만으로 인가를 끝내지 않습니다.
- 관심 메뉴, URL, 화면 컴포넌트 ID를 권한 근거로 사용하지 않습니다.
- 조회 전용 API라는 이유로 인증을 생략하지 않습니다.
- 상태 변경 API의 CSRF 보호를 임의로 끄지 않습니다.
- 비밀번호, 세션 ID, CSRF 토큰을 로그나 감사 상세에 기록하지 않습니다.
- 존재하지 않는 권한·메뉴·기능을 기본 허용하지 않습니다.
- 위임 권한을 다른 위임의 원천으로 사용하지 않습니다.

## 22. Git 등록 전 확인

이 폴더는 자동으로 Git 저장소를 만들지 않습니다. 새 저장소로 등록할 경우 빌드 결과와 IDE 파일이 포함되지 않는지 먼저 확인합니다.

```powershell
git init -b main
git add .gitattributes .gitignore .mvn mvnw mvnw.cmd pom.xml README.md USER_MANUAL.md DEVELOPER_MANUAL.md ARISUINFO_PERMISSION_ANALYSIS.md src
git update-index --chmod=+x mvnw
git diff --cached --check
git status --short
```

`target/`, `.idea/`, 로컬 Maven 저장소, 사용자별 설정 파일, 로그 파일은 커밋하지 않습니다.

## 23. 변경 완료 점검표

- [ ] 권한은 인증 사용자명과 서버 현재 조직으로만 계산한다.
- [ ] 최신 회수 이력이 과거 승인보다 우선한다.
- [ ] 만료·대기·비활성 권한을 제외한다.
- [ ] 위임 원천 직접 권한을 다시 검증한다.
- [ ] 메뉴 표시와 실제 접근 가능 여부를 구분한다.
- [ ] 프로그램 기능은 메뉴·프로그램·기능 전체 문맥으로 검사한다.
- [ ] 모든 업무 API에 서버 측 인가가 있다.
- [ ] 상태 변경 요청에 CSRF 검사가 있다.
- [ ] 입력 검증 실패가 표준 오류로 반환된다.
- [ ] 오류 응답에 traceId가 있다.
- [ ] 민감한 변경과 접근 거부가 감사 이벤트로 남는다.
- [ ] 공통코드 저장 시 버전과 캐시가 함께 갱신된다.
- [ ] 전체 40개 테스트가 통과한다.
- [ ] 사용자 매뉴얼의 계정별 시나리오를 수동 확인했다.
