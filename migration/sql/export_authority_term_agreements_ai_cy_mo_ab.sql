-- 7단계 기존 사용자 약관 동의 이력 승인 추출용 읽기 전용 SQL
-- 결과를 임의로 보정하지 않고 {"agreements":[...]} JSON의 agreements 배열로 내보낸다.
-- 제공된 원본 소스에서 PK나 행 식별자를 확인할 수 없어 모든 원본 컬럼이 같은 행도 duplicateOrdinal로 보존한다.
-- ROWID는 동일 행의 순번 계산에만 사용하며 결과에는 내보내지 않는다.

SELECT A.USR_ID AS "username",
       A.UTZTN_TRMS_SEQ AS "termSequence",
       TO_CHAR(A.UTZTN_TRMS_AGRE_DT, 'YYYY-MM-DD"T"HH24:MI:SS') AS "agreedAt",
       A.UTZTN_TRMS_AGRE_FLAG AS "agreedFlag",
       A.CONN_IP AS "connectionIp",
       TO_CHAR(A.REG_DT, 'YYYY-MM-DD"T"HH24:MI:SS') AS "registeredAt",
       A.REGER_ID AS "registrarId",
       ROW_NUMBER() OVER (
           PARTITION BY A.USR_ID,
                        A.UTZTN_TRMS_SEQ,
                        A.UTZTN_TRMS_AGRE_DT,
                        A.UTZTN_TRMS_AGRE_FLAG,
                        A.CONN_IP,
                        A.REG_DT,
                        A.REGER_ID
           ORDER BY A.ROWID
       ) AS "duplicateOrdinal"
  FROM TN_CM_UTZTN_TRMS_AGRE A
 WHERE EXISTS (
       SELECT 1
         FROM TN_CM_UTZTN_TRMS T
        WHERE T.UTZTN_TRMS_SEQ = A.UTZTN_TRMS_SEQ
          AND T.SYS_SE_CD IN ('AI', 'CY', 'MO', 'AB')
 )
 ORDER BY A.USR_ID,
          A.UTZTN_TRMS_SEQ,
          A.UTZTN_TRMS_AGRE_DT,
          A.REG_DT,
          "duplicateOrdinal";
