-- V14 回退：账号与工作台补全（待办责任人与备注、简历转发记录、账号注销留痕）。回退前先备份。
-- kg_app_user 新增的 intro / id_last4 / id_hash / real_name_verified_time / deactivated_time 为可空列，保留不删。
delete from sys_role_menu where menu_id between 2650 and 2669;
delete from sys_menu where menu_id between 2650 and 2669;
drop table if exists kg_account_deletion;
drop table if exists kg_resume_forward;
drop table if exists kg_todo_meta;
delete from kg_schema_version where version = 14;
