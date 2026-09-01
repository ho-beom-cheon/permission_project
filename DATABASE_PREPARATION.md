# 권한·메뉴·공통코드 Oracle DB 구축 준비서

## 1. 목적과 적용 범위

이 문서는 현재 인메모리로 동작하는 `permission-menu-demo`를 향후 Oracle DB 저장 방식으로 전환하기 위한 사전 설계서입니다.

다음 항목을 정의합니다.

- Oracle 연결 프로필과 환경변수
- 테이블 생성 순서와 관계
- 테이블·컬럼 이름, 자료형, 필수 여부, 기본값
- PK, FK, UNIQUE, CHECK 제약조건
- 조회 및 인가에 필요한 인덱스
- 테이블과 컬럼의 한글 코멘트
- 현재 데모 동작을 재현할 초기 데이터
- Repository 또는 MyBatis Mapper 교체 지점
- 트랜잭션, 캐시, 감사 이력 운영 기준

이 문서는 설계와 검토용입니다. 실제 DB 연결, DDL/DML 실행, 스키마 초기화는 수행하지 않습니다.

## 2. DBMS 기준

ARISUINFO 설정과 의존성을 확인한 결과 Oracle JDBC를 사용하고 있으므로 다음 기준으로 설계합니다.

| 항목 | 기준 |
|---|---|
| DBMS | Oracle |
| JDBC 드라이버 | `oracle.jdbc.OracleDriver` |
| Java 드라이버 계열 | `ojdbc11` 권장 |
| 문자 집합 | `AL32UTF8` 권장 |
| 날짜 기준 | 권한·코드 적용일은 `DATE`, 이벤트 시각은 `TIMESTAMP(6)` |
| 논리값 | `CHAR(1)`의 `Y`/`N` |
| 테이블 접두어 | `PM_` |
| 식별자 대소문자 | 대문자, 따옴표 없는 Oracle 식별자 |

Oracle 버전과 사내 표준에 따라 IDENTITY 대신 시퀀스를 사용하도록 설계했습니다.

## 3. 권장 스키마 분리

권한 테스트용 객체는 기존 업무 테이블과 섞지 않고 전용 계정 또는 전용 스키마에 생성하는 것을 권장합니다.

권장 원칙:

- 스키마 소유자와 애플리케이션 접속 계정을 분리합니다.
- 애플리케이션 계정에는 필요한 SELECT, INSERT, UPDATE 권한만 부여합니다.
- 감사 이력 DELETE 권한은 애플리케이션 계정에 부여하지 않습니다.
- 운영 비밀번호와 JDBC URL을 Git에 저장하지 않습니다.
- 테스트·개발·운영 DB 계정과 서비스명을 분리합니다.

## 4. 애플리케이션 연결 설정안

실제 전환 시 `oracle` 프로필 전용 설정을 만들고 다음 환경변수를 사용합니다.

| 환경변수 | 예시 | 설명 |
|---|---|---|
| `PM_DB_URL` | `jdbc:oracle:thin:@//db-host:1521/service-name` | Oracle JDBC URL |
| `PM_DB_USERNAME` | `PM_APP` | 애플리케이션 계정 |
| `PM_DB_PASSWORD` | 별도 비밀 저장소 값 | Git 저장 금지 |
| `PM_DB_POOL_MAX` | `10` | 최대 커넥션 수 |
| `PM_DB_POOL_MIN` | `2` | 최소 유휴 커넥션 수 |
| `PM_DB_CONN_TIMEOUT_MS` | `30000` | 연결 대기 제한 |

설정 형태는 다음과 같이 준비합니다. 현재 프로젝트에는 아직 활성 설정으로 추가하지 않습니다.

동일한 내용의 안전한 예제 파일은 [application-oracle.yml.example](src/main/resources/application-oracle.yml.example)에 준비되어 있습니다. 실제 전환 시 검토 후 파일명을 `application-oracle.yml`로 변경하고 `oracle` 프로필을 활성화합니다.

```yaml
spring:
  config:
    activate:
      on-profile: oracle
  datasource:
    driver-class-name: oracle.jdbc.OracleDriver
    url: ${PM_DB_URL}
    username: ${PM_DB_USERNAME}
    password: ${PM_DB_PASSWORD}
    hikari:
      maximum-pool-size: ${PM_DB_POOL_MAX:10}
      minimum-idle: ${PM_DB_POOL_MIN:2}
      connection-timeout: ${PM_DB_CONN_TIMEOUT_MS:30000}
      validation-timeout: 5000
      connection-test-query: SELECT 1 FROM DUAL
  sql:
    init:
      mode: never
```

`spring.sql.init.mode=never`를 유지해 애플리케이션 기동이 임의로 테이블이나 초기 데이터를 생성하지 않게 합니다.

추가 의존성 후보:

- `spring-boot-starter-jdbc`
- `com.oracle.database.jdbc:ojdbc11`
- MyBatis를 사용할 경우 사내 표준과 호환되는 `mybatis-spring-boot-starter`

의존성 버전은 실제 전환 시점의 Spring Boot BOM과 사내 저장소 정책에 맞춰 결정합니다.

## 5. 전체 테이블 관계

```text
PM_ORGANIZATION
  └─ PM_USER_PROFILE
       ├─ PM_USER_AUTH_HIST ── PM_AUTHORITY
       └─ PM_FAVORITE_MENU ─── PM_MENU

PM_AUTHORITY
  ├─ PM_AUTH_MENU ──────────── PM_MENU
  └─ PM_AUTH_ACTION ────────── PM_PROGRAM_ACTION

PM_MENU
  ├─ PM_MENU (상위/하위 자기 참조)
  └─ PM_PROGRAM_ACTION

PM_CODE_GROUP
  └─ PM_COMMON_CODE (상위/하위 자기 참조)

PM_DOMAIN_VERSION
PM_AUDIT_EVENT
```

## 6. 객체 생성 순서

FK 의존성을 고려한 권장 순서입니다.

1. `PM_ORGANIZATION`
2. `PM_USER_PROFILE`
3. `PM_AUTHORITY`
4. `PM_MENU`
5. `PM_PROGRAM_ACTION`
6. `PM_USER_AUTH_HIST`
7. `PM_AUTH_MENU`
8. `PM_AUTH_ACTION`
9. `PM_FAVORITE_MENU`
10. `PM_CODE_GROUP`
11. `PM_COMMON_CODE`
12. `PM_DOMAIN_VERSION`
13. `PM_AUDIT_EVENT`
14. 권한 이력 및 감사 이력용 시퀀스
15. 보조 인덱스
16. 테이블 및 컬럼 코멘트
17. 초기 기준 데이터

초기화나 재실행을 위해 DROP을 자동 수행하는 스크립트는 만들지 않는 것을 권장합니다.

## 7. 테이블별 상세 설계

### 7.1 PM_ORGANIZATION

테이블 코멘트: `조직 기준정보`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `ORG_ID` | `VARCHAR2(30)` | Y |  | PK | 조직 ID |
| `ORG_NAME` | `VARCHAR2(100)` | Y |  |  | 조직명 |
| `USE_YN` | `CHAR(1)` | Y | `'Y'` | CHECK | 사용 여부 |
| `SORT_ORDER` | `NUMBER(5)` | Y | `999` | CHECK | 표시 순서 |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |
| `UPDATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 수정 일시 |
| `UPDATED_BY` | `VARCHAR2(100)` | Y |  |  | 수정자 ID |

제약조건:

- `USE_YN IN ('Y', 'N')`
- `SORT_ORDER BETWEEN 0 AND 9999`

### 7.2 PM_USER_PROFILE

테이블 코멘트: `권한 판정용 사용자 현재 조직 정보`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `USERNAME` | `VARCHAR2(100)` | Y |  | PK | 로그인 사용자명 |
| `DISPLAY_NAME` | `VARCHAR2(100)` | Y |  |  | 사용자 표시명 |
| `CURRENT_ORG_ID` | `VARCHAR2(30)` | Y |  | FK | 현재 조직 ID |
| `ENABLED_YN` | `CHAR(1)` | Y | `'Y'` | CHECK | 사용자 사용 여부 |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |
| `UPDATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 수정 일시 |
| `UPDATED_BY` | `VARCHAR2(100)` | Y |  |  | 수정자 ID |

FK: `CURRENT_ORG_ID → PM_ORGANIZATION.ORG_ID`

이 테이블은 인증 비밀번호 저장소가 아닙니다. 실제 비밀번호와 SSO 식별 정보는 인증 시스템에서 관리하고, 여기에는 권한 판정에 필요한 사용자명과 현재 조직만 저장합니다.

### 7.3 PM_AUTHORITY

테이블 코멘트: `업무 권한 마스터`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `AUTHORITY_ID` | `VARCHAR2(50)` | Y |  | PK | 권한 ID |
| `AUTHORITY_NAME` | `VARCHAR2(100)` | Y |  |  | 권한명 |
| `ACTIVE_YN` | `CHAR(1)` | Y | `'Y'` | CHECK | 권한 활성 여부 |
| `DESCRIPTION` | `VARCHAR2(500)` | N |  |  | 권한 설명 |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |
| `UPDATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 수정 일시 |
| `UPDATED_BY` | `VARCHAR2(100)` | Y |  |  | 수정자 ID |

제약조건: `ACTIVE_YN IN ('Y', 'N')`

### 7.4 PM_USER_AUTH_HIST

테이블 코멘트: `사용자 직접 및 위임 권한 변경 이력`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `ASSIGNMENT_ID` | `NUMBER(19)` | Y | 시퀀스 | PK | 권한 배정 이력 ID |
| `USERNAME` | `VARCHAR2(100)` | Y |  | FK | 권한 대상 사용자명 |
| `ORG_ID` | `VARCHAR2(30)` | Y |  | FK | 권한 적용 조직 ID |
| `AUTHORITY_ID` | `VARCHAR2(50)` | Y |  | FK | 권한 ID |
| `ASSIGNMENT_TYPE` | `VARCHAR2(10)` | Y |  | CHECK | 부여 유형 |
| `ASSIGNMENT_STATUS` | `VARCHAR2(10)` | Y |  | CHECK | 승인 상태 |
| `VALID_FROM` | `DATE` | Y |  | CHECK | 적용 시작일 |
| `VALID_TO` | `DATE` | Y |  | CHECK | 적용 종료일 |
| `DELEGATED_BY` | `VARCHAR2(100)` | N |  | FK | 위임 원천 사용자명 |
| `HISTORY_SEQ` | `NUMBER(10)` | Y |  | UNIQUE 구성 | 동일 권한 이력 순번 |
| `REASON` | `VARCHAR2(500)` | N |  |  | 부여·회수 사유 |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 이력 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 이력 등록자 ID |

FK:

- `USERNAME → PM_USER_PROFILE.USERNAME`
- `ORG_ID → PM_ORGANIZATION.ORG_ID`
- `AUTHORITY_ID → PM_AUTHORITY.AUTHORITY_ID`
- `DELEGATED_BY → PM_USER_PROFILE.USERNAME`

제약조건:

- `ASSIGNMENT_TYPE IN ('DIRECT', 'DELEGATED')`
- `ASSIGNMENT_STATUS IN ('PENDING', 'APPROVED', 'REVOKED')`
- `VALID_TO >= VALID_FROM`
- 직접 권한은 `DELEGATED_BY IS NULL`
- 위임 권한은 `DELEGATED_BY IS NOT NULL`
- `(USERNAME, ORG_ID, AUTHORITY_ID, ASSIGNMENT_TYPE, HISTORY_SEQ)` UNIQUE

권한 회수 시 기존 승인 행을 UPDATE하거나 DELETE하지 않고 `HISTORY_SEQ`가 더 큰 `REVOKED` 행을 INSERT합니다.

권장 인덱스:

- `(USERNAME, ORG_ID, AUTHORITY_ID, ASSIGNMENT_TYPE, HISTORY_SEQ DESC)`
- `(DELEGATED_BY, ORG_ID, AUTHORITY_ID, ASSIGNMENT_TYPE, HISTORY_SEQ DESC)`
- `(AUTHORITY_ID, ASSIGNMENT_STATUS, VALID_FROM, VALID_TO)`

### 7.5 PM_MENU

테이블 코멘트: `계층형 메뉴 마스터`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `MENU_ID` | `VARCHAR2(50)` | Y |  | PK | 메뉴 ID |
| `PARENT_MENU_ID` | `VARCHAR2(50)` | N |  | 자기 FK | 상위 메뉴 ID |
| `MENU_NAME` | `VARCHAR2(100)` | Y |  |  | 메뉴명 |
| `MENU_PATH` | `VARCHAR2(500)` | N |  |  | 화면 경로 또는 앵커 |
| `SORT_ORDER` | `NUMBER(5)` | Y | `999` | CHECK | 동일 부모 내 표시 순서 |
| `ACTIVE_YN` | `CHAR(1)` | Y | `'Y'` | CHECK | 메뉴 활성 여부 |
| `DISPLAY_YN` | `CHAR(1)` | Y | `'Y'` | CHECK | 화면 메뉴 표시 여부 |
| `PUBLIC_YN` | `CHAR(1)` | Y | `'N'` | CHECK | 로그인 사용자 공용 메뉴 여부 |
| `DESCRIPTION` | `VARCHAR2(500)` | N |  |  | 메뉴 설명 |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |
| `UPDATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 수정 일시 |
| `UPDATED_BY` | `VARCHAR2(100)` | Y |  |  | 수정자 ID |

FK: `PARENT_MENU_ID → PM_MENU.MENU_ID`

제약조건:

- `MENU_ID <> PARENT_MENU_ID`
- `SORT_ORDER BETWEEN 0 AND 9999`
- 세 Y/N 컬럼은 각각 `IN ('Y', 'N')`

권장 인덱스: `(PARENT_MENU_ID, ACTIVE_YN, DISPLAY_YN, SORT_ORDER, MENU_ID)`

DB FK는 직접 자기 참조만 방지합니다. 간접 순환 구조는 저장 서비스에서 조상 경로를 확인해 차단합니다.

### 7.6 PM_AUTH_MENU

테이블 코멘트: `권한별 접근 허용 메뉴 매핑`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `AUTHORITY_ID` | `VARCHAR2(50)` | Y |  | PK, FK | 권한 ID |
| `MENU_ID` | `VARCHAR2(50)` | Y |  | PK, FK | 메뉴 ID |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |

PK: `(AUTHORITY_ID, MENU_ID)`

FK:

- `AUTHORITY_ID → PM_AUTHORITY.AUTHORITY_ID`
- `MENU_ID → PM_MENU.MENU_ID`

상위 폴더는 매핑하지 않아도 서비스가 허용된 자식의 조상 메뉴를 화면 트리에 보존합니다.

### 7.7 PM_PROGRAM_ACTION

테이블 코멘트: `메뉴 및 프로그램별 기능 마스터`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `MENU_ID` | `VARCHAR2(50)` | Y |  | PK, FK | 메뉴 ID |
| `PROGRAM_ID` | `VARCHAR2(50)` | Y |  | PK | 프로그램 ID |
| `ACTION_ID` | `VARCHAR2(50)` | Y |  | PK | 기능 ID |
| `ACTION_NAME` | `VARCHAR2(100)` | Y |  |  | 기능 표시명 |
| `COMPONENT_ID` | `VARCHAR2(100)` | N |  |  | 화면 컴포넌트 ID |
| `SORT_ORDER` | `NUMBER(5)` | Y | `999` | CHECK | 기능 표시 순서 |
| `ACTIVE_YN` | `CHAR(1)` | Y | `'Y'` | CHECK | 기능 활성 여부 |
| `DESCRIPTION` | `VARCHAR2(500)` | N |  |  | 기능 설명 |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |
| `UPDATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 수정 일시 |
| `UPDATED_BY` | `VARCHAR2(100)` | Y |  |  | 수정자 ID |

PK: `(MENU_ID, PROGRAM_ID, ACTION_ID)`

FK: `MENU_ID → PM_MENU.MENU_ID`

제약조건:

- `SORT_ORDER BETWEEN 0 AND 9999`
- `ACTIVE_YN IN ('Y', 'N')`

권한 판정은 `ACTION_ID` 단독이 아니라 PK 전체 문맥으로 수행합니다.

### 7.8 PM_AUTH_ACTION

테이블 코멘트: `권한별 프로그램 기능 허용 매핑`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `AUTHORITY_ID` | `VARCHAR2(50)` | Y |  | PK, FK | 권한 ID |
| `MENU_ID` | `VARCHAR2(50)` | Y |  | PK, FK | 메뉴 ID |
| `PROGRAM_ID` | `VARCHAR2(50)` | Y |  | PK, FK | 프로그램 ID |
| `ACTION_ID` | `VARCHAR2(50)` | Y |  | PK, FK | 기능 ID |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |

PK: `(AUTHORITY_ID, MENU_ID, PROGRAM_ID, ACTION_ID)`

FK:

- `AUTHORITY_ID → PM_AUTHORITY.AUTHORITY_ID`
- `(MENU_ID, PROGRAM_ID, ACTION_ID) → PM_PROGRAM_ACTION`

권장 인덱스: `(MENU_ID, PROGRAM_ID, ACTION_ID, AUTHORITY_ID)`

### 7.9 PM_FAVORITE_MENU

테이블 코멘트: `사용자 관심 메뉴`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `USERNAME` | `VARCHAR2(100)` | Y |  | PK, FK | 사용자명 |
| `MENU_ID` | `VARCHAR2(50)` | Y |  | PK, FK | 관심 메뉴 ID |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |

PK: `(USERNAME, MENU_ID)`

FK:

- `USERNAME → PM_USER_PROFILE.USERNAME`
- `MENU_ID → PM_MENU.MENU_ID`

저장된 관심 메뉴는 편의 데이터일 뿐입니다. 조회와 등록 시 현재 메뉴 접근 권한을 다시 확인합니다.

### 7.10 PM_CODE_GROUP

테이블 코멘트: `공통코드 그룹 마스터 및 변경 버전`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `GROUP_CODE` | `VARCHAR2(30)` | Y |  | PK | 공통코드 그룹 코드 |
| `GROUP_NAME` | `VARCHAR2(100)` | Y |  |  | 공통코드 그룹명 |
| `ACTIVE_YN` | `CHAR(1)` | Y | `'Y'` | CHECK | 그룹 활성 여부 |
| `VERSION_NO` | `NUMBER(19)` | Y | `1` | CHECK | 그룹 변경 버전 |
| `DESCRIPTION` | `VARCHAR2(500)` | N |  |  | 그룹 설명 |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |
| `UPDATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 수정 일시 |
| `UPDATED_BY` | `VARCHAR2(100)` | Y |  |  | 수정자 ID |

제약조건:

- `ACTIVE_YN IN ('Y', 'N')`
- `VERSION_NO >= 1`

공통코드 저장과 `VERSION_NO` 증가는 같은 트랜잭션에서 수행합니다.

### 7.11 PM_COMMON_CODE

테이블 코멘트: `계층 및 적용 기간 공통코드`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `GROUP_CODE` | `VARCHAR2(30)` | Y |  | PK, FK | 공통코드 그룹 코드 |
| `CODE` | `VARCHAR2(30)` | Y |  | PK | 코드 |
| `CODE_NAME` | `VARCHAR2(100)` | Y |  |  | 코드명 |
| `PARENT_CODE` | `VARCHAR2(30)` | N |  | 자기 FK | 같은 그룹의 상위 코드 |
| `SORT_ORDER` | `NUMBER(5)` | Y | `999` | CHECK | 표시 순서 |
| `ACTIVE_YN` | `CHAR(1)` | Y | `'Y'` | CHECK | 코드 활성 여부 |
| `VALID_FROM` | `DATE` | Y |  | CHECK | 적용 시작일 |
| `VALID_TO` | `DATE` | Y |  | CHECK | 적용 종료일 |
| `DESCRIPTION` | `VARCHAR2(500)` | N |  |  | 코드 설명 |
| `CREATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 등록 일시 |
| `CREATED_BY` | `VARCHAR2(100)` | Y |  |  | 등록자 ID |
| `UPDATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 수정 일시 |
| `UPDATED_BY` | `VARCHAR2(100)` | Y |  |  | 수정자 ID |

PK: `(GROUP_CODE, CODE)`

FK:

- `GROUP_CODE → PM_CODE_GROUP.GROUP_CODE`
- `(GROUP_CODE, PARENT_CODE) → PM_COMMON_CODE(GROUP_CODE, CODE)`

제약조건:

- `CODE <> PARENT_CODE`
- `SORT_ORDER BETWEEN 0 AND 9999`
- `ACTIVE_YN IN ('Y', 'N')`
- `VALID_TO >= VALID_FROM`

권장 인덱스: `(GROUP_CODE, PARENT_CODE, ACTIVE_YN, VALID_FROM, VALID_TO, SORT_ORDER, CODE)`

간접 순환 참조는 저장 서비스에서 별도로 차단합니다.

### 7.12 PM_DOMAIN_VERSION

테이블 코멘트: `권한 및 메뉴 기준정보 변경 버전`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `DOMAIN_CODE` | `VARCHAR2(30)` | Y |  | PK | 버전 관리 도메인 코드 |
| `VERSION_NO` | `NUMBER(19)` | Y | `1` | CHECK | 현재 변경 버전 |
| `UPDATED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` |  | 최종 변경 일시 |
| `UPDATED_BY` | `VARCHAR2(100)` | Y |  |  | 최종 변경자 ID |

도메인 예시:

- `AUTHORITY`
- `MENU`
- `PROGRAM_ACTION`

마스터나 매핑 변경 시 해당 도메인 버전을 같은 트랜잭션에서 증가시킵니다. 애플리케이션은 버전을 캐시 키 또는 무효화 판단 기준으로 사용할 수 있습니다.

### 7.13 PM_AUDIT_EVENT

테이블 코멘트: `인증·인가·관리 데이터 변경 감사 이력`

| 컬럼 | 자료형 | 필수 | 기본값 | 키 | 컬럼 코멘트 |
|---|---|---:|---|---|---|
| `AUDIT_ID` | `NUMBER(19)` | Y | 시퀀스 | PK | 감사 이벤트 ID |
| `EVENT_TYPE` | `VARCHAR2(50)` | Y |  |  | 이벤트 유형 |
| `ACTOR` | `VARCHAR2(100)` | Y |  |  | 행위자 사용자명 |
| `ORG_ID` | `VARCHAR2(30)` | N |  |  | 발생 당시 조직 ID |
| `TARGET_TYPE` | `VARCHAR2(50)` | Y |  |  | 대상 유형 |
| `TARGET_ID` | `VARCHAR2(200)` | N |  |  | 대상 식별자 |
| `RESULT_CODE` | `VARCHAR2(20)` | Y |  | CHECK | 처리 결과 |
| `CLIENT_IP` | `VARCHAR2(45)` | N |  |  | 요청 클라이언트 IP |
| `TRACE_ID` | `VARCHAR2(64)` | N |  | 인덱스 | 요청 추적 ID |
| `OCCURRED_AT` | `TIMESTAMP(6)` | Y | `SYSTIMESTAMP` | 인덱스 | 발생 일시 |
| `DETAILS_JSON` | `CLOB` | N |  |  | 민감정보를 제외한 상세 JSON |

제약조건: `RESULT_CODE IN ('SUCCESS', 'FAILED', 'DENIED')`

권장 인덱스:

- `(OCCURRED_AT DESC, AUDIT_ID DESC)`
- `(ACTOR, OCCURRED_AT DESC)`
- `(EVENT_TYPE, OCCURRED_AT DESC)`
- `(TRACE_ID)`

행위자와 조직은 역사적 사실 보존을 위해 사용자·조직 테이블 FK를 강제하지 않습니다. 사용자가 삭제되거나 조직이 변경돼도 기존 감사 이력이 남아야 합니다.

`DETAILS_JSON`에는 비밀번호, 세션 ID, CSRF 토큰, 인증 헤더, 주민등록번호 등 민감정보를 저장하지 않습니다.

## 8. 시퀀스 설계

| 시퀀스 | 사용 테이블 | 시작값 | 증가값 | 캐시 권장 |
|---|---|---:|---:|---:|
| `SQ_PM_USER_AUTH_HIST` | `PM_USER_AUTH_HIST.ASSIGNMENT_ID` | 1 | 1 | 100 |
| `SQ_PM_AUDIT_EVENT` | `PM_AUDIT_EVENT.AUDIT_ID` | 1 | 1 | 100 |

시퀀스 값은 업무적 연속성을 의미하지 않으며 삭제나 롤백으로 빈 번호가 발생할 수 있습니다.

## 9. 초기 데이터 설계

### 9.1 조직

| ORG_ID | ORG_NAME | 용도 |
|---|---|---|
| `HQ` | 본부 | 기본 데모 조직 |
| `BRANCH_A` | 지점 A | 과거 조직 권한 제외 시험 |
| `BRANCH_B` | 지점 B | 이동 후 현재 조직 시험 |

### 9.2 사용자 프로필

| USERNAME | DISPLAY_NAME | CURRENT_ORG_ID | 로그인 계정 여부 | 용도 |
|---|---|---|---:|---|
| `admin` | 시스템 관리자 | `HQ` | Y | 전체 권한 |
| `manager` | 콘텐츠 관리자 | `HQ` | Y | 콘텐츠 직접 권한과 위임 원천 |
| `viewer` | 조회 사용자 | `HQ` | Y | 조회 권한과 대기 위임 |
| `delegate` | 위임 사용자 | `HQ` | Y | 유효 위임 |
| `expired` | 만료 위임 사용자 | `HQ` | Y | 만료 위임 |
| `revoked` | 회수 사용자 | `HQ` | N | 최신 회수 이력 시험 |
| `moved` | 조직 이동 사용자 | `BRANCH_B` | N | 이전 조직 권한 제외 시험 |
| `revokedManager` | 회수된 관리자 | `HQ` | N | 위임 원천 회수 시험 |
| `orphanDelegate` | 원천 없는 위임 사용자 | `HQ` | N | 고아 위임 제외 시험 |

로그인 계정 여부가 N인 행도 서비스 단위 테스트용 권한 데이터입니다. 실제 인증 저장소에는 최초 5개 계정만 데모 사용자로 구성합니다.

### 9.3 권한 마스터

| AUTHORITY_ID | AUTHORITY_NAME | ACTIVE_YN |
|---|---|---|
| `AUTH_SYSTEM_ADMIN` | 시스템 관리자 | Y |
| `AUTH_CONTENT_MANAGER` | 콘텐츠 관리자 | Y |
| `AUTH_VIEWER` | 조회 사용자 | Y |
| `AUTH_DISABLED` | 사용 중지 권한 | N |

### 9.4 권한 이력

모든 일반 적용 기간은 `2020-01-01`부터 `2099-12-31`까지로 설정합니다.

| 사용자 | 조직 | 권한 | 유형 | 상태 | 기간/순번 | 원천 사용자 |
|---|---|---|---|---|---|---|
| admin | HQ | AUTH_SYSTEM_ADMIN | DIRECT | APPROVED | 일반/1 |  |
| manager | HQ | AUTH_CONTENT_MANAGER | DIRECT | APPROVED | 일반/1 |  |
| viewer | HQ | AUTH_VIEWER | DIRECT | APPROVED | 일반/1 |  |
| delegate | HQ | AUTH_VIEWER | DIRECT | APPROVED | 일반/1 |  |
| delegate | HQ | AUTH_CONTENT_MANAGER | DELEGATED | APPROVED | 일반/1 | manager |
| expired | HQ | AUTH_VIEWER | DIRECT | APPROVED | 일반/1 |  |
| expired | HQ | AUTH_SYSTEM_ADMIN | DELEGATED | APPROVED | 2023-01-01~2023-12-31/1 | admin |
| viewer | HQ | AUTH_CONTENT_MANAGER | DELEGATED | PENDING | 일반/1 | manager |
| admin | HQ | AUTH_DISABLED | DIRECT | APPROVED | 일반/1 |  |
| revoked | HQ | AUTH_SYSTEM_ADMIN | DIRECT | APPROVED | 일반/1 |  |
| revoked | HQ | AUTH_SYSTEM_ADMIN | DIRECT | REVOKED | 일반/2 |  |
| moved | BRANCH_A | AUTH_SYSTEM_ADMIN | DIRECT | APPROVED | 일반/1 |  |
| revokedManager | HQ | AUTH_CONTENT_MANAGER | DIRECT | APPROVED | 일반/1 |  |
| revokedManager | HQ | AUTH_CONTENT_MANAGER | DIRECT | REVOKED | 일반/2 |  |
| orphanDelegate | HQ | AUTH_VIEWER | DIRECT | APPROVED | 일반/1 |  |
| orphanDelegate | HQ | AUTH_CONTENT_MANAGER | DELEGATED | APPROVED | 일반/1 | revokedManager |

### 9.5 메뉴

| MENU_ID | PARENT_MENU_ID | MENU_NAME | MENU_PATH | 순서 | 활성 | 표시 | 공용 |
|---|---|---|---|---:|---:|---:|---:|
| `HOME` |  | 홈 | `#home` | 10 | Y | Y | Y |
| `CONTENT` |  | 콘텐츠 |  | 20 | Y | Y | N |
| `CONTENT_LIST` | CONTENT | 콘텐츠 조회 | `#content` | 10 | Y | Y | N |
| `SYSTEM` |  | 시스템 관리 |  | 30 | Y | Y | N |
| `SYSTEM_AUTH` | SYSTEM | 권한 관리 | `#authority` | 10 | Y | Y | N |
| `SYSTEM_MENU` | SYSTEM | 메뉴 관리 | `#menu` | 20 | Y | Y | N |
| `SYSTEM_CODE` | SYSTEM | 공통코드 관리 | `#common-code` | 30 | Y | Y | N |
| `HIDDEN_MENU` | SYSTEM | 숨김 메뉴 | `#hidden` | 99 | Y | N | N |

### 9.6 권한-메뉴 매핑

| 권한 | 메뉴 |
|---|---|
| `AUTH_SYSTEM_ADMIN` | `CONTENT_LIST`, `SYSTEM_AUTH`, `SYSTEM_MENU`, `SYSTEM_CODE`, `HIDDEN_MENU` |
| `AUTH_CONTENT_MANAGER` | `CONTENT_LIST` |
| `AUTH_VIEWER` | `CONTENT_LIST` |

### 9.7 프로그램 기능

| MENU_ID | PROGRAM_ID | ACTION_ID | ACTION_NAME | COMPONENT_ID | 순서 | 활성 |
|---|---|---|---|---|---:|---:|
| CONTENT_LIST | CONTENT | CONTENT_READ | 조회 | btnContentRead | 10 | Y |
| CONTENT_LIST | CONTENT | CONTENT_SAVE | 저장 | btnContentSave | 20 | Y |
| CONTENT_LIST | CONTENT | CONTENT_PUBLISH | 게시 | btnContentPublish | 30 | Y |
| SYSTEM_AUTH | AUTHORITY | AUTHORITY_READ | 권한 현황 조회 | btnAuthorityRead | 10 | Y |
| SYSTEM_AUTH | AUTHORITY | AUTHORITY_UPDATE | 권한 변경 | btnAuthorityUpdate | 20 | Y |
| SYSTEM_CODE | COMMON_CODE | COMMON_CODE_SAVE | 공통코드 저장 | btnCommonCodeSave | 10 | Y |
| CONTENT_LIST | CONTENT | CONTENT_DELETE_OLD | 폐기 기능 | btnOldDelete | 99 | N |

### 9.8 권한-기능 매핑

| 권한 | 허용 기능 |
|---|---|
| `AUTH_SYSTEM_ADMIN` | 전체 7개 기능 매핑. 단, 비활성 `CONTENT_DELETE_OLD`는 최종 허용에서 제외 |
| `AUTH_CONTENT_MANAGER` | `CONTENT_READ`, `CONTENT_SAVE`, `CONTENT_PUBLISH` |
| `AUTH_VIEWER` | `CONTENT_READ` |

### 9.9 공통코드 그룹

| GROUP_CODE | GROUP_NAME | 초기 VERSION_NO |
|---|---|---:|
| `USE_YN` | 사용 여부 | 2 |
| `ARTICLE_STATUS` | 게시물 상태 | 3 |
| `AUTH_STATUS` | 권한 승인 상태 | 3 |
| `REGION` | 지역 | 5 |

초기 버전은 현재 인메모리 서비스가 그룹에 코드를 등록할 때마다 증가시키는 동작과 맞춘 값입니다. 실제 운영에서는 초기 적재 완료 후 모두 1로 시작해도 되지만 애플리케이션과 운영 기준을 하나로 정해야 합니다.

### 9.10 공통코드 항목

적용 기간은 모두 `2000-01-01`부터 `2099-12-31`까지입니다.

| 그룹 | 코드 | 코드명 | 상위 코드 | 순서 | 활성 |
|---|---|---|---|---:|---:|
| USE_YN | Y | 사용 |  | 10 | Y |
| USE_YN | N | 미사용 |  | 20 | Y |
| ARTICLE_STATUS | DRAFT | 작성 중 |  | 10 | Y |
| ARTICLE_STATUS | PUBLISHED | 게시 |  | 20 | Y |
| ARTICLE_STATUS | DELETED | 삭제 |  | 30 | N |
| AUTH_STATUS | PENDING | 승인 대기 |  | 10 | Y |
| AUTH_STATUS | APPROVED | 승인 |  | 20 | Y |
| AUTH_STATUS | REVOKED | 회수 |  | 30 | Y |
| REGION | SEOUL | 서울 |  | 10 | Y |
| REGION | BUSAN | 부산 |  | 20 | Y |
| REGION | GANGNAM | 강남구 | SEOUL | 10 | Y |
| REGION | MAPO | 마포구 | SEOUL | 20 | Y |
| REGION | HAEUNDAE | 해운대구 | BUSAN | 10 | Y |

### 9.11 도메인 버전

| DOMAIN_CODE | VERSION_NO |
|---|---:|
| `AUTHORITY` | 1 |
| `MENU` | 1 |
| `PROGRAM_ACTION` | 1 |

관심 메뉴와 감사 이벤트는 초기 데이터를 넣지 않습니다. 각 사용자 행동에 따라 생성해야 합니다.

## 10. Repository 또는 Mapper 계약

### 10.1 사용자와 유효 권한

필요 메서드 예시:

- `findUserProfile(username)`
- `findLatestAssignments(username, currentOrgId)`
- `existsValidDirectAuthority(delegatedBy, orgId, authorityId, baseDate)`
- `findActiveAuthority(authorityId)`

최신 권한 이력 조회는 `ROW_NUMBER()` 분석 함수로 사용자·조직·권한·유형 그룹의 최신 순번만 선택하는 방식을 권장합니다.

### 10.2 메뉴와 기능

필요 메서드 예시:

- `findActiveMenus()`
- `findMenuIdsByAuthorityIds(authorityIds)`
- `findActiveProgramActions()`
- `findActionKeysByAuthorityIds(authorityIds)`
- `findDomainVersions()`

빈 권한 목록을 SQL `IN ()`으로 전달하지 않고 빈 결과로 즉시 반환합니다.

### 10.3 공통코드

필요 메서드 예시:

- `findCodeGroup(groupCode)`
- `findCommonCodes(groupCode, activeOnly, parentCode, baseDate)`
- `insertCommonCode(item)`
- `updateCommonCode(item)`
- `incrementGroupVersion(groupCode)`

저장은 서비스 트랜잭션 안에서 코드 저장과 버전 증가를 완료한 뒤 캐시를 무효화합니다.

### 10.4 관심 메뉴와 감사

필요 메서드 예시:

- `findFavoriteMenuIds(username)`
- `insertFavorite(username, menuId)`
- `deleteFavorite(username, menuId)`
- `insertAuditEvent(event)`
- `findAuditEvents(page, size, filters)`

관심 메뉴 INSERT는 PK 중복을 멱등 성공으로 취급할지 409로 처리할지 API 정책을 먼저 결정합니다. 현재 인메모리 구현은 중복 등록을 성공으로 처리합니다.

## 11. 트랜잭션 설계

### 권한 부여 또는 회수

1. 현재 사용자와 관리자 권한 확인
2. 최신 `HISTORY_SEQ` 잠금 조회
3. 다음 순번의 이력 INSERT
4. 감사 이벤트 INSERT
5. 권한 도메인 버전 증가
6. COMMIT 후 관련 캐시 무효화

### 공통코드 저장

1. 그룹 행 잠금
2. 입력과 상위 코드 관계 검증
3. 코드 INSERT 또는 UPDATE
4. 그룹 버전 증가
5. 감사 이벤트 INSERT
6. COMMIT 후 그룹 캐시 무효화

### 관심 메뉴 등록

1. 현재 메뉴 접근 가능 여부 조회
2. 경로·표시·활성 상태 확인
3. 관심 메뉴 INSERT
4. 감사 이벤트 INSERT

DB 트랜잭션 안에서 외부 캐시를 직접 갱신하지 않습니다. 커밋 완료 이벤트 또는 버전 기반 조회를 사용합니다.

## 12. 데이터 정합성 점검 쿼리 항목

운영 점검에서는 다음 이상 데이터를 조회해야 합니다.

- 존재하지 않는 권한을 참조하는 권한 이력
- 현재 조직과 다른 조직의 권한만 가진 사용자
- 위임 원천 사용자가 없거나 원천 직접 권한이 회수된 위임
- 종료일이 시작일보다 빠른 권한 또는 공통코드
- 존재하지 않는 부모를 참조하는 메뉴 또는 공통코드
- 메뉴 및 공통코드의 순환 참조
- 비활성 권한·메뉴·기능에 남은 매핑
- 동일 사용자·조직·권한·유형·순번 중복
- 프로그램 기능 마스터가 없는 권한-기능 매핑
- 사용자 권한이 없는 관심 메뉴
- traceId나 행위자가 누락된 감사 이벤트

정합성 점검은 조회 전용으로 실행하고 자동 DELETE나 보정 UPDATE를 연결하지 않습니다.

## 13. 전환 단계

### 1단계: 스키마 검토

- Oracle 버전과 사내 명명 규칙 확인
- 테이블스페이스와 스키마 계정 결정
- 개인정보 및 감사 보존 정책 승인
- 인덱스와 예상 데이터량 검토

### 2단계: 개발 DB 생성

- DBA가 승인된 DDL 실행
- 코멘트와 제약조건 생성 여부 확인
- 초기 기준 데이터 적재
- 애플리케이션 계정 권한 부여

### 3단계: 읽기 전환

- `AuthorizationCatalog` 읽기를 Repository로 교체
- 기존 인메모리 결과와 DB 결과를 테스트에서 비교
- 메뉴와 기능 부트스트랩 결과 비교
- 공통코드 읽기와 버전 확인

### 4단계: 쓰기 전환

- 관심 메뉴 저장
- 공통코드 저장과 버전 증가
- 감사 이벤트 영구 저장
- 트랜잭션 롤백과 중복 요청 시험

### 5단계: 운영 준비

- 인메모리 초기 데이터 제거
- 비밀값 외부화
- DB 장애 시 fail-closed 동작 확인
- 성능, 잠금, 캐시, 감사 보존 시험
- 백업과 복구 절차 확정

## 14. DBA 전달 체크리스트

- [ ] Oracle 버전과 문자 집합을 확인했다.
- [ ] 전용 스키마와 애플리케이션 계정을 분리했다.
- [ ] 13개 테이블과 2개 시퀀스의 명명 규칙을 승인했다.
- [ ] 모든 PK, FK, UNIQUE, CHECK 제약조건을 검토했다.
- [ ] 권한·메뉴·감사 조회용 인덱스를 검토했다.
- [ ] 모든 테이블과 컬럼에 한글 코멘트가 있다.
- [ ] 데모 사용자 비밀번호를 DB에 저장하지 않는다.
- [ ] 초기 권한과 메뉴 매핑이 현재 소스와 일치한다.
- [ ] 감사 테이블에 애플리케이션 DELETE 권한을 부여하지 않는다.
- [ ] DDL과 초기 데이터는 검토 후 DBA가 직접 실행한다.
- [ ] 실행 전 백업 또는 독립 개발 스키마를 준비했다.
- [ ] 실행 결과와 객체 목록을 별도 변경 이력에 남긴다.
