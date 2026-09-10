-- 8단계 운영 Directory bootstrap 승인 추출용 읽기 전용 SQL
-- 각 SELECT 결과를 이름이 같은 JSON 필드로 내보낸 뒤 하나의 객체로 조립한다.
-- people에는 원본 SSO 소속을 포함한다. 비밀번호와 권한·신청·이력은 추출하지 않는다.
-- NULL이나 중복을 임의 보정하지 말고 서버의 전체 선검증 결과로 판단한다.

-- offices
SELECT O.OFFICE_CD AS "code",
       O.OFFICE_NM AS "name",
       O.OFFICE_ABRV_NM AS "abbreviation",
       O.ADDR AS "address",
       O.RP_TEL AS "telephone",
       O.USE_FLAG AS "useFlag"
  FROM TC_CM_OFFICE_CD O
 ORDER BY O.OFFICE_CD;

-- departments: 한 SSO 조직표에 들어 있는 업무 사업소·부서 조합
SELECT DISTINCT D.DEPT_CD AS "code",
       D.OFFICE_CD AS "officeCode",
       D.DEPT_NM AS "name",
       D.DEPT_DSC AS "description",
       D.USE_FLAG AS "useFlag"
  FROM TN_CM_OFFICE_DEPT D
 WHERE D.DEPT_CD IS NOT NULL
   AND D.OFFICE_CD IS NOT NULL
 ORDER BY "officeCode", "code";

-- organizationMappings
SELECT D.SSO_OFFICE_CD AS "ssoOfficeCode",
       D.SSO_DEPT_CD AS "ssoDepartmentCode",
       D.SSO_OFFICE_NM AS "ssoOfficeName",
       D.SSO_DEPT_NM AS "ssoDepartmentName",
       D.OFFICE_CD AS "officeCode",
       D.DEPT_CD AS "departmentCode",
       D.USE_FLAG AS "useFlag"
  FROM TN_CM_OFFICE_DEPT D
 ORDER BY D.SSO_OFFICE_CD, D.SSO_DEPT_CD;

-- regions: 원본 담당지역 화면과 같은 행정동 행 및 구 코드·구 이름
SELECT B.STND_BDONG_CD AS "code",
       B.OFFICE_CD AS "officeCode",
       SUBSTR(B.STND_BDONG_CD, 3, 3) AS "districtCode",
       G.SIGNGU_NM AS "districtName",
       B.EMD_NM AS "name",
       B.USE_FLAG AS "useFlag"
  FROM TC_CM_STD_BDONG B
  LEFT JOIN TC_CM_STD_BDONG G
    ON G.STND_BDONG_CD = SUBSTR(B.STND_BDONG_CD, 1, 5) || '00000'
   AND G.BDONG_HDONG_SE_CD = '01'
 WHERE B.BDONG_HDONG_SE_CD = '01'
   AND SUBSTR(B.STND_BDONG_CD, 6, 5) <> '00000'
 ORDER BY B.STND_BDONG_CD;

-- people: 업무 조직은 현재 SSO 조직 대응표에서 그대로 가져온다.
SELECT U.USR_ID AS "username",
       U.USR_NM AS "name",
       M.OFFICE_CD AS "officeCode",
       M.DEPT_CD AS "departmentCode",
       U.SSO_OFFICE_CD AS "ssoOfficeCode",
       U.SSO_DEPT_CD AS "ssoDepartmentCode",
       U.RANK_CD AS "rankCode",
       U.TEL AS "telephone",
       U.MTEL AS "mobile",
       U.EMAIL AS "email",
       U.CHRG_JOB AS "jobDescription",
       U.USE_FLAG AS "useFlag"
  FROM TN_CM_USR U
  LEFT JOIN TN_CM_OFFICE_DEPT M
    ON M.SSO_OFFICE_CD = U.SSO_OFFICE_CD
   AND M.SSO_DEPT_CD = U.SSO_DEPT_CD
 ORDER BY U.USR_ID;

-- userRegions: 사업소 002 사용자도 원본에 명시된 담당지역 행만 내보낸다.
SELECT R.USR_ID AS "username",
       R.STND_BDONG_CD AS "regionCode"
  FROM TN_CM_USR_CHRG_RGN_DTLS R
 ORDER BY R.USR_ID, R.STND_BDONG_CD;

-- rankGroup: JSON에서는 배열이 아니라 단일 객체이며, 결과가 없으면 null로 둔다.
SELECT C.CD_NM AS "name",
       C.CD_DSC AS "description",
       C.USE_FLAG AS "useFlag"
  FROM TC_CM_CD C
 WHERE C.CD = 'CM008';

-- rankCodes
SELECT D.DETAIL_CD AS "code",
       D.DETAIL_CD_NM AS "name",
       D.DETAIL_CD_DSC AS "description",
       D.SORT_ORDER AS "sortOrder",
       D.USE_FLAG AS "useFlag",
       D.SCRIN_DISP_FLAG1 AS "screen1",
       D.SCRIN_DISP_FLAG2 AS "screen2",
       D.SCRIN_DISP_FLAG3 AS "screen3",
       D.SCRIN_DISP_FLAG4 AS "screen4",
       D.SCRIN_DISP_FLAG5 AS "screen5",
       D.SBST_CD_NM AS "substituteName",
       D.SCRIN_DISP_DSC AS "screenDescription"
  FROM TC_CM_CD_DETAIL D
 WHERE D.CD = 'CM008'
 ORDER BY D.SORT_ORDER, D.DETAIL_CD;
