# P8 운영 Directory bootstrap 이관

## 범위

단계 8은 운영 원본의 사업소·부서·SSO 조직 대응·담당지역·사용자·사용자 담당지역과 `CM008` 직급 코드를 새 저장소에 원자적으로 upsert하는 경로다. 실제 원본 값은 저장소에 포함하지 않는다. 승인된 환경에서 `migration/sql/export_directory_bootstrap.sql`을 읽기 전용으로 실행하고 결과를 JSON으로 조립한 뒤 관리 화면에서 미리보기와 서버 검증을 거쳐 적재한다.

이 경로는 로컬 계정 비밀번호, 권한 부여, 권한 신청·처리 이력, 약관 동의 이력을 입력으로 받지 않는다. 기존 사용자 행을 갱신해도 이 자료는 삭제하거나 다시 만들지 않는다. 요청에 없는 Directory 행도 삭제하거나 비활성화하지 않는다.

## 요청 계약

관리자 API는 `POST /api/admin/directory/import`이며 `SYSTEM_AUTH / AUTHORITY / AUTHORITY_UPDATE` 권한과 CSRF 토큰이 필요하다. 최상위 JSON은 다음 모양이다. `rankGroup`만 단일 객체 또는 `null`이고 나머지는 배열이다.

```json
{
  "offices": [],
  "departments": [],
  "organizationMappings": [],
  "regions": [],
  "people": [],
  "userRegions": [],
  "rankGroup": null,
  "rankCodes": []
}
```

행 필드는 다음과 같다.

| 필드 | 행 계약 |
|---|---|
| `offices` | `code`, `name`, `abbreviation`, `address`, `telephone`, `useFlag` |
| `departments` | `code`, `officeCode`, `name`, `description`, `useFlag` |
| `organizationMappings` | `ssoOfficeCode`, `ssoDepartmentCode`, `ssoOfficeName`, `ssoDepartmentName`, `officeCode`, `departmentCode`, `useFlag` |
| `regions` | `code`, `officeCode`, `districtCode`, `districtName`, `name`, `useFlag` |
| `people` | `username`, `name`, `officeCode`, `departmentCode`, `ssoOfficeCode`, `ssoDepartmentCode`, `rankCode`, `telephone`, `mobile`, `email`, `jobDescription`, `useFlag` |
| `userRegions` | `username`, `regionCode` |
| `rankGroup` | `name`, `description`, `useFlag` |
| `rankCodes` | `code`, `name`, `description`, `sortOrder`, `useFlag`, `screen1`~`screen5`, `substituteName`, `screenDescription` |

`people`의 SSO 사업소·부서는 한 쌍으로 제공하며 별도 affiliation 배열은 없다. 서버가 해당 두 값으로 `UserSsoOrganizationService` 소속도 함께 갱신한다. `useFlag`와 `screen1`~`screen5`는 원본 `Y`/`N`을 그대로 내보내며, `NULL`을 임의 값으로 보정하지 않는다.

## 검증과 적용 원칙

서버는 모든 행과 요청 내 자연키 중복, 부모 참조, 활성 참조, SSO 대응, 사용자 담당지역을 적용 전에 검사한다. 실패하면 Directory, 공통코드, 조직 대응, SSO 소속, 권한 버전과 감사 이력을 모두 이전 상태로 되돌린다.

활성 사용자는 활성 사업소·부서·직급·담당지역과 활성 SSO 대응을 참조해야 한다. 비활성 사용자는 폐지된 조직·지역·SSO 대응을 과거 값으로 보존할 수 있지만 존재하지 않는 키를 참조할 수는 없다.

검증된 Directory 최종 그래프를 먼저 반영하고 변경된 사용자의 권한 프로필 조직·활성 상태만 동기화한다. 이어 `CM008` 그룹·상세, SSO 조직 대응, 사용자 SSO 소속 순으로 차이만 반영한다. 사용자 담당지역은 사용자 최종 행의 완전한 집합으로 함께 저장한다. 현재 값과 같은 행은 저장 호출을 생략해 같은 JSON 재실행 시 버전과 감사 이력이 증가하지 않아야 한다. 기존 비밀번호 해시와 권한·신청·처리·동의 이력은 해당 서비스의 상태를 그대로 보존한다.

원본 `usrRgnMapper.xml`의 지역 팝업 조회는 사업소 `002`일 때 사업소 필터를 생략한다. 반면 사용자 담당지역 테이블은 사용자가 명시적으로 선택한 행만 보존한다. 따라서 export와 importer는 `002` 사용자의 빈 담당지역을 모든 지역으로 확장하지 않는다. 전역 조회 규칙은 조회 시 계산해야 하며 이관 데이터로 복제하지 않는다.

## 운영 절차

1. 승인된 원본 DB 계정으로 export SQL의 여덟 결과를 조회한다. 원본 DB에 쓰기 SQL을 실행하지 않는다.
2. 결과 별칭을 그대로 사용해 요청 JSON을 만든다. `rankGroup` 조회 결과가 없으면 `null`로 둔다.
3. 공통 업무 관리 화면의 **운영 Directory 이관**에서 JSON을 선택한다.
4. 배열별 건수와 최대 세 개의 식별자 예시, 사용자의 SSO 코드 쌍을 확인한다.
5. 서버 import 결과와 감사 이력, 현재 Directory 건수를 승인 원본 건수와 대조한다.
6. 동일 JSON을 한 번 더 실행해 변경·버전·감사 이벤트가 추가되지 않는지 확인한다.

## 필수 검증

- 뒤쪽 SSO 소속 하나가 잘못된 요청은 앞서 처리 가능한 행까지 포함해 전체 무변경이어야 한다.
- 동일 요청 재실행 결과가 unchanged이며 파일 DB 재기동 뒤에도 Directory·SSO 소속·CM008이 같아야 한다.
- 기존 로컬 계정 snapshot과 비밀번호 인증이 유지되어야 한다. 단, 원본 요청이 사용자를 비활성화한 경우 로그인 차단 자체는 정상 동작이다.
- 사용자 조직·활성 상태를 변경해도 권한 배정, 신청, 권한 처리 이력과 약관 동의 이력은 보존되어야 한다.
- 요청에 없는 사용자와 공통코드 그룹은 유지되어야 한다.
- 없는 부모, 비활성 부모, 없는 SSO 대응, 중복 자연키, 사용자와 다른 사업소의 담당지역은 전체 거부되어야 한다.
- 사업소 `002`의 명시 담당지역은 그대로 보존하고 전역 지역 조회는 별도 조회 테스트로 확인해야 한다.
- 이전 형식의 파일 DB를 열 수 있고 import가 새 저장 상태 키를 요구하지 않아야 한다.

## 남은 운영 확인

실제 승인 원본 JSON은 아직 적재하지 않았다. 원본 건수·NULL 분포·중복 키와 대상 결과를 대조한 증거, 실제 SSO 인증 종단 검증은 승인된 시험 환경에서 별도로 남겨야 한다.
