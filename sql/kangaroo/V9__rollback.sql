-- V9 回退：旅居（房源、房型、房态、早鸟、旅居订单、增值服务与服务订单）。回退前先备份这些表。
delete from sys_menu where menu_id between 2200 and 2299;
delete from sys_dict_data where dict_type in ('kg_stay_status', 'kg_stay_order_status', 'kg_shop_order_status', 'kg_shop_category',
  'kg_cancel_policy', 'kg_room_kind', 'kg_stay_facility');
delete from sys_dict_type where dict_type in ('kg_stay_status', 'kg_stay_order_status', 'kg_shop_order_status', 'kg_shop_category',
  'kg_cancel_policy', 'kg_room_kind', 'kg_stay_facility');
drop table if exists kg_stay_qualification;
drop table if exists kg_stay_vlog_comment;
drop table if exists kg_stay_vlog_event;
drop table if exists kg_stay_vlog;
drop table if exists kg_shop_order;
drop table if exists kg_shop_service;
drop table if exists kg_stay_order;
drop table if exists kg_stay_promo;
drop table if exists kg_stay_calendar;
drop table if exists kg_stay_room_type;
drop table if exists kg_stay;
delete from kg_schema_version where version = 9;
