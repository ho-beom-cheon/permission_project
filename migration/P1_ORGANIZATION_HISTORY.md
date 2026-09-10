# P1 두 번째 실행 — 조직 대응표와 권한 처리 이력

작성일: 2026-09-03. 첫 실행의 80개 테스트 통과 상태 위에 적용한 작업 트리 변경이다. 원본 소스는 읽기만 했고 원본 DB는 사용하지 않았다.

## 구현 결과

### 명시적 SSO 조직 대응표

- `OrganizationMappingService`가 `(ssoOfficeCode, ssoDepartmentCode)` 복합키와 SSO 명칭, 업무 사업소·부서, 사용 여부, 행 버전을 별도 저장한다.
- 동일 업무 부서에 여러 SSO 조직을 연결할 수 있다. 업무 조직이 미지정인 SSO 행도 저장한다.
- 지정한 사업소·부서가 실제 디렉터리에 있는지와 같은 사업소 소속인지 검증한다. 수정 시 조회 버전이 다르면 충돌로 거부한다.
- `GET/POST /api/admin/organization-mappings`는 기존 관리자 읽기·수정 권한을 요구한다. 관리 화면에 **SSO 조직 대응** 항목을 추가했다.
- 기존 Office/Department/Person의 저장 형식과 사용자의 organizationId, 권한 이력은 변경하지 않는다. 초기 대응표는 빈 목록이며 HQ와 원본 조직 코드를 자동 연결하지 않는다.

원본 근거:

| 출처 | 확인한 계약 |
|---|---|
| `src/main/resources/mappers/arisuinfo/cm/offceMngMapper.xml`, `searchDeptCd` 166행 | SSO 코드·명칭과 업무 사업소·부서 코드는 별도 필드 |
| `src/main/resources/mappers/arisuinfo/cm/mainMngMapper.xml`, 68행 | SSO사업소+SSO부서로 조직행 식별 |
| `src/main/resources/mappers/arisuinfo/cm/usr/usrMngMapper.xml`, `searchUsr` 29행 | 업무 사업소 매핑·SSO사업소·SSO부서 조건 구분 |
| `src/main/resources/mappers/arisuinfo/cm/loginMapper.xml`, `searchCheckBonbuList` 185행 | 본부 여부는 활성 CM087/BONBU 하위 코드의 명칭과 사용자 SSO사업소코드를 비교 |
| `src/main/java/arisuinfo/cm/usr/service/impl/UsrMngServiceImpl.java`, `resolveOfficeCd` 160행 | 비본부는 세션 사업소 강제, 다른 사업소 요청·소속 누락 거부 |

위 원본 경로는 모두 `C:/arisuinfo` 기준이다. 사용자별 SSO 소속·본부 공통코드 판정과 실제 사용자 조회 범위 연결은 후속 작업이다. 대응표 관리 기능 구현을 SSO 인증 연계 완료로 해석하지 않는다.

### 권한별 처리 이벤트 저장과 조회

- `AuthorityHistoryService`가 새로 발생한 신청·승인·반려·직접/위임 부여·개별/일괄 회수 및 CHANGE 승인 중 회수를 권한별로 기록한다.
- 이벤트에는 사용자·발생 당시 organizationId·권한·처리 상태·신청 구분·신청 번호·부여 이력 순번·등록/처리 시각·처리자·사유·기간을 보존한다.
- 승인/반려가 발생해도 앞선 신청 이벤트를 보존한다. 신청 이벤트의 처리 시각은 비워 둔다. 등록 시각과 이벤트 번호의 역순으로 조회한다.
- `GET /api/me/authority-history`는 세션 사용자만 조회한다. `GET /api/admin/authority-history`는 읽기 권한과 사용자·상태 필터를 지원한다.
- **권한 처리 이력** 화면을 추가하고 신청·심사 화면에서 연결했다. 본인 이력과 관리자 사용자·상태별 조회, 페이징을 제공한다.
- 저장 키는 `authority-history`이며 업무·감사와 같은 트랜잭션에서 저장된다. 적용 전의 신청/부여 데이터는 기존 저장소에 그대로 남긴다. 알 수 없는 과거 처리 시각·행위자를 만들어 새 이벤트로 채우지 않는다. 화면에 기록 시작 범위를 명시했다.

이 저장소는 이번 단계에서 **조회용 처리 이력**이다. 유효 권한 판정, 기존 CHANGE 동작, 승인 기간, 기준 상태 비교는 아직 바꾸지 않았다.

## 원본과의 차이 및 다음 전환 조건

원본은 요청·처리를 한 테이블에서 관리하고 최신 상태를 실제 권한 판정에 사용한다. 현재 분리된 요청·부여 상태를 전환할 때 아래 조건을 함께 다뤄야 한다.

| 원본 규칙 | 원본 근거 | 남은 작업 |
|---|---|---|
| 모든 상태 중 현재 사업소·부서의 최신 행을 먼저 선택한 뒤 승인·기간 검사 | `authUsrMapper.xml`, `selectLoginAuthUsrInfo` 782/826~853행 | 신규 이력을 인가의 원천으로 전환; 최신 신청·반려가 과거 승인을 가리는 규칙 적용 |
| 변경은 단일 대상 권한의 CHNG 신청이며 이미 유효한 보유 권한 신청도 차단 | `UsrMngServiceImpl.java` 661~695행 | 현재 복수 대상·보유 유지 CHANGE와의 차이 해소 |
| 승인 시작일은 처리 당일, 종료일은 99991231 | `UsrMngServiceImpl.java` 413~449행 | 현재 요청 기간 유지와의 차이 해소 |
| 반려 시작·종료일은 처리 당일 | `UsrMngServiceImpl.java` 455~465행 | 현행 신청 기간과 구분된 처리 기간 적용 |
| 변경 승인 시 같은 시스템·현재 조직의 현재 유효한 직접 승인 권한 회수 | `UsrMngServiceImpl.java` 421~449/596~628행 | 만료·미래 권한의 회수 범위와 신청 baseline을 함께 변경 |
| 회수는 최신 승인에서만 가능, 사유 필수, 종료일 당일 | `authUsrMapper.xml` 432~489행, `UsrMngServiceImpl.java` 468~483행 | 개별 회수 사유 입력과 전이·기간 규칙 보완 |
| 최신 반려·회수의 사유 정정은 동일 행 수정 | `authUsrMapper.xml`, `updateAuthUsrReturnRsn` 615~645행 | 사유 정정 기능과 조회 표현 추가 |
| 사용자 전 조직의 직접 이력 조회, 현재 마스터 명칭 결합 | `authUsrMapper.xml`, `selectAuthUsrHstryDtls` 160~219행; `cmAuthHstryP.xfdl` 28~47행 | 과거 저장 이력의 정확한 연결, 별도 사업소·부서·현재 명칭 표시 |

위 `authUsrMapper.xml`은 `src/main/resources/mappers/arisuinfo/cm/auth/auth/`, `UsrMngServiceImpl.java`는 `src/main/java/arisuinfo/cm/usr/service/impl/`, 화면은 `src/main/nexacro/form/cm/user/` 아래다.

새 journal에 PENDING/REJECTED를 넣는 것만으로 현재 assignments를 바꾸면 기존 CHANGE의 보유 권한과 baseline이 달라진다. 다음 구현에서는 대상·기간·전이·최신 판정·저장 호환을 하나의 작업 묶음으로 검증한다. 과거 직접 부여/회수에 없는 처리 시각·사유는 불명으로 유지하며 과거 데이터를 정렬 가능한 신규 사건으로 추정하지 않는다.

## 검증

- 전체 자동 테스트 **92개 통과**, 실패·오류·건너뜀 없음. 이전 80개 대비 조직 대응 6개, 권한 이력 4개, 저장 호환·복원·롤백 2개 추가.
- 새 저장 키가 없는 가상 파일 DB를 다시 열어 기존 요청·카탈로그가 유지되고 신규 이력이 임의 생성되지 않음을 확인했다.
- 조직 대응 등록이 사용자의 카탈로그·권한을 변경하지 않으며, 새 조직 대응과 신청→승인→회수가 DB 재시작 후 그대로 복원되는지 확인했다.
- 조직 대응과 권한 신청·이력 저장 후 강제 실패시켜 실행 상태·DB 내용·revision이 모두 복원되는지 확인했다.
- 본인 조회에 다른 username을 보내도 세션 사용자만 반환하고, 일반 사용자의 관리자 이력 접근은 거절됨을 확인했다.
- 브라우저: 별도 메모리 DB의 18081 서버에서 SSO 대응표 등록·수정, 권한 신청→반려, 신청/반려 두 행 보존, 본인 이력 및 관리자 사용자+반려 조건 조회를 확인했다. 해당 화면 배치도 확인했다.
- JavaScript 구문·변경 공백 검사를 통과했다. 전체 신규 화면의 반응형 검증이나 실제 SSO 시험을 완료한 것은 아니다.
- 검증용 서버·탭은 종료했다. 기존 8080 서버는 재시작하거나 재배포하지 않았다.

```powershell
mvn -o '-Dmaven.repo.local=C:\workspace' --batch-mode --no-transfer-progress test
```

로그: 로컬 `target/migration-next-test.log`, `target/surefire-reports`. 운영 연결 없이 합성 데이터만 사용했다.

## 다음 실행 순서

1. 사용자별 SSO소속과 CM087/BONBU 판정 연결 → 업무 사업소 범위의 서버 적용.
2. 원본 CHANGE 대상·처리기간·신청/처리 전이를 정리 → 통합 최신 상태와 baseline 동시 전환.
3. 기존 데이터에서 복원 가능한 신청/처리와 시각 불명의 부여/회수를 구분 → 전체 이력 조회에 연결.
4. 원본 권한별 대기 신청·연계 AI/AB 권한 규칙, 부서·최신 권한 조건 조회를 후속 구현.

전체 컨트롤러 동등성 확인은 여전히 0개다. 이번 구현·테스트를 전체 이관 완료로 집계하지 않는다.
