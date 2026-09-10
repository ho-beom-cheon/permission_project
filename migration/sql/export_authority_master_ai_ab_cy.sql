-- 1단계 권한 마스터 승인 추출용 읽기 전용 SQL
-- 운영 반영 전에 결과를 JSON 배열로 내보내고 담당자 검토를 거친다.
SELECT AUTH_ID      AS "authId",
       AUTH_NM      AS "authNm",
       AUTH_DSC     AS "authDsc",
       USE_FLAG     AS "useFlag",
       AUTH_CLSF_ID AS "authClsfId"
  FROM TN_CM_AUTH_BASIC
 WHERE SUBSTR(AUTH_ID, 1, 2) IN ('AI', 'AB', 'CY', 'MO')
 ORDER BY AUTH_ID;
