-- V11 回退：用户端扩展（城市字典、客服工单与问答记录、新机会订阅、简历与经历、证书、责任书签署、到店信息、同行队伍、提醒设置、体验官申请）。回退前先备份。
-- kg_review.dims、kg_store.latitude/longitude 为新增可空列，保留不删（删列会丢失已写入的数据）。
delete from sys_role_menu where menu_id between 2400 and 2499;
delete from sys_menu where menu_id between 2400 and 2499;
drop table if exists kg_guide_apply;
drop table if exists kg_notify_pref;
drop table if exists kg_team_member;
drop table if exists kg_app_arrival;
drop table if exists kg_contract_sign;
drop table if exists kg_cert;
drop table if exists kg_resume_exp;
drop table if exists kg_resume;
drop table if exists kg_job_subscribe;
drop table if exists kg_support_log;
drop table if exists kg_support_ticket;
drop table if exists kg_city;
delete from kg_schema_version where version = 11;
