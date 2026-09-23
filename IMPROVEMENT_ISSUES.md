# 안정성 개선 이슈 및 진행 현황

작성일: 2026-09-24

상태: 3건의 GitHub 이슈 등록 완료. 후속 진행 승인에 따라 #1~#3의 수정·회귀 검증을 완료하고 소스를 `2f93620`에 커밋했다. `project/issue-5-content-persistence`에서 검토용 PR로 반영하며 main 병합은 별도다.

콘텐츠 확장 후 전체 검증은 Java 124건(실제 Oracle 6건), JavaScript 5건 통과다. 아래 결과는 각 수정 당시의 기록이며 #1의 Oracle 별도 연결 간 승인·취소 경합 테스트는 미실행으로 남아 있다.

| 우선순위 | 이슈 | 진행 상태 |
|---|---|---|
| 1 | [#1 승인·취소 경합 시 권한 잔존](https://github.com/ho-beom-cheon/permission_project/issues/1) | 로컬 수정·테스트 완료, GitHub 이슈는 열림 |
| 2 | [#2 보관 프로젝트 수정·복원 우회](https://github.com/ho-beom-cheon/permission_project/issues/2) | 로컬 수정·demo/API/Oracle 검증 완료 |
| 3 | [#3 상태 변경 사유 오류 시 부분 저장](https://github.com/ho-beom-cheon/permission_project/issues/3) | 로컬 수정·demo/API/Oracle 검증 완료 |

## #2·#3 후속 수정 및 검증 결과

- 일반 프로젝트 저장에서 보관·완료 상태, 기대 버전, UPDATE 역할 권한을 검사한다. 기존 `active` 변경은 거부하며 전용 보관·복원 동작으로 처리한다. 오너 변경은 TRANSFER_OWNERSHIP 권한도 검사한다.
- 행위자와 상태 변경 사유를 저장 전에 검증한다. null·빈 문자열·공백·300자 초과 사유가 프로젝트·참여자·이력·성공 감사를 부분 변경하지 않는다.
- 관리자 화면에서 기존 프로젝트의 활성 체크박스를 읽기 전용으로 표시한다.
- 실제 Oracle 신규 등록 검증에서 첫 상태 이력의 `fromStatus=null` 바인딩 오류를 발견했다. Mapper에 `jdbcType=VARCHAR`를 명시해 초기 등록 이력을 저장할 수 있게 수정했다.
- `ProjectSaveRegressionTest` 4건, `WorkspaceConfigurationTest`의 프로젝트 HTTP 오류·전용 복원 비교 검증, `ProjectMapperOracleIntegrationTest`의 실제 DB 데이터 불변·전용 복원 검증을 추가했다. Oracle 시험 데이터는 롤백했다.
- 전체 Maven 테스트 **117건 통과, 실패 0, 오류 0, 건너뜀 0**. 실제 Oracle 통합 테스트 5건을 포함한다. 로그: `target/remediation-full-tests.log`. 전용 복원 API 단언 추가 후 `WorkspaceConfigurationTest` 5건을 재검증했다(`target/remediation-api-final.log`).
- 포털 후속 수정과 검증은 [화면 분리 작업 기록](PORTAL_SEPARATION_PLAN.md)에 정리했다. #1의 Oracle 별도 연결 간 승인·취소 경합 검증은 여전히 미실행이다.

릴리스 노트 후보: 보관·완료 프로젝트의 일반 수정 및 활성 상태 우회를 차단하고, 잘못된 상태 변경 사유에 따른 부분 저장과 Oracle 초기 상태 이력의 null 바인딩 오류를 수정했다.

## #1 수정 및 검증 결과

- `AuthorityRequestService`의 승인·반려·취소가 같은 서비스 잠금을 사용하도록 변경했다. 동일 신청의 경쟁 결정이 승인 처리 중간에 끼어들지 못한다.
- 승인 사유와 결정 객체를 권한 부여 전에 검증·생성한다. 잘못된 사유 때문에 승인에 실패할 때 권한·버전·성공 감사가 남지 않게 했다.
- 기존 Oracle 트랜잭션, 낙관적 버전 검사, 커밋 후 권한 캐시 갱신 코드는 유지했다.
- `AuthorityRequestConcurrencyTest`에 6건을 추가했다. 승인 우선 3건(중복 승인·취소·반려), 취소/반려 우선 2건, 잘못된 승인 사유 1건이다. latch와 실제 경쟁 스레드 상태로 순서를 제어하며, 신청 상태·버전·유효 권한·감사 이벤트를 확인한다.
- 수정 전 새 테스트 결과: 6건 중 5건 실패(단언 실패 1, 실행 오류 4). 중복 승인 방지 1건만 통과했다.
- 수정 후 대상 테스트: `AuthorityRequestConcurrencyTest`, `AuthorityRequestServiceTest`, `AuthorityWorkflowSecurityTest` 11건 모두 통과.
- 로컬 Oracle 접속 설정을 프로세스 환경변수로 주입한 전체 테스트: **97건 통과, 실패 0, 오류 0, 건너뜀 0**. 기존 실제 Oracle 통합 테스트 4건도 통과했다. 비밀번호는 출력하거나 저장소에 추가하지 않았다.
- Oracle의 별도 연결 간 동시 승인/취소 전용 테스트는 미실행이다. 새 경합 회귀 테스트는 `demo` 인메모리 경로를 검증하고, 기존 Oracle 4건은 저장소 왕복을 검증한다.
- 애플리케이션 소스 변경은 `AuthorityRequestService.java` 한 파일이다. 테스트 한 파일을 추가하고 이 문서를 갱신했다. 기존 미커밋 변경은 보존했으며 커밋·푸시·서버 재시작은 수행하지 않았다.

실행한 수정 후 검증 명령:

```powershell
.\mvnw.cmd --offline --batch-mode --no-transfer-progress '-Dmaven.repo.local=C:\Users\cjs41\.m2\repository' '-Dtest=AuthorityRequestConcurrencyTest,AuthorityRequestServiceTest,AuthorityWorkflowSecurityTest' test
# PM_DB_URL, PM_DB_USERNAME, PM_DB_PASSWORD를 로컬 환경파일에서 주입한 프로세스에서 실행
.\mvnw.cmd --offline --batch-mode --no-transfer-progress '-Dmaven.repo.local=C:\Users\cjs41\.m2\repository' test
```

릴리스 노트 후보: 권한 신청 승인과 취소·반려가 겹칠 때 취소된 신청에 권한이 남는 문제를 수정하고, 잘못된 승인 사유로 인한 부분 반영을 방지했다.

## 최초 진단 기준과 검증 결과

- 사용자 우선순위: 안정성·오류 개선.
- 기준: 현재 `main` 작업 디렉터리. HEAD는 `606dd68`이며, 시작 시 기존 변경 항목 69개가 있었다. 이번 진단은 그 변경을 포함한 현재 코드를 대상으로 했다.
- GitHub 저장소 이슈 검색(`repo:ho-beom-cheon/permission_project is:issue`) 결과는 0건이었다.
- 기존 전체 테스트: 91건 중 87건 통과, 실패 0건, 오류 0건, Oracle 환경 의존 4건 건너뜀.
- `app.js`, `portal.js`, `login.js`: `node --check` 통과.
- 아래 3건은 별도 JVM의 인메모리 서비스와 저장소에서 실행해 재현했다. 현재 실행 중인 서버 및 Oracle 데이터는 변경하지 않았다.
- 실제 Oracle 왕복 및 브라우저 조작 검증은 이번 진단에 포함하지 않았다. 인메모리 재현 결과를 Oracle의 확정된 동작으로 간주하지 않는다.

실행한 기존 테스트 명령:

```powershell
.\mvnw.cmd --offline --batch-mode --no-transfer-progress '-Dmaven.repo.local=C:\Users\cjs41\.m2\repository' test
node --check src/main/resources/static/app.js
node --check src/main/resources/static/portal.js
node --check src/main/resources/static/login.js
```

임시 재현 코드는 Git 제외 경로인 `target/StabilityProbe.java`에 있으며, `clean` 실행 시 삭제된다. 컴파일된 애플리케이션 클래스를 임시 JAR로 묶고 기존 테스트 클래스패스를 사용했다. 재현 시각은 `2026-09-01T00:00:00Z`로 고정했다.

## 1순위 · P1 · 버그: 승인과 취소가 경합할 때 취소된 신청의 권한 부여 방지

### 문제와 영향

`demo` 저장소에서 승인과 취소가 겹치면 승인은 충돌로 실패하고 신청은 `CANCELLED`가 되지만, 신청 대상의 유효 권한에는 승인하려던 권한이 남는다. 신청 상태와 실제 권한이 일치하지 않는다.

### 재현

1. `viewer`가 `AUTH_CONTENT_MANAGER` 직접 권한을 신청한다.
2. 별도 스레드에서 관리자가 승인하고, 실제 권한 부여 직후 신청 상태 갱신 전에 잠시 대기시킨다.
3. 신청자가 같은 신청 버전으로 취소를 완료한다.
4. 승인 스레드를 재개하면 버전 충돌이 발생한다.
5. 신청 상태와 유효 권한을 각각 조회한다.

```text
RACE approvalException=ApiException
RACE requestStatus=CANCELLED effectiveGrant=true
```

### 원인과 범위

- [AuthorityRequestService](src/main/java/com/example/permissiondemo/authorization/workflow/AuthorityRequestService.java)의 `approve`가 `assignAuthority`를 먼저 호출하고 `updateDecision`을 나중에 호출한다.
- `approve`에는 `synchronized`가 있지만 `cancel`과 `reject`는 같은 서비스 잠금을 사용하지 않는다.
- [InMemoryAuthorityRequestRepository](src/main/java/com/example/permissiondemo/authorization/workflow/InMemoryAuthorityRequestRepository.java)의 개별 저장소 잠금은 권한 원장 변경까지 묶지 않는다.
- `demo`의 메모리 변경에는 DB 트랜잭션의 롤백 효과가 없다. Oracle 경로의 동시 처리 결과는 별도 확인이 필요하다.

### 수정 범위

신청 결정과 권한 부여가 하나의 변경 단위로 처리되도록 `demo`의 승인·취소·반려 경합을 조정한다. 입력 검증 및 실패 경로도 권한·신청·감사 상태의 불일치를 남기지 않게 한다. Oracle의 기존 트랜잭션과 커밋 후 캐시 갱신 규칙을 유지한다.

### 완료 기준과 검증 방법

- [x] 승인/취소 및 승인/반려 경합에서 한 결정만 성공한다(`demo` 회귀 테스트).
- [x] 취소·반려가 성공하면 새 유효 권한과 승인 성공 감사 이벤트가 남지 않는다(`demo`).
- [x] 승인이 성공하면 신청 상태, 실제 권한, 감사 이력이 일치한다(`demo`).
- [x] `CountDownLatch` 등으로 순서를 제어하는 회귀 테스트를 추가한다. 임의 sleep에 의존하지 않는다.
- [x] 기존 `AuthorityRequestServiceTest`, `AuthorityWorkflowSecurityTest`와 전체 테스트가 통과한다.
- [ ] Oracle 환경에서는 별도 트랜잭션/연결을 사용하는 경합 검증을 실행하고, 미실행 시 명시한다.

## 2순위 · P1 · 버그: 일반 저장 경로에서 보관 프로젝트 수정과 복원 우회 차단

### 문제와 영향

보관된 프로젝트는 capability 계산에서 `UPDATE`가 거부되지만 일반 저장 메서드는 이름 수정을 허용한다. 같은 경로에 `active=true`를 넣으면 복원 사유와 전용 복원 동작을 거치지 않고 다시 활성화된다.

### 재현

1. 활성 프로젝트를 만든 뒤 `archiveProject`로 보관한다.
2. 같은 오너와 현재 버전으로 이름을 바꿔 `saveProject`를 호출한다.
3. 반환된 버전으로 `active=true`인 일반 저장을 호출한다.

```text
ARCHIVE updateAllowed=false
ARCHIVE storedName=edited while archived active=false
ARCHIVE genericSaveRestored=true
```

### 원인과 범위

- [ProjectService](src/main/java/com/example/permissiondemo/project/ProjectService.java)의 `saveProject`는 오너가 바뀔 때만 `ensureMutable`를 호출한다.
- 일반 수정에 보관/완료 상태 제한을 일관되게 적용하지 않고 요청의 `active`를 그대로 저장한다.
- [AdminController](src/main/java/com/example/permissiondemo/web/AdminController.java)의 `POST /api/admin/projects/{projectId}`는 전역 `PROJECT_UPDATE` 권한을 확인한 다음 이 메서드를 호출한다. 해당 요청 DTO에도 `active`가 포함되어 있다.
- 서비스에서 직접 재현했다. HTTP 경로는 코드로 확인했으며, 실제 HTTP 재현은 수정 회귀 테스트에 포함한다.

### 수정 범위

일반 수정에도 보관·완료 상태와 프로젝트 capability 규칙을 적용한다. 기존 프로젝트의 보관/복원은 사유·버전·권한을 확인하는 전용 동작으로 일관되게 처리한다. 신규 등록 시 비활성 프로젝트 허용 여부는 기존 제품 계약을 확인해 보존한다.

### 완료 기준과 검증 방법

- [x] 보관 프로젝트의 일반 수정은 거부되고 데이터와 버전이 유지된다.
- [x] 일반 저장의 `active` 변경으로 기존 프로젝트의 보관/복원을 우회할 수 없다.
- [x] 완료 프로젝트 수정 제한도 동일하게 적용된다.
- [x] 전용 보관/복원 API의 정상 권한·사유·기대 버전 동작은 유지된다.
- [x] 서비스 회귀 테스트 및 MockMvc에서 일반 저장과 전용 복원 요청을 비교 검증한다.
- [x] Oracle에서도 같은 서비스 규칙이 적용되는지 통합 검증한다.

## 3순위 · P2 · 버그: 상태 변경 사유 오류 시 프로젝트가 부분 저장되는 문제 수정

### 문제와 영향

`demo`에서 상태 변경 사유가 비어 있으면 오류가 발생하지만 프로젝트의 상태와 버전은 이미 변경된다. 상태 이력에는 해당 변경이 기록되지 않는다. 사용자는 실패 응답을 받았는데 재조회하면 변경된 상태를 보게 된다.

### 재현

1. `PLANNED`, 버전 1인 프로젝트를 등록한다.
2. `ACTIVE`로 바꾸되 상태 변경 사유를 빈 문자열로 저장 요청한다.
3. 예외 이후 프로젝트와 상태 이력을 조회한다.

```text
EMPTY_REASON exception=IllegalArgumentException
EMPTY_REASON storedStatus=ACTIVE storedVersion=2 historyCount=1
```

초기 등록 이력 1건만 남고, 실패한 변경으로 상태와 버전은 바뀌었다.

### 원인과 범위

- [ProjectService](src/main/java/com/example/permissiondemo/project/ProjectService.java)의 `saveProject`가 프로젝트/오너 참여자를 저장한 다음 `appendStatusHistory`를 호출한다.
- 필수 사유 검증은 `appendStatusHistory` 안에서 수행된다.
- [AdminController](src/main/java/com/example/permissiondemo/web/AdminController.java)의 `ProjectSaveRequest.statusChangeReason`에는 최대 길이 검증만 있어 빈 사유가 서비스까지 전달된다.
- Oracle에서는 서비스 트랜잭션으로 롤백될 수 있으므로 이 부분 저장 현상은 현재 `demo` 재현으로 한정한다.

### 수정 범위

상태 변경 여부와 사유·행위자 유효성을 저장 전에 검사한다. `demo`의 프로젝트·오너 참여자·상태 이력을 변경하는 흐름에서 실패 시 부분 반영이 남는 범위를 점검하고 하나의 변경 단위로 처리한다.

### 완료 기준과 검증 방법

- [x] 상태 변경 시 null/빈 문자열/공백 사유는 저장 전에 거부된다.
- [x] 실패 이후 프로젝트 상태·버전·참여자·상태 이력·성공 감사 이력이 모두 이전 상태와 일치한다.
- [x] 상태를 바꾸지 않는 정상 수정과 신규 등록은 기존 동작을 유지한다.
- [x] 서비스 회귀 테스트 및 MockMvc에서 오류 응답 직후 재조회 결과를 검증한다.
- [x] Oracle에서도 동일한 오류 요청 후 데이터 불변을 검증한다.

## 진행 순서

1. 3건 등록과 #1의 로컬 수정·검증을 완료했다.
2. 후속 요청에 따라 #2와 #3의 로컬 구현과 검증도 완료했다.
3. 커밋·PR 준비 시 기존 사용자 변경 69개 항목과 이번 수정의 범위를 구분한다. 특히 권한 신청 서비스 자체가 이번 작업 전부터 미추적 상태였으므로 파일 전체를 이번 변경으로 간주하면 안 된다.
4. GitHub 이슈는 아직 닫지 않았다. 코드 반영과 릴리스 상태에 맞춰 관리한다.
