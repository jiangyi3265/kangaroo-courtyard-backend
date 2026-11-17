-- V2 回退：删除本脚本插入的菜单与业务字典（不影响任何 kg_ 业务数据）
delete from sys_role_menu where menu_id between 1 and 1100;
delete from sys_menu where menu_id between 1 and 1100;
delete from sys_role_menu where menu_id between 2000 and 2999;
delete from sys_menu where menu_id between 2000 and 2999;
delete from sys_dict_data where dict_type like 'kg_%';
delete from sys_dict_type where dict_type like 'kg_%';
