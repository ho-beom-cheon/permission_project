-- 단일 서버 폼 로그인용 데모 계정. 저장 값은 BCrypt 단방향 해시이며 평문은 DB에 넣지 않는다.
MERGE INTO PM_LOCAL_ACCOUNT target
USING (
    SELECT 'admin' USERNAME, '{bcrypt}$2a$10$S4IjINDTv3bMPvhn5VdQLelBmzIfFOmp8ukPjRHP//6mDtJrc9ZAW' PASSWORD_HASH FROM DUAL
    UNION ALL SELECT 'backupAdmin', '{bcrypt}$2a$10$3QFMBYxWjidTCSypivJHP.3yBEqNxxs30UHN3rYBwczVwkoJVwtTa' FROM DUAL
    UNION ALL SELECT 'authorityManager', '{bcrypt}$2a$10$zUVlq64yFTji7bd4JyW.y.azBOwX82WrD2YXWlzPUz.em3SmGgsHC' FROM DUAL
    UNION ALL SELECT 'programManager', '{bcrypt}$2a$10$tuWsvyCtPhxaKV2b698gZeqqd2zrATDM0fwnNI476MVAxSTFcp6cO' FROM DUAL
    UNION ALL SELECT 'projectManager', '{bcrypt}$2a$10$IuHuktqaNlk0r82bvgFN9uHo.qTn4USnhA6NmofqWigSiFcAARUd2' FROM DUAL
    UNION ALL SELECT 'codeManager', '{bcrypt}$2a$10$Es2EHNgrVjiVBAMNJcXxkO1gY.Q5ReOGvCLAB.fhlikX.fCI8yoxG' FROM DUAL
    UNION ALL SELECT 'auditor', '{bcrypt}$2a$10$ltVQHSzVMfoGBPR28jYqd.UkLWSL74ECbXcoZ6TpXnZ0wGPqrdWMq' FROM DUAL
    UNION ALL SELECT 'contentEditor', '{bcrypt}$2a$10$s01zgNsCqYuLBlVrs3W7iuEojpFudFV0ABdyBoX19uX8paa5J8jLe' FROM DUAL
    UNION ALL SELECT 'seoulOperator', '{bcrypt}$2a$10$TOEEx6Z2LUBPzjk.ha93e.xzeGwL85jCUTZRAP.lBmH6iw8uKI0Lu' FROM DUAL
    UNION ALL SELECT 'busanViewer', '{bcrypt}$2a$10$2R8Spb/CPFtYwDaqmrkFHuhdjwgTAj008wkLTya4uN83wWerJ2g.m' FROM DUAL
    UNION ALL SELECT 'manager', '{bcrypt}$2a$10$i9jKLBdTMtfoUJ54xR.xVeitRZ2HJPlHjxiwyDkzr4e1mFlibtbZG' FROM DUAL
    UNION ALL SELECT 'viewer', '{bcrypt}$2a$10$NtmLw3ltFZdZiyboct9zS.lXX61mxdZwwIt18Jv1vOAakivxV7Is6' FROM DUAL
    UNION ALL SELECT 'delegate', '{bcrypt}$2a$10$znRE33/OgOOCFSXVmVuG..eWi9TklL94wdzSoSq5YhXcQWmiVaT86' FROM DUAL
    UNION ALL SELECT 'expired', '{bcrypt}$2a$10$MVQxy0hOSDEMY0NVhgDCzeRmB8CxeKHUXrAv8iMaWAleZC4ca4E/G' FROM DUAL
    UNION ALL SELECT 'revoked', '{bcrypt}$2a$10$HGAUjzXGB/1WuVVG1Xb5muscwA97lSJi2uHrggKVs0tGDEbRcli5K' FROM DUAL
    UNION ALL SELECT 'moved', '{bcrypt}$2a$10$Sy3gd4JD/cqfjLOZ.R869uOeqKaaRq3JyJarcnBL7USWmZZgUjrhS' FROM DUAL
    UNION ALL SELECT 'revokedManager', '{bcrypt}$2a$10$aJc9xsCYES5haUuhKh11we/JG5A9qdZPs7.S2VyWIr8zkiM0Z6JRK' FROM DUAL
    UNION ALL SELECT 'orphanDelegate', '{bcrypt}$2a$10$lCOPbTqkx37AKwQIm6GSCe3hUxkjJ9B9zEkti/PamdydXm51acMWW' FROM DUAL
) source
ON (target.USERNAME = source.USERNAME)
WHEN MATCHED THEN UPDATE SET
    target.PASSWORD_HASH = source.PASSWORD_HASH,
    target.ENABLED_YN = 'Y',
    target.UPDATED_AT = SYSTIMESTAMP,
    target.UPDATED_BY = 'SYSTEM'
WHEN NOT MATCHED THEN INSERT (
    USERNAME, PASSWORD_HASH, ENABLED_YN, CREATED_BY, UPDATED_BY
) VALUES (
    source.USERNAME, source.PASSWORD_HASH, 'Y', 'SYSTEM', 'SYSTEM'
);

COMMIT;
