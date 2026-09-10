# 단계 7 기존 사용자 약관 동의 이력 이관

## 범위

원본 `TN_CM_UTZTN_TRMS_AGRE`의 기존 사용자별 약관 동의 이력을 승인된 JSON으로 전달받아 검증하고, 신규 권한 신청에서 저장한 동의 이력과 같은 약관 상태에 보존한다. 이 단계는 동의 이력 import 계약과 관리자 실행 경로를 제공하며 실제 운영 데이터는 포함하지 않는다.

## 원본 계약

| 원본 | 확인한 계약 |
|---|---|
| `BbsUtztnTrmsAgreMapper.insertUtztnTrmsAgre` | `USR_ID`, `UTZTN_TRMS_SEQ`, `UTZTN_TRMS_AGRE_DT`, `UTZTN_TRMS_AGRE_FLAG`, `CONN_IP`, `REG_DT`, `REGER_ID` 일곱 컬럼을 저장한다. |
| `selectUtztnTrmsNeedAgreCnt` | 신청 시 기존 동의 재사용은 사용자·약관 순번이 같고 동의 여부가 `Y`인 이력의 존재 여부로 판단한다. |
| `TN_CM_UTZTN_TRMS_AGRE` | 제공된 원본 소스에서 PK나 행 식별자를 확인할 수 없어 일곱 컬럼이 모두 같은 완전 중복 행도 원본 건수대로 보존해야 한다. |

원본 행을 합치거나 최신 행 하나로 축약하지 않는다. `N` 이력과 같은 값의 반복 이력도 감사·이관 건수의 일부로 유지한다.

## 추출 형식

[export_authority_term_agreements_ai_cy_mo_ab.sql](sql/export_authority_term_agreements_ai_cy_mo_ab.sql)은 `AI`, `CY`, `MO`, `AB` 약관에 연결된 동의 이력을 읽기 전용으로 조회한다.

```text
{
  "agreements": [
    {
      "username": "원본 USR_ID",
      "termSequence": 1,
      "agreedAt": "2026-09-04T09:30:00",
      "agreedFlag": "Y",
      "connectionIp": "원본 CONN_IP",
      "registeredAt": "2026-09-04T09:30:00",
      "registrarId": "원본 REGER_ID",
      "duplicateOrdinal": 1
    }
  ]
}
```

- 동의시각은 `yyyy-MM-dd'T'HH:mm:ss` 필수값으로 추출한다. 등록시각은 원본이 `NULL`이면 JSON `null`, 값이 있으면 같은 형식으로 추출한다. 서버는 시간대가 없는 원본 Oracle `DATE`를 `Asia/Seoul` 시각으로 해석해 보존한다.
- `duplicateOrdinal`은 일곱 원본 컬럼을 모두 `PARTITION BY`한 `ROW_NUMBER()`다. 완전 중복의 첫 행은 1, 다음 행은 2가 된다.
- 같은 일곱 원본 컬럼 그룹의 `duplicateOrdinal`은 반드시 1부터 행 수까지 중복이나 누락 없이 연속해야 한다. `[2]`, `[1, 3]`, `[1, 1]` 같은 입력은 전체 반영 전에 거부한다.
- Oracle `ROWID`는 완전 중복 행의 순번 계산에만 사용하고 JSON에는 넣지 않는다.
- 약관 마스터에 없는 순번, 잘못된 플래그·시각·중복 순번이나 같은 논리 행·순번 충돌은 전체 반영 전에 거부한다.
- 접속 IP와 등록자 ID도 원본 `NULL`은 JSON `null`로 보존한다. 원본 문자열과 시각을 자르거나 기본값으로 바꾸지 않는다.

## 관리자 절차

1. 승인된 원본 조회 환경에서 추출 SQL을 실행한다.
2. 전체 행 수, 사용자 수, `Y`·`N` 건수와 `duplicateOrdinal > 1` 건수를 검토한다.
3. `/authority-requests.html`의 **기존 사용자 약관 동의 이력 이관**에서 `{agreements:[...]}` JSON 파일을 선택한다.
4. 화면 미리보기의 집계와 일부 행을 원본 추출 결과와 비교한다.
5. `AUTHORITY_UPDATE` 권한이 있는 관리자가 `POST /api/admin/authority-term-agreements/import`를 실행한다.
6. 서버는 모든 행을 먼저 검증한 뒤 한 상태 경계에서 생성하거나 기존 동일 행으로 판정한다. 한 행이라도 잘못되면 아무 행도 반영하지 않는다.

파일 선택과 미리보기는 저장 작업이 아니다. 재실행은 같은 원본 행을 다시 만들지 않는다. 신규 신청으로 저장한 동의 이력은 원본 중복 순번이 없는 기존 형식으로 별도 보존하며, import 이력과 임의로 합치지 않는다.

## 완료 범위와 대기 조건

합성 데이터로 완전 중복 보존, 재실행 멱등성, 약관 참조·날짜·플래그 검증, 전체 무변경 실패, API 권한·CSRF, 기존 4필드 저장 JSON 호환과 파일 DB 복원을 확인했다. 관련 서비스 11개, API 8개, 파일 DB 재시작 2개로 **21개 대상 테스트**, 전체 회귀 **209개 테스트**가 통과했고 JavaScript 구문 검사도 통과했다. 브라우저에서는 관리자 화면에 동의 이력 import 영역이 표시되고, `null` 메타데이터와 완전 중복 2행이 포함된 합성 JSON을 선택했을 때 사용자 1명·Y 2건·완전 중복 추가행 1건으로 미리보기되며 실행 버튼이 활성화되는 것을 확인했다. 실제 완료 판정에는 승인된 원본 JSON을 적재한 뒤 원본과 다음 값을 대조해야 한다.

- 전체 동의 이력 건수와 사용자별·약관별 건수
- `Y`·`N` 건수와 완전 중복 추가행 건수
- 최소·최대 동의시각 및 등록시각
- 약관 마스터 미연결 행과 잘못된 사용자·등록자 식별자 여부

실제 원본 데이터가 제공되기 전까지 상태는 `IMPLEMENTED_WAITING_SOURCE_EXPORT`로 유지한다.
