# Oracle 데이터베이스 구축 준비서

## 1. 목적과 범위

이 문서는 권한관리, 프로그램관리, 프로젝트관리, 공통코드관리 데이터를 Oracle에 영속화하기 위한 구축 기준입니다. 애플리케이션은 단일 Spring Boot 서버와 하나의 업무 스키마를 사용합니다. 로그인은 `PM_LOCAL_ACCOUNT`를 조회하는 폼 로그인 방식이며 외부 통합 로그인 구성은 포함하지 않습니다.

개발 기본값은 다음과 같습니다.

| 항목 | 값 |
|---|---|
| DBMS | Oracle Database Free 23 계열 |
| 서비스 | `FREEPDB1` |
| 업무 스키마 | `PM_APP` |
| JDBC URL | `jdbc:oracle:thin:@//127.0.0.1:1521/FREEPDB1` |
| 애플리케이션 프로필 | `oracle` |
| 문자 처리 | Oracle Unicode 기본 설정과 Java UTF-8 소스 |

실제 암호는 저장소에 커밋하지 말고 환경변수나 운영 비밀 저장소로 주입하십시오.

## 2. 연결 환경변수

`application-oracle.yml`은 아래 값을 읽습니다.

```powershell
$env:SPRING_PROFILES_ACTIVE='oracle'
$env:PM_DB_URL='jdbc:oracle:thin:@//db-host:1521/FREEPDB1'
$env:PM_DB_USERNAME='PM_APP'
$env:PM_DB_PASSWORD='별도_관리_암호'
```

애플리케이션 계정에는 업무 스키마의 테이블·시퀀스에 필요한 `SELECT`, `INSERT`, `UPDATE`, `DELETE` 권한만 부여하는 것이 원칙입니다. 초기 객체 생성은 별도 배포 계정으로 수행할 수 있습니다.

## 3. 스키마 객체 목록

현재 초기화 결과는 테이블 20개와 시퀀스 4개입니다.

### 권한·조직·프로그램 원장

| 테이블 | 역할 |
|---|---|
| `PM_ORGANIZATION` | 조직 계층과 활성 상태 |
| `PM_USER_PROFILE` | 사용자명과 현재 조직 |
| `PM_AUTHORITY` | 권한 마스터 |
| `PM_USER_AUTH_HIST` | 직접·위임 권한의 기간·상태 이력 |
| `PM_MENU` | 메뉴 트리와 표시·공개 상태 |
| `PM_PROGRAM` | 프로그램 마스터 |
| `PM_PROGRAM_ACTION` | 메뉴·프로그램·기능 복합 키 원장 |
| `PM_AUTH_MENU` | 권한별 메뉴 허용 |
| `PM_AUTH_ACTION` | 권한별 프로그램 기능 허용 |
| `PM_FAVORITE_MENU` | 사용자 관심 메뉴 |
| `PM_AUTH_REQUEST` | 권한 신청과 승인·반려·취소 이력 |
| `PM_LOCAL_ACCOUNT` | 로컬 폼 로그인 계정과 BCrypt 암호 해시 |

### 프로젝트

| 테이블 | 역할 |
|---|---|
| `PM_PROJECT` | 프로젝트 상태·일정·오너·활성·낙관적 잠금 버전 |
| `PM_PROJECT_MEMBER` | 프로젝트 참여자 역할과 활성 상태 |
| `PM_PROJECT_STATUS_HISTORY` | 상태 변경 전후 값, 행위자, 사유와 변경 시각 |

### 공통코드·운영

| 테이블 | 역할 |
|---|---|
| `PM_CODE_GROUP` | 코드 그룹 메타데이터, 시스템 여부와 버전 |
| `PM_COMMON_CODE` | 계층형 코드, 정렬·활성·적용 기간 |
| `PM_DOMAIN_VERSION` | 권한·메뉴·프로그램 등 도메인 버전 |
| `PM_AUDIT_EVENT` | 주요 변경 감사 이벤트 |
| `PM_CONTENT` | 공유 콘텐츠 제목·본문(CLOB)·상태·작성자·시각·버전 |

시퀀스는 `SQ_PM_USER_AUTH_HIST`, `SQ_PM_AUDIT_EVENT`, `SQ_PM_AUTH_REQUEST`, `PM_CONTENT_SEQ`입니다. 이름이 비슷하므로 배포 스크립트와 Mapper에서 정확한 이름을 사용해야 합니다.

## 4. 핵심 키와 정합성

### 권한과 프로그램

- 사용자 권한 이력은 시퀀스 ID를 사용하고 대상 사용자·조직·권한·유형·기간·상태를 보존합니다.
- 위임 권한은 `DELEGATED_BY`가 있어야 하며 서비스가 위임자의 유효한 직접 권한을 재검증합니다.
- 프로그램 기능 기본 키는 `(MENU_ID, PROGRAM_ID, ACTION_ID)`입니다.
- 권한-기능 매핑도 같은 세 키와 `AUTHORITY_ID`를 사용합니다.
- 메뉴는 자기 참조 상위 메뉴 FK로 트리를 구성하며 삭제 전 자식과 매핑 존재 여부를 확인합니다.

### 프로젝트

- 프로젝트 기본 키는 `PROJECT_ID`입니다.
- 참여자 기본 키는 `(PROJECT_ID, USERNAME)`입니다.
- 오너 사용자명과 활성 `OWNER` 참여자 한 명이 항상 일치해야 합니다.
- `VERSION_NO`는 수정, 오너 이전, 보관·복원의 낙관적 잠금 기준입니다.
- 상태 이력 기본 키는 `(PROJECT_ID, SEQUENCE_NO)`이며 프로젝트별 다음 순번을 계산해 삽입합니다.
- 보관은 행 삭제가 아니라 `ACTIVE_YN='N'`으로 처리합니다.

### 공통코드

- 그룹 기본 키는 `GROUP_CODE`, 항목 기본 키는 `(GROUP_CODE, CODE)`입니다.
- 상위 코드는 같은 그룹 안에서만 참조합니다.
- `VALID_FROM <= VALID_TO`와 활성·시스템 여부의 `Y/N` 제약을 유지합니다.
- `PM_CODE_GROUP.VERSION_NO`는 코드 변경 후 캐시 무효화에 사용합니다.
- 시스템 그룹은 서비스 정책상 삭제할 수 없습니다.

### 로그인과 감사

- `PM_LOCAL_ACCOUNT.USERNAME`은 사용자 프로필과 같은 사용자명을 사용합니다.
- 평문 암호를 저장하지 않고 `PASSWORD_HASH`에 BCrypt 해시만 저장합니다.
- 감사 이벤트는 시퀀스 ID와 발생 시각 기준으로 추가 기록하며 업무 이력을 수정·삭제하지 않는 것을 원칙으로 합니다.

## 5. SQL 실행 순서

`docker/oracle/init/001_initialize_app_schema.sh`는 다음 순서를 사용합니다.

| 순서 | 파일 | 역할 | 재적용 |
|---:|---|---|---|
| 1 | `001_schema.sql` | 핵심 14개 테이블, 시퀀스·인덱스 | 새 스키마 전용 |
| 2 | `002_seed.sql` | 기본 조직·사용자·권한·메뉴·코드 | 새 스키마 초기 시드 |
| 3 | `003_project_domain.sql` | 프로젝트·참여자와 권한 매핑 | 가능 |
| 4 | `004_authority_workflow.sql` | 권한 신청 테이블·시퀀스·인덱스 | 가능 |
| 5 | `005_product_seed.sql` | 관리 시나리오 기준 데이터 | 가능 |
| 6 | `006_local_account.sql` | 로컬 계정 테이블 | 가능 |
| 7 | `007_project_history.sql` | 프로젝트 상태 이력 | 가능 |
| 8 | `008_common_code_upgrade.sql` | 코드 그룹 정렬·시스템 속성 | 가능 |
| 9 | `009_management_catalog_seed.sql` | 역할별 조직·권한·메뉴·기능 카탈로그 | 가능 |
| 10 | `010_local_account_seed.sql` | 역할별 계정 BCrypt 해시 | 가능 |
| 11 | `011_project_portfolio_seed.sql` | 프로젝트 포트폴리오·참여자·상태 이력 확장 | 가능 |
| 12 | `012_column_comments.sql` | 테이블·컬럼 설명 | 가능 |
| 13 | `013_workspace_configuration.sql` | 메뉴 화면 영역과 프로그램 실행 화면 | 가능, 기존 설정 보존 |
| 14 | `014_workspace_content.sql` | 공유 콘텐츠 테이블·시퀀스 | 가능, 기존 데이터 보존 |

`001`과 `002`는 새 볼륨의 최초 초기화에 사용합니다. 기존 개발 스키마에는 필요한 `003`~`014`를 번호 순서대로 적용합니다. 현재 애플리케이션은 `013`의 `PM_MENU.MENU_AREA`와 `PM_PROGRAM.SCREEN_CODE`, `014`의 `PM_CONTENT`와 `PM_CONTENT_SEQ`를 사용합니다. 콘텐츠 초기 시드는 없으며 기존 브라우저 예제 데이터는 이관하지 않습니다. 모든 적용은 사전 백업과 대상 스키마 확인 후 수행하십시오.

## 6. 트랜잭션 경계

다음 변경은 중간 상태가 남지 않도록 하나의 트랜잭션에서 처리합니다.

- 권한 신청 승인: 신청 상태·버전 갱신 + 사용자 권한 이력 생성 + 감사
- 사용자 권한 회수: 유효 이력 상태 갱신 + 도메인 버전 갱신 + 감사
- 메뉴·프로그램 변경: 마스터 또는 매핑 변경 + 도메인 버전 갱신 + 감사
- 프로젝트 저장: 프로젝트 버전 갱신 + 오너 참여자 정합성 + 상태 이력 + 감사
- 오너 이전: 프로젝트 오너 갱신 + 기존 오너 강등 + 새 오너 승격 + 감사
- 공통코드 일괄 저장: 모든 항목 선검증 + 항목 반영 + 그룹 버전 1회 갱신 + 감사
- 콘텐츠 저장·게시: 활성 상태·기대 버전 검사 + 데이터·버전 변경 + 감사. 일괄 게시 실패는 전체 롤백

Oracle DDL은 암묵적 커밋이 발생하므로 업무 트랜잭션과 같은 세션에서 섞어 실행하지 마십시오.

## 7. 인덱스 기준

DDL에는 다음 조회 패턴용 인덱스가 포함됩니다.

- 사용자 권한: 대상 사용자, 위임 원천, 유효 기간·상태
- 메뉴: 상위 메뉴, 정렬, 활성·표시
- 프로그램 기능: 권한 매핑 역조회
- 공통코드: 그룹, 활성, 정렬과 적용 기간
- 감사: 발생 시각, 행위자, 이벤트 유형, traceId
- 권한 신청: 대기 큐 상태·신청 시각, 대상 사용자·조직
- 프로젝트: 활성·상태·시작일, 참여자 사용자명
- 프로젝트 이력: 프로젝트별 변경 시각 역순

운영 데이터 분포를 측정하기 전에 임의 인덱스를 추가하지 말고, 실행 계획과 통계 정보를 근거로 조정하십시오.

## 8. 구축 후 검증 SQL

### 테이블과 시퀀스 수

```sql
SELECT COUNT(*) AS TABLE_COUNT
  FROM USER_TABLES
 WHERE TABLE_NAME LIKE 'PM\_%' ESCAPE '\';

SELECT SEQUENCE_NAME
  FROM USER_SEQUENCES
 WHERE SEQUENCE_NAME IN (
       'SQ_PM_USER_AUTH_HIST',
       'SQ_PM_AUDIT_EVENT',
       'SQ_PM_AUTH_REQUEST',
       'PM_CONTENT_SEQ')
 ORDER BY SEQUENCE_NAME;
```

테이블 수 기대값은 `20`, 시퀀스 기대값은 `4`입니다.

### 고아 참조와 오너 정합성

```sql
SELECT m.PROJECT_ID, m.USERNAME
  FROM PM_PROJECT_MEMBER m
  LEFT JOIN PM_PROJECT p ON p.PROJECT_ID = m.PROJECT_ID
 WHERE p.PROJECT_ID IS NULL;

SELECT p.PROJECT_ID, p.OWNER_USERNAME,
       SUM(CASE WHEN m.PROJECT_ROLE = 'OWNER' AND m.ACTIVE_YN = 'Y' THEN 1 ELSE 0 END) AS OWNER_COUNT
  FROM PM_PROJECT p
  LEFT JOIN PM_PROJECT_MEMBER m ON m.PROJECT_ID = p.PROJECT_ID
 GROUP BY p.PROJECT_ID, p.OWNER_USERNAME
HAVING SUM(CASE WHEN m.PROJECT_ROLE = 'OWNER' AND m.ACTIVE_YN = 'Y' THEN 1 ELSE 0 END) <> 1;
```

두 쿼리 모두 결과가 없어야 합니다.

### 카탈로그와 계정

```sql
SELECT AUTHORITY_ID, AUTHORITY_NAME, ACTIVE_YN
  FROM PM_AUTHORITY
 ORDER BY AUTHORITY_ID;

SELECT USERNAME, ENABLED_YN
  FROM PM_LOCAL_ACCOUNT
 ORDER BY USERNAME;

SELECT GROUP_CODE, GROUP_NAME, SORT_ORDER, SYSTEM_YN, VERSION_NO
  FROM PM_CODE_GROUP
 ORDER BY SORT_ORDER, GROUP_CODE;
```

암호 해시는 운영 확인 결과나 로그에 출력하지 마십시오.

## 9. 배포 전 체크리스트

- 대상 PDB, 스키마와 JDBC URL을 확인했다.
- 애플리케이션 계정과 DDL 배포 계정 권한을 분리했다.
- 암호를 환경변수 또는 비밀 저장소에 등록했다.
- 신규면 `001`~`014`, 기존이면 필요한 `003`~`014`를 번호 순서대로 적용했다.
- 적용 전 백업과 복구 절차를 검증했다.
- 20개 테이블, 4개 시퀀스와 핵심 인덱스를 확인했다.
- 역할별 계정, 권한·메뉴·기능 매핑과 도메인 버전을 확인했다.
- 프로젝트 오너, 권한 이력과 공통코드 계층의 고아 데이터를 점검했다.
- Oracle 통합 테스트와 폼 로그인·권한 신청 승인 흐름을 실행했다.
- 단일 서버 구성에서만 운영되도록 서버 바인딩과 접근 통제를 확인했다.
