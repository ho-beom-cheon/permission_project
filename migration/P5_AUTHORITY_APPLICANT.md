# P5 권한 신청자 정보·담당지역 동시 저장

## 범위

권한 신청 화면에서 신청자의 연락처·직급·담당업무와 담당지역 최종 집합을 확인하고, 약관 동의 및 권한별 신청과 같은 저장 경계에서 반영한다. 사용자 ID와 소속은 요청값으로 받지 않고 로그인 세션과 현재 디렉터리 정보에서 정한다.

## 원본 근거

| 원본 | 근거 | 확인한 계약 |
|---|---|---|
| `src/main/java/arisuinfo/cm/auth/auth/service/impl/AuthAplyServiceImpl.java` | `authAplyList:77-115` | 한 신청 호출에서 약관 동의 → 신청자 정보 → 담당지역 → 권한 신청 순서로 저장한다. |
| 같은 파일 | `saveUsr:243-260` | 세션 사용자 ID로 부서전화 `tel`, 휴대전화 `mtel`, 이메일, 직급 `rankCd`, 담당업무 `chrgJob`만 수정하며 이메일은 소문자로 저장한다. |
| 같은 파일 | `saveArea:125-169` | 현재 담당지역과 화면의 최종 `stndBdongCd` 집합을 비교해 빠진 값은 삭제하고 새 값은 추가한다. 사용자 ID는 세션에서 얻는다. |
| `src/main/nexacro/form/cm/user/cmAuthAplyF.xfdl` | `f_execSaveAuthAply:665-686` | `ds_authUsr`, `ds_usr`, `ds_areaInfo`, `dsUtztnTrmsAgre`를 같은 신청 트랜잭션으로 보낸다. |
| 같은 파일 | `f_doSaveAuthAply:690-716` | 선택 담당지역이 0건이면 신청 전에 현재 사업소의 전체 동 목록을 채운 뒤 저장한다. |

원본 흐름에 따라 사용자 ID·사업소·부서는 클라이언트 신청 정보로 변경하지 않는다. 빈 담당지역의 사업소 전체 치환은 화면에서 현재 조회된 같은 사업소의 활성 지역으로 수행하며, 서버가 알 수 없는 지역이나 다른 사업소를 추정하지 않는다.

## 대상 구현

- `GET /api/me/authority-requests/applicant`
  - 현재 로그인 사용자의 `rankCode`, `telephone`, `mobile`, `email`, `jobDescription`, `regionIds`를 반환한다.
  - 현재 사업소의 활성 담당지역만 `availableRegions`로 반환한다.
- 기존 `POST /api/me/authority-requests`와 `POST /api/me/authority-requests/batch`
  - 기존 필드 뒤에 선택적 `applicant`를 받는다.
  - `applicant`는 `rankCode`, `telephone`, `mobile`, `email`, `jobDescription`, `regionIds`만 포함한다.
  - 화면은 지역을 하나도 선택하지 않았을 때 `availableRegions` 전체 코드를 `regionIds`로 보낸다.
- `DirectoryService`
  - 현재 사용자의 신원·소속·활성 상태를 유지하면서 신청자가 수정 가능한 필드와 담당지역만 변경한다.
  - 부서전화는 숫자 1~15자리, 휴대전화는 선택 입력 시 `010`으로 시작하는 숫자 11자리, 이메일은 원본 정규식, 지역은 최대 500개를 검증한다.
  - 담당지역은 현재 사업소에 속한 활성 지역만 허용하고 이메일은 소문자로 저장한다.
- `authority-requests.html/js`
  - 신청자 필수 정보와 담당지역 체크 목록을 표시하고 신청 본문의 `applicant`로 전송한다.
  - 성공 후 최신 신청자 정보를 다시 조회한다. 실패하면 입력과 권한·지역 선택을 유지하고 약관 계획만 폐기해 다시 확인한다.

## 저장과 롤백

`AuthorityWorkflowController`의 상태 경계 안에서 약관 결정, 신청자 정보·담당지역, 권한별 신청, 대기 권한, 처리 이력, 감사 이벤트를 순서대로 저장한다. 후순위 권한 검증이나 저장이 실패하면 `directory`, `authority-terms`, `authorization`, `authority-requests`, `authority-history`, `audit` 상태를 호출 전 값으로 되돌린다.

신청자 정보는 기존 `DirectoryService.Person`과 `directory` 저장 상태가 소유한다. 공통코드·기준정보 서비스나 별도 중복 저장 모델을 만들지 않는다. 신청자 정보만 수정해도 권한 조직은 바뀌지 않으며 권한 캐시 버전도 올리지 않는다.

## 호환성과 검증

- 기존 클라이언트가 `applicant`를 보내지 않으면 현재 신청자 정보와 담당지역을 그대로 보존한다.
- 요청 DTO에 사용자 ID·이름·사업소·부서·활성 상태 필드가 없으므로 클라이언트가 같은 이름의 추가 JSON 값을 보내도 신원과 소속은 바뀌지 않는다.
- 신청자 조회·정상 저장·신원 보존, 권한 버전 불변, 다른 사업소 및 비활성 지역 거부, `applicant: null` 호환, 후순위 실패 전체 롤백을 합성 데이터로 검증했다.
- `AuthorityApplicantIntegrationTest` **5개**, 단계 4 연계 포함 targeted 테스트 **11개**, 전체 자동 테스트 **199개**, JavaScript 구문 검사를 통과했다.

## 남은 작업

원본 직급은 `CM008` 공통코드에서 선택하지만 현재 저장소에는 해당 그룹의 승인된 실제 코드 데이터가 없다. 임의 코드를 만들지 않았으며, 추출 데이터를 확보하기 전까지 `rankCode`는 필수값과 길이만 검증한다.

실제 운영 사용자와 담당지역 원천 데이터는 제공되지 않아 적재하지 않았다. 원본 Oracle 동시성 및 전체 사업소 지역 데이터와의 동등성 검증은 승인된 추출본을 받은 뒤 수행한다.
