# 로컬 Oracle 실행 및 연결 가이드

## 1. 구성 개요

로컬 구성은 Spring Boot 단일 서버와 Oracle Database Free 컨테이너 하나로 실행합니다.

| 항목 | 값 |
|---|---|
| Compose 파일 | `docker-compose.oracle.yml` |
| 이미지 | `gvenzl/oracle-free:23.26.1-slim-faststart` |
| 컨테이너 | `permission-oracle` |
| 호스트 포트 | `127.0.0.1:1521` |
| 서비스 | `FREEPDB1` |
| 기본 업무 사용자 | `PM_APP` |
| 데이터 볼륨 | `permission-oracle-data` |

DB 포트는 로컬 루프백에만 공개됩니다. 애플리케이션도 기본적으로 `127.0.0.1:8080`에서 실행됩니다. 로그인은 `demo`, `oracle` 모두 자체 폼 로그인을 사용하며 외부 통합 로그인은 현재 구성에 포함하지 않습니다.

## 2. 사전 준비

- Docker Desktop 또는 Docker Engine과 Compose 플러그인
- Java 17
- 저장소의 Maven Wrapper
- 선택 사항: DBeaver와 Oracle JDBC 드라이버

저장소 루트에서 환경 파일을 만듭니다.

```powershell
Copy-Item .env.oracle.example .env.oracle.local
```

`.env.oracle.local`의 두 암호를 로컬 값으로 바꾸십시오.

```dotenv
ORACLE_ADMIN_PASSWORD=충분히_긴_로컬_관리자_암호
PM_DB_USERNAME=PM_APP
PM_DB_PASSWORD=충분히_긴_로컬_업무_암호
PM_DB_URL=jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1
```

이 파일은 커밋하지 않습니다. `PM_DB_PASSWORD`는 컨테이너 업무 계정 생성과 Spring Boot 접속에 같은 값을 사용해야 합니다.

## 3. 컨테이너 시작과 상태 확인

```powershell
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml up -d
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml ps
```

첫 시작은 이미지 다운로드, DB 생성과 SQL 초기화 때문에 시간이 걸립니다. `STATUS`가 `healthy`가 될 때까지 로그를 확인합니다.

```powershell
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml logs -f oracle
```

로그 추적은 `Ctrl+C`로 끝내도 컨테이너는 계속 실행됩니다.

일반 종료와 재시작은 데이터를 보존합니다.

```powershell
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml stop
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml start
```

## 4. 최초 초기화 순서

새 `permission-oracle-data` 볼륨이 생성될 때 `docker/oracle/init/001_initialize_app_schema.sh`가 다음 SQL을 순서대로 실행합니다.

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

결과는 핵심 네 관리 도메인, 권한 신청, 로컬 계정과 감사를 포함한 테이블 20개와 시퀀스 4개입니다. `010`까지 끝나야 역할별 계정으로 Oracle 폼 로그인을 할 수 있습니다.

초기화 스크립트는 새 볼륨 생성 시에만 자동 실행됩니다. 기존 볼륨에 파일만 추가하고 `docker compose up`을 다시 실행해도 새 SQL이 자동 적용되지 않습니다.

## 5. 기존 볼륨 업그레이드

먼저 대상이 로컬 컨테이너와 `PM_APP` 스키마인지 확인하고 필요한 데이터를 백업합니다. 필요한 스크립트를 번호 순서대로 적용합니다. 현재 코드에는 `013`의 `PM_MENU.MENU_AREA`와 `PM_PROGRAM.SCREEN_CODE`, `014`의 `PM_CONTENT`와 `PM_CONTENT_SEQ`가 필수입니다. `013`은 최초 컬럼 추가 때만 기존 데이터를 분류하고, 재실행 시 관리자가 저장한 설정을 보존합니다.

```powershell
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml exec oracle bash -lc 'for script in 003_project_domain.sql 004_authority_workflow.sql 005_product_seed.sql 006_local_account.sql 007_project_history.sql 008_common_code_upgrade.sql 009_management_catalog_seed.sql 010_local_account_seed.sql 011_project_portfolio_seed.sql 012_column_comments.sql 013_workspace_configuration.sql 014_workspace_content.sql; do sqlplus -s "$APP_USER/$APP_USER_PASSWORD@//localhost/FREEPDB1" @"/opt/permission/sql/$script" || exit 1; done'
```

명령이 실패하면 다음 번호로 넘어가지 말고 첫 Oracle 오류를 해결하십시오. `001`과 `002`는 기존 스키마에 재실행하지 않습니다.

특정 변경만 다시 확인할 때도 선행 번호가 적용되었는지 먼저 확인한 뒤 개별 파일을 실행합니다.

```powershell
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml exec oracle bash -lc 'sqlplus -s "$APP_USER/$APP_USER_PASSWORD@//localhost/FREEPDB1" @/opt/permission/sql/014_workspace_content.sql'
```

## 6. DBeaver 연결

새 Oracle 연결에서 다음 값을 사용합니다.

| 설정 | 값 |
|---|---|
| Host | `127.0.0.1` |
| Port | `1521` |
| Connection type | Service name |
| Service name | `FREEPDB1` |
| Username | `.env.oracle.local`의 `PM_DB_USERNAME` |
| Password | `.env.oracle.local`의 `PM_DB_PASSWORD` |

JDBC URL을 직접 입력하면 다음과 같습니다.

```text
jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1
```

SID 입력란이 아니라 Service name 입력란에 `FREEPDB1`을 넣습니다. 관리자 계정 대신 업무 계정으로 객체와 데이터를 확인하십시오.

## 7. DB 객체 검증

DBeaver 또는 SQL*Plus에서 업무 계정으로 실행합니다.

```sql
SELECT COUNT(*) AS TABLE_COUNT
  FROM USER_TABLES
 WHERE TABLE_NAME LIKE 'PM\_%' ESCAPE '\';

SELECT SEQUENCE_NAME
  FROM USER_SEQUENCES
 WHERE SEQUENCE_NAME LIKE 'SQ\_PM\_%' ESCAPE '\' OR SEQUENCE_NAME = 'PM_CONTENT_SEQ'
 ORDER BY SEQUENCE_NAME;
```

기대 결과는 테이블 `20`, 시퀀스 `4`입니다.

```sql
SELECT USERNAME, ENABLED_YN
  FROM PM_LOCAL_ACCOUNT
 ORDER BY USERNAME;

SELECT AUTHORITY_ID, AUTHORITY_NAME, ACTIVE_YN
  FROM PM_AUTHORITY
 ORDER BY AUTHORITY_ID;

SELECT PROJECT_ID, PROJECT_STATUS, OWNER_USERNAME, ACTIVE_YN, VERSION_NO
  FROM PM_PROJECT
 ORDER BY PROJECT_ID;

SELECT PROJECT_ID, SEQUENCE_NO, FROM_STATUS, TO_STATUS, CHANGED_BY, CHANGED_AT
  FROM PM_PROJECT_STATUS_HISTORY
 ORDER BY PROJECT_ID, SEQUENCE_NO DESC;

SELECT GROUP_CODE, GROUP_NAME, SORT_ORDER, SYSTEM_YN, VERSION_NO
  FROM PM_CODE_GROUP
 ORDER BY SORT_ORDER, GROUP_CODE;
```

암호 해시 열은 확인 출력이나 캡처에 포함하지 마십시오.

## 8. Spring Boot oracle 실행

PowerShell에서 환경 파일과 같은 연결 값을 설정합니다.

```powershell
$env:SPRING_PROFILES_ACTIVE='oracle'
$env:PM_DB_URL='jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1'
$env:PM_DB_USERNAME='PM_APP'
$env:PM_DB_PASSWORD='로컬에서_설정한_업무_암호'
.\mvnw.cmd spring-boot:run
```

다음 주소에서 폼 로그인을 확인합니다.

- `http://127.0.0.1:8080/login`
- `http://127.0.0.1:8080/portal.html`
- `http://127.0.0.1:8080/`

대표 검증 계정은 `admin / admin123!`, `authorityManager / authority123!`, `programManager / program123!`, `projectManager / project123!`, `codeManager / code123!`, `auditor / auditor123!`입니다. 이 암호들은 로컬 시드 전용입니다.

## 9. Oracle 통합 테스트

컨테이너가 healthy이고 `003`~`014`이 적용된 상태에서 실행합니다.

```powershell
$env:PM_DB_URL='jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1'
$env:PM_DB_USERNAME='PM_APP'
$env:PM_DB_PASSWORD='로컬에서_설정한_업무_암호'
.\mvnw.cmd --batch-mode --no-transfer-progress test
```

세 환경변수가 있으면 프로젝트 Mapper, 공통코드 Mapper, 관리 영속성과 콘텐츠 Oracle 통합 테스트가 실행됩니다. 테스트가 만든 데이터는 테스트의 정리 절차를 따르지만 공유 개발 DB에서 실행하기 전에는 데이터 영향을 확인하십시오.

## 10. 문제 해결

### 컨테이너가 healthy가 되지 않음

```powershell
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml ps
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml logs oracle
```

암호 형식, 1521 포트 충돌, Docker 메모리와 첫 SQL 오류를 확인합니다.

### `ORA-01017` 또는 로그인 실패

- Spring의 `PM_DB_USERNAME`, `PM_DB_PASSWORD`가 컨테이너 생성 때 사용한 값과 같은지 확인합니다.
- DB 접속 오류와 웹 폼 계정 오류를 구분합니다.
- 웹 폼 계정은 `PM_LOCAL_ACCOUNT`에 있으며 `010_local_account_seed.sql` 적용이 필요합니다.

### 테이블이나 열이 없음

- 기존 볼륨에서는 초기화 디렉터리가 다시 실행되지 않습니다.
- `003`~`014`을 번호 순서대로 적용했는지 확인합니다.
- 접속한 PDB와 현재 스키마가 `FREEPDB1`, `PM_APP`인지 확인합니다.

### 애플리케이션 시작 시 프로필 오류

`SPRING_PROFILES_ACTIVE`는 `demo` 또는 `oracle` 하나만 지정합니다. 빈 값, 둘의 동시 활성화 또는 다른 프로필만 지정한 구성은 거부됩니다.

## 11. 정리와 데이터 삭제 주의

컨테이너만 내리고 데이터를 보존하려면 다음 명령을 사용합니다.

```powershell
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml down
```

아래 명령은 `permission-oracle-data` 볼륨과 모든 로컬 DB 데이터를 삭제합니다. 백업이 있고 완전 초기화를 의도할 때만 실행합니다.

```powershell
docker compose --env-file .env.oracle.local -f docker-compose.oracle.yml down --volumes
```

삭제 후 다시 `up -d`하면 `001`~`014`이 새 스키마에 순서대로 실행됩니다.
