# ARISUINFO 권한·메뉴·공통 처리 분석 및 독립 샘플 매핑

## 1. 분석 결론

ARISUINFO의 업무 권한 모델은 Spring Security의 `ROLE_USER`가 아니라 다음 세 단계 매핑으로 구성됩니다.

```text
사용자 직접 권한 이력 + 유효 위임
  → 유효 권한 ID 집합
  → 권한-메뉴 매핑 + 공용 메뉴
  → 권한-메뉴-프로그램-프로그램상세 매핑
```

Nexacro는 이 결과를 `gdsAut`, `gdsMenu`, 버튼 데이터셋으로 전달하고 화면을 그리는 클라이언트일 뿐입니다. 핵심 업무 규칙은 일반 JSON API와 서버 권한 서비스로 분리할 수 있습니다.

## 2. 인증과 현재 사용자

ARISUINFO SSO 필터는 인증 후 모든 사용자에게 동일한 Spring 권한 `ROLE_USER`를 부여하고, 실제 업무 사용자 정보는 `SsoUserDetails`와 세션 `LoginVO`에 저장합니다.

- `C:\arisuinfo\src\main\java\arisuinfo\com\security\SsoAuthFilter.java:143`
- `C:\arisuinfo\src\main\java\arisuinfo\com\security\SsoAuthFilter.java:169`
- `C:\arisuinfo\src\main\java\arisuinfo\com\security\SsoAuthFilter.java:176`
- `C:\arisuinfo\src\main\java\arisuinfo\com\util\SessionUtil.java:38`
- `C:\arisuinfo\src\main\java\arisuinfo\com\util\SessionUtil.java:79`

따라서 인증과 업무 권한은 분리되어 있습니다. 사용자 ID, 사업소, 부서, 권한 ID를 요청 파라미터에서 받지 않고 서버 인증 객체에서 결정해야 합니다.

독립 샘플도 Spring Security는 `ROLE_AUTHENTICATED_USER`만 사용하고, 실제 업무 권한은 `EffectiveAuthorityService`가 사용자명으로 별도 계산합니다.

## 3. 권한 마스터와 사용자 권한 이력

주요 테이블 역할:

| 테이블 | 역할 |
|---|---|
| `TN_CM_AUTH_CLSF_BASIC` | 권한 분류 계층 |
| `TN_CM_AUTH_BASIC` | 실제 권한 마스터와 사용 여부 |
| `TN_CM_AUTH_USR_DTLS` | 사용자 권한 신청·승인·회수 이력 |
| `TN_CM_AUTH_MNDT_DTLS` | 권한 위임 상세 |

권한 분류는 `CONNECT BY` 계층 쿼리로 구성되고 실제 삭제보다 `USE_FLAG`를 통한 비활성화를 사용합니다.

- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMngMapper.xml:33`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMngMapper.xml:85`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMngMapper.xml:281`

요청 구분은 부여 `10`, 변경 `20`이며 처리 상태는 신청 `01`, 승인 `02`, 반려 `03`, 변경 `04`, 회수 `05`입니다.

- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\auth\enums\ReqSeCd.java:32`
- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\auth\enums\ProcStatusCd.java:32`

로그인 시 직접 권한이 유효하려면 다음 조건을 만족해야 합니다.

1. 세션 사용자와 현재 SSO 조직이 일치
2. 사용자·권한 범위의 최신 이력
3. 최신 상태가 승인
4. 현재일이 시작일과 종료일 사이
5. 해당 시스템 범위의 권한
6. 권한 마스터가 활성 상태

원본 쿼리 근거:

- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authUsrMapper.xml:826`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authUsrMapper.xml:838`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authUsrMapper.xml:850`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authUsrMapper.xml:852`

현재 원본은 권한 마스터 `USE_FLAG`를 조회하지만 유효 사용자 권한 단계에서는 명시적으로 필터하지 않아 비활성 권한이 `gdsAut`에 들어갈 여지가 있습니다. 독립 샘플은 모든 직접·위임 권한에서 활성 권한 마스터를 필수로 확인합니다.

## 4. 위임 권한

ARISUINFO는 피위임자와 기간이 유효한 위임 권한을 직접 권한 결과와 `UNION`합니다.

- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authUsrMapper.xml:855`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authUsrMapper.xml:876`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authUsrMapper.xml:884`

원본의 주의점:

- 위임 등록 시 위임자가 해당 권한을 실제 보유하는지 서버 검증이 부족합니다.
- 유효 권한 계산 시에도 위임자의 원천 권한이 계속 유효한지 확인하지 않습니다.
- 직접 권한과 위임 권한의 출처 표기가 섞일 수 있습니다.
- 활성 위임 제한이 권한별이 아니라 위임자 전체 1건으로 동작합니다.

독립 샘플은 `DIRECT`와 `DELEGATED`를 분리하고, 위임자에게 동일한 활성 직접 권한이 있을 때만 피위임자의 권한에 합산합니다. 위임 연쇄는 허용하지 않습니다.

## 5. 메뉴 계산

로그인 호출 흐름:

```text
MainMngController.login
  → AuthListServiceImpl.searchAutList
  → AuthUserService.selectLoginAuthUsrInfo
  → 유효 권한 ID 추출
  → AuthMenuService.selectMenuListWithPublic
  → menuId 중복 제거
  → gdsAut / gdsMenu 반환
```

- `C:\arisuinfo\src\main\java\arisuinfo\cm\main\web\MainMngController.java:115`
- `C:\arisuinfo\src\main\java\arisuinfo\cm\main\service\impl\AuthListServiceImpl.java:50`
- `C:\arisuinfo\src\main\java\arisuinfo\cm\main\service\impl\AuthListServiceImpl.java:71`
- `C:\arisuinfo\src\main\java\arisuinfo\cm\main\service\impl\AuthListServiceImpl.java:95`

권한 메뉴는 유효 권한 중 하나에 매핑되면 포함됩니다. 권한·메뉴·연결 프로그램은 활성 상태여야 합니다.

- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMenuMapper.xml:350`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMenuMapper.xml:369`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMenuMapper.xml:386`

권한이 없어도 공용 메뉴 `AIBS` 하위와 필요한 `AI` 상위 트리가 합쳐집니다. 메뉴는 계층과 `SORT_ORDER`를 유지합니다.

- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMenuMapper.xml:395`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMenuMapper.xml:431`
- `C:\arisuinfo\src\main\resources\mappers\arisuinfo\cm\auth\auth\authMenuMapper.xml:445`

독립 샘플은 `HOME`을 공용 메뉴로 두고, 권한별 허용 메뉴를 합친 뒤 허용된 자식의 모든 활성·표시 상위 노드를 보존합니다. 상위 컨테이너 노출은 하위 기능 실행 권한으로 간주하지 않습니다.

## 6. 프로그램 상세와 버튼 권한

원본 매핑의 논리 키는 다음 네 값입니다.

```text
authId + menuId + pgmId + pgmDetailId
```

- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\auth\vo\menu\AuthPgmKeyVO.java:37`
- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\auth\vo\menu\AuthPgmKeyVO.java:58`

Nexacro의 `gfn_setBtnAuth`는 전역 `gdsAut`의 권한 ID를 문자열로 서버에 보내고, 반환된 `pgmDetailId`와 같은 컴포넌트를 찾아 활성화합니다.

- `C:\arisuinfo\src\main\nexacro\_extlib_\arisuInfo\common.js:731`
- `C:\arisuinfo\src\main\nexacro\_extlib_\arisuInfo\common.js:771`
- `C:\arisuinfo\src\main\nexacro\_extlib_\arisuInfo\common.js:801`
- `C:\arisuinfo\src\main\nexacro\_extlib_\arisuInfo\common.js:827`

보안상 중요한 공백:

1. 서버가 클라이언트가 보낸 `authIds`를 그대로 조회조건에 사용합니다.
2. 버튼 비활성화는 UI 제어이며 실제 업무 API 전체의 서버 인가를 보장하지 않습니다.
3. 버튼 조회는 매핑 키의 `menuId` 문맥을 충분히 사용하지 않아 같은 프로그램을 여러 메뉴에서 사용할 때 권한이 섞일 수 있습니다.

독립 샘플의 변경:

- 요청에서 권한 ID를 받지 않습니다.
- `menuId + programId + actionId`를 함께 확인합니다.
- UI에는 허용 기능과 DOM 컴포넌트 ID만 반환합니다.
- 실제 조회·저장·게시·관리 API는 `@PreAuthorize`로 다시 검사합니다.
- 비활성 기능은 매핑이 있어도 허용하지 않습니다.

## 7. 권한-메뉴 저장 정합성

권한 중심 저장은 다음 정리를 수행합니다.

- 선택한 하위 메뉴의 조상 메뉴 추가
- 회수한 메뉴의 프로그램 상세 권한 제거
- 고아 프로그램 권한 제거
- 자식 권한이 없는 상위 메뉴 반복 제거
- 부여·회수 이력 기록

- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\auth\service\impl\AuthMenuServiceImpl.java:174`
- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\auth\service\impl\AuthMenuServiceImpl.java:364`
- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\auth\service\impl\AuthMenuServiceImpl.java:401`

반면 메뉴 중심 저장 경로는 동일한 수준의 프로그램 권한 정리와 이력 기록이 없어 두 관리 화면의 결과가 달라질 수 있습니다.

- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\menu\service\impl\MenuServiceImpl.java:376`
- `C:\arisuinfo\src\main\java\arisuinfo\cm\auth\menu\service\impl\MenuServiceImpl.java:404`

실제 구현에서는 권한 중심/메뉴 중심 UI가 같은 도메인 서비스를 호출하도록 통합하고, 매핑 변경·하위 정리·이력 기록을 한 트랜잭션에서 처리해야 합니다.

## 8. 공통코드와 Nexacro 공통 처리

ARISUINFO 공통코드는 다음 구조를 사용합니다.

| 테이블 | 역할 |
|---|---|
| `TC_CM_CD` | 공통코드 그룹 |
| `TC_CM_CD_DETAIL` | 상세 코드 |
| `TC_CM_CD_DETAIL_LWR` | 하위 상세 코드 |

Nexacro에서는 로그인 후 `gdsCmCd`, `gdsCmCdLwr` 전역 데이터셋을 사용하고, `arisuTransaction`과 `BaseController.processService`가 데이터셋 입출력 계약을 공통화합니다.

Nexacro 제거 대상:

- `NexacroMapDTO`, `DataSetMap`, `DataSetMapConverter`
- `BaseController.processService`, `nexacroMapView`
- `gdsAut`, `gdsMenu`, `dsBtnAuth`, 순번형 `ds_output_01`
- Nexacro `ROW_TYPE`
- `arisuTransaction`, `svc::` URL, 콜백 시그니처
- 동적 Dataset과 Div/Tab 컴포넌트 재귀 검색
- XFDL 프레임 메뉴 필터 및 MDI 처리

독립 샘플은 표준 JSON 응답, `fetch`, HTTP 상태 코드, Spring MVC Controller로 대체했습니다. 공통코드는 `CommonCodeService`의 인메모리 저장소를 사용하며 활성 코드만 정렬해 반환합니다. 저장은 공통코드 관리 기능 권한과 CSRF가 모두 있어야 합니다.

## 9. 원본에서 확인된 추가 위험

| 항목 | 영향 | 샘플 대응 |
|---|---|---|
| 클라이언트 `authIds` 신뢰 | 버튼 권한 결과 조작 | 요청값 무시, 서버 인증 사용자로만 계산 |
| URL 전반이 인증만 확인 | 숨은 URL 직접 호출 가능 | 실제 API별 Method Security |
| 비활성 권한 누수 | 중지 권한이 유효 집합에 잔존 | 모든 단계에서 권한 활성 확인 |
| 위임 원천 미검증 | 보유하지 않은 권한 위임 가능 | 위임자의 동일 직접 권한 검증 |
| 메뉴 문맥 없는 기능 조회 | 다른 메뉴 권한 혼입 | 메뉴·프로그램·기능 세 값 확인 |
| 저장 경로 비대칭 | 고아 상세 권한·이력 누락 | 단일 도메인 서비스와 트랜잭션 권장 |
| 관리 사용자 목록과 런타임 유효성 차이 | 화면 표시와 실제 접근 불일치 | 같은 유효 권한 서비스 재사용 권장 |

## 10. 독립 샘플이 의도적으로 단순화한 부분

- 실제 SSO 대신 Spring Security 인메모리 사용자 사용
- DB 최신 이력 선별 대신 승인 상태가 포함된 현재 assignment 목록 사용
- 사업소·부서를 단일 `organizationId`로 단순화
- 영속 감사 이력 대신 서버 판정과 자동 테스트에 집중
- 메뉴/권한 관리 CRUD 전체 대신 런타임 권한 계산과 공통코드 저장 예제 제공
- 재시작 시 공통코드 변경 초기화

운영 전환 시에는 조직 범위, 최신 이력 선별, 위임 등록 검증, 부여·회수 감사 이력, 캐시 무효화, 동시성, 트랜잭션 원자성을 추가해야 합니다.

## 11. 핵심 불변식

1. 현재 사용자는 서버 인증 객체에서만 가져옵니다.
2. 업무 권한은 승인·기간·활성·조직·위임 원천을 모두 확인합니다.
3. 여러 유효 권한의 메뉴와 기능은 합집합으로 계산합니다.
4. 공용 메뉴도 활성·표시 조건을 적용합니다.
5. 메뉴 표시와 업무 API 인가는 별개입니다.
6. 기능 권한은 메뉴·프로그램·상세 문맥으로 확인합니다.
7. UI에서 버튼을 조작해도 서버 상태 변경은 허용되지 않습니다.
8. 권한 변경과 정리, 감사 이력은 한 트랜잭션으로 처리합니다.
