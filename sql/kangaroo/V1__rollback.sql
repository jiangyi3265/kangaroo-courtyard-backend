-- =============================================================================
-- V1 回退脚本：删除 V1__kangaroo_core.sql 创建的全部 kg_ 表
--
-- 警告：会丢失这些表中的全部业务数据。执行前请先备份：
--   mysqldump -uroot -p dkxy kg_app_user kg_store kg_staff_grant kg_job \
--     kg_job_favorite kg_application kg_application_log kg_interview_slot \
--     kg_interview kg_message kg_idempotency > kangaroo_v1_backup.sql
--
-- 本脚本不触碰任何 sys_ 表。菜单数据的回退见 V2__rollback.sql。
-- =============================================================================

drop table if exists kg_idempotency;
drop table if exists kg_message;
drop table if exists kg_interview;
drop table if exists kg_interview_slot;
drop table if exists kg_application_log;
drop table if exists kg_application;
drop table if exists kg_job_favorite;
drop table if exists kg_job;
drop table if exists kg_staff_grant;
drop table if exists kg_store;
drop table if exists kg_app_user;
