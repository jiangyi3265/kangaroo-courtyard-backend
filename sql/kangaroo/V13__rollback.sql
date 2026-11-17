-- V13 回退：员工端扩展（人才库标记与邀请、线上任务扩展 / 成果 / 结算）。回退前先备份。
-- kg_review 新增的 internal_note / feature_reasons / feature_note / featured_by / featured_time 为可空列，保留不删。
delete from sys_role_menu where menu_id between 2600 and 2649;
delete from sys_menu where menu_id between 2600 and 2649;
drop table if exists kg_online_settlement;
drop table if exists kg_online_submission;
drop table if exists kg_online_task;
drop table if exists kg_talent_invite;
drop table if exists kg_talent_mark;
delete from kg_schema_version where version = 13;
