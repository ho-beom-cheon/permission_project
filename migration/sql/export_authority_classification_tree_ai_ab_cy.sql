-- 2단계 권한 분류와 권한-분류 연결 승인 추출용 읽기 전용 SQL
-- 첫 번째 결과 집합은 classifications, 두 번째 결과 집합은 authorityLinks 배열로 내보낸다.

SELECT AUTH_CLSF_ID       AS "authClsfId",
       UPPER_AUTH_CLSF_ID AS "upperAuthClsfId",
       AUTH_CLSF_NM       AS "authClsfNm",
       USE_FLAG           AS "useFlag"
  FROM TN_CM_AUTH_CLSF_BASIC
 WHERE SUBSTR(AUTH_CLSF_ID, 1, 2) IN ('AI', 'AB', 'CY', 'MO')
 ORDER BY SUBSTR(AUTH_CLSF_ID, 1, 2),
          CASE WHEN UPPER_AUTH_CLSF_ID IS NULL THEN 0 ELSE 1 END,
          UPPER_AUTH_CLSF_ID,
          AUTH_CLSF_ID;

SELECT AUTH_ID      AS "authId",
       AUTH_CLSF_ID AS "authClsfId"
  FROM TN_CM_AUTH_BASIC
 WHERE REGEXP_LIKE(AUTH_ID, '^(AI|AB|CY|MO)[0-9]{10}$')
   AND AUTH_CLSF_ID IS NOT NULL
 ORDER BY AUTH_ID;
