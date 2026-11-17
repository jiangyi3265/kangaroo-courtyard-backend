-- V15 回退：微信订阅消息。回退前先备份。
delete from sys_role_menu where menu_id between 2670 and 2689;
delete from sys_menu where menu_id between 2670 and 2689;
drop table if exists kg_subscribe_log;
drop table if exists kg_subscribe_grant;
drop table if exists kg_subscribe_template;
delete from kg_schema_version where version = 15;
