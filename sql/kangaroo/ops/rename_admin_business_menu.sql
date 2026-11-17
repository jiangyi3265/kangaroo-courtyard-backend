-- 后台目录显示名调整；路由、子菜单、权限以及门店名称沿用原设置。
-- 属于管理员可修改的菜单配置，不改变数据库结构或历史迁移校验和。
UPDATE sys_menu
SET menu_name = '袋鼠任务', update_by = 'admin', update_time = NOW()
WHERE menu_id = 2000
  AND parent_id = 0
  AND path = 'kangaroo'
  AND menu_type = 'M'
  AND menu_name = '袋鼠小院';
