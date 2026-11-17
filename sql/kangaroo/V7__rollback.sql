-- V7 回退：删除共享能力的四张表、字典与 2100–2199 菜单，并删掉自己的账本行。
-- kg_pay_order 是资金流水：回退前先 mysqldump 备份，且确认没有未完结的支付单。
delete from sys_menu where menu_id between 2100 and 2199;
delete from sys_dict_data where dict_type in ('kg_pay_status', 'kg_biz_type', 'kg_review_target', 'kg_report_status');
delete from sys_dict_type where dict_type in ('kg_pay_status', 'kg_biz_type', 'kg_review_target', 'kg_report_status');
drop table if exists kg_favorite;
drop table if exists kg_report;
drop table if exists kg_review;
drop table if exists kg_pay_order;
delete from kg_schema_version where version = 7;
