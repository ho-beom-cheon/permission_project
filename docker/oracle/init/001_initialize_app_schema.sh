#!/usr/bin/env bash
set -Eeuo pipefail

sqlplus -s "${APP_USER}/${APP_USER_PASSWORD}@//localhost/FREEPDB1" <<'SQL'
WHENEVER OSERROR EXIT FAILURE ROLLBACK
WHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK
SET DEFINE OFF
SET ECHO ON
SET FEEDBACK ON

@/opt/permission/sql/001_schema.sql
@/opt/permission/sql/002_seed.sql
@/opt/permission/sql/003_project_domain.sql
@/opt/permission/sql/004_authority_workflow.sql
@/opt/permission/sql/005_product_seed.sql
@/opt/permission/sql/006_local_account.sql
@/opt/permission/sql/007_project_history.sql
@/opt/permission/sql/008_common_code_upgrade.sql
@/opt/permission/sql/009_management_catalog_seed.sql
@/opt/permission/sql/010_local_account_seed.sql
@/opt/permission/sql/011_project_portfolio_seed.sql
@/opt/permission/sql/012_column_comments.sql
@/opt/permission/sql/013_workspace_configuration.sql
@/opt/permission/sql/014_workspace_content.sql

EXIT SUCCESS
SQL
