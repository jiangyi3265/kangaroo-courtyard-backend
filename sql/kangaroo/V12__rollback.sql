-- V12 回退：经营模块（周报、日报明细、门店任务与积分、分佣规则、钱包流水、收款账户、结算申请、荣誉、里程碑、生日礼物、申报）。回退前先备份。
-- kg_app_user.birthday 为新增可空列，保留不删（删列会丢失已写入的数据）。
delete from sys_role_menu where menu_id between 2500 and 2599;
delete from sys_menu where menu_id between 2500 and 2599;
drop table if exists kg_appeal;
drop table if exists kg_gift_claim;
drop table if exists kg_gift_option;
drop table if exists kg_growth_milestone;
drop table if exists kg_honor_award;
drop table if exists kg_honor_def;
drop table if exists kg_wallet_withdraw;
drop table if exists kg_wallet_payee;
drop table if exists kg_wallet_entry;
drop table if exists kg_commission_rule;
drop table if exists kg_point_entry;
drop table if exists kg_task_assignee;
drop table if exists kg_task;
drop table if exists kg_daily_entry;
drop table if exists kg_weekly_item;
drop table if exists kg_weekly_report;
delete from kg_schema_version where version = 12;
