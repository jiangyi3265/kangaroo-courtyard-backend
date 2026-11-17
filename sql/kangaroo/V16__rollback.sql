-- V16 回退：撤掉 2700–2759 号段的员工运营菜单（回到 V12 之后缺失的状态），并放回 V10 那 4 个无实现的菜单权限。
delete from sys_role_menu where menu_id between 2700 and 2759;
delete from sys_menu where menu_id between 2700 and 2759;
insert ignore into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2314, '动态导出', 2310, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:post:export', '#', 'admin', sysdate(), ''),
(2334, '活动导出', 2330, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:activity:export', '#', 'admin', sysdate(), ''),
(2343, '报名导出', 2340, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:actsignup:export', '#', 'admin', sysdate(), ''),
(2362, '会话处置', 2360, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:chatconv:audit', '#', 'admin', sysdate(), '');
delete from kg_schema_version where version = 16;
