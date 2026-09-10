-- 4단계 권한 신청 이용약관 승인 추출용 읽기 전용 SQL
-- 결과를 임의로 보정하지 않고 {"terms":[...]} JSON의 terms 배열로 내보낸다.
-- ENFC_* 값은 원본 YYYYMMDD를 그대로 보존한다. 이관 API는 YYYYMMDD와 YYYY-MM-DD를 명시적으로 수용한다.
-- SORT_ORDER가 NULL이면 임의의 0으로 바꾸지 말고 원본 확인 대상으로 남긴다.

SELECT UTZTN_TRMS_SEQ       AS "seq",
       SYS_SE_CD            AS "systemId",
       UTZTN_TRMS_SE_CD     AS "typeCode",
       UTZTN_TRMS_VER       AS "version",
       UTZTN_TRMS_TITLE     AS "title",
       UTZTN_TRMS_CN        AS "body",
       UTZTN_TRMS_STATUS_CD AS "statusCode",
       ENFC_START_DAY       AS "enforcementStart",
       ENFC_END_DAY         AS "enforcementEnd",
       ESNTL_AGRE_FLAG      AS "requiredFlag",
       SORT_ORDER           AS "sortOrder",
       USE_FLAG             AS "useFlag"
  FROM TN_CM_UTZTN_TRMS
 WHERE SYS_SE_CD IN ('AI', 'CY', 'MO', 'AB')
 ORDER BY CASE SYS_SE_CD
              WHEN 'AI' THEN 1
              WHEN 'CY' THEN 2
              WHEN 'MO' THEN 3
              WHEN 'AB' THEN 4
              ELSE 5
          END,
          UTZTN_TRMS_SE_CD,
          SORT_ORDER,
          ESNTL_AGRE_FLAG DESC,
          UTZTN_TRMS_VER DESC,
          UTZTN_TRMS_SEQ;
