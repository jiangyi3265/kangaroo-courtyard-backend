-- =============================================================================
-- 袋鼠小院 V2 —— 管理后台菜单与业务字典
--
-- 背景：dkxy 库的 sys_menu / sys_role_menu 初始为 0 行，管理后台登录后侧边栏全空，
--       连若依自带的用户/角色/菜单管理都进不去。本脚本补齐：
--       1) 若依系统管理 + 系统监控菜单（不含定时任务与代码生成——这两个 Maven 模块
--          在父 pom 中被注释掉，对应接口不存在，挂菜单会 500）
--       2) 袋鼠小院业务菜单与按钮权限
--       3) 业务状态字典
--
-- 幂等：系统菜单按 1-1100 区间、业务菜单与字典按 V2 **自己的** menu_id / 字典类型清单删除再插入，
--       可重复执行。重跑不会碰 V6 起各迁移在 2000-2999 段挂的菜单、也不会碰它们的 kg_ 字典（WP01）。
--       重跑 V2 之后要再跑一次 V5（V2 会把「数据监控」菜单重新插成可见）；V6 起的迁移不用重跑。
--       V6 的迁移账本里记着本脚本的 checksum：改了本脚本，要在库里重跑它，再重跑 V6 更新账本。
-- 回退：执行同目录 V2__rollback.sql
-- =============================================================================

-- ----------------------------
-- 1、若依系统菜单（menu_id 1-1100）
-- ----------------------------
-- 这里原先还有一条 `delete from sys_role_menu where menu_id between 1 and 1100;`。
-- 删掉它的理由：脚本头部写着"可重复执行"，但删 sys_role_menu 之后全文没有任何一条
-- insert into sys_role_menu —— 重跑一次就会静默清空所有角色的菜单授权。
-- 超管持 *:*:* 看不出来，其它角色的侧边栏会直接空掉，而脚本一声不吭。
-- menu_id 区间是固定的，重跑时 sys_menu 被按同样的 id 重新插入，
-- 原有的 sys_role_menu 关联天然仍然有效，本来就不需要先删它。
delete from sys_menu where menu_id between 1 and 1100;

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(1, '系统管理', 0, 1, 'system',  null, '', '', 1, 0, 'M', '0', '0', '',  'system',   'admin', sysdate(), '系统管理目录'),
(2, '系统监控', 0, 2, 'monitor', null, '', '', 1, 0, 'M', '0', '0', '',  'monitor',  'admin', sysdate(), '系统监控目录'),
(100, '用户管理', 1, 1, 'user',   'system/user/index',        '', '', 1, 0, 'C', '0', '0', 'system:user:list',     'user',       'admin', sysdate(), '用户管理菜单'),
(101, '角色管理', 1, 2, 'role',   'system/role/index',        '', '', 1, 0, 'C', '0', '0', 'system:role:list',     'peoples',    'admin', sysdate(), '角色管理菜单'),
(102, '菜单管理', 1, 3, 'menu',   'system/menu/index',        '', '', 1, 0, 'C', '0', '0', 'system:menu:list',     'tree-table', 'admin', sysdate(), '菜单管理菜单'),
(103, '部门管理', 1, 4, 'dept',   'system/dept/index',        '', '', 1, 0, 'C', '0', '0', 'system:dept:list',     'tree',       'admin', sysdate(), '部门管理菜单'),
(104, '岗位管理', 1, 5, 'post',   'system/post/index',        '', '', 1, 0, 'C', '0', '0', 'system:post:list',     'post',       'admin', sysdate(), '岗位管理菜单'),
(105, '字典管理', 1, 6, 'dict',   'system/dict/index',        '', '', 1, 0, 'C', '0', '0', 'system:dict:list',     'dict',       'admin', sysdate(), '字典管理菜单'),
(106, '参数设置', 1, 7, 'config', 'system/config/index',      '', '', 1, 0, 'C', '0', '0', 'system:config:list',   'edit',       'admin', sysdate(), '参数设置菜单'),
(107, '通知公告', 1, 8, 'notice', 'system/notice/index',      '', '', 1, 0, 'C', '0', '0', 'system:notice:list',   'message',    'admin', sysdate(), '通知公告菜单'),
(108, '日志管理', 1, 9, 'log',    null,                       '', '', 1, 0, 'M', '0', '0', '',                     'log',        'admin', sysdate(), '日志管理菜单'),
(109, '在线用户', 2, 1, 'online', 'monitor/online/index',     '', '', 1, 0, 'C', '0', '0', 'monitor:online:list',  'online',     'admin', sysdate(), '在线用户菜单'),
(111, '数据监控', 2, 3, 'druid',  'monitor/druid/index',      '', '', 1, 0, 'C', '0', '0', 'monitor:druid:list',   'druid',      'admin', sysdate(), '数据监控菜单'),
(112, '服务监控', 2, 4, 'server', 'monitor/server/index',     '', '', 1, 0, 'C', '0', '0', 'monitor:server:list',  'server',     'admin', sysdate(), '服务监控菜单'),
(113, '缓存监控', 2, 5, 'cache',  'monitor/cache/index',      '', '', 1, 0, 'C', '0', '0', 'monitor:cache:list',   'redis',      'admin', sysdate(), '缓存监控菜单'),
(114, '缓存列表', 2, 6, 'cacheList', 'monitor/cache/list',    '', '', 1, 0, 'C', '0', '0', 'monitor:cache:list',   'redis-list', 'admin', sysdate(), '缓存列表菜单'),
(500, '操作日志', 108, 1, 'operlog',    'monitor/operlog/index',    '', '', 1, 0, 'C', '0', '0', 'monitor:operlog:list',    'form',  'admin', sysdate(), '操作日志菜单'),
(501, '登录日志', 108, 2, 'logininfor', 'monitor/logininfor/index', '', '', 1, 0, 'C', '0', '0', 'monitor:logininfor:list', 'logininfor', 'admin', sysdate(), '登录日志菜单');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(1001, '用户查询', 100, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'system:user:query',  '#', 'admin', sysdate(), ''),
(1002, '用户新增', 100, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'system:user:add',    '#', 'admin', sysdate(), ''),
(1003, '用户修改', 100, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'system:user:edit',   '#', 'admin', sysdate(), ''),
(1004, '用户删除', 100, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'system:user:remove', '#', 'admin', sysdate(), ''),
(1005, '用户导出', 100, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'system:user:export', '#', 'admin', sysdate(), ''),
(1006, '用户导入', 100, 6, '', null, '', '', 1, 0, 'F', '0', '0', 'system:user:import', '#', 'admin', sysdate(), ''),
(1007, '重置密码', 100, 7, '', null, '', '', 1, 0, 'F', '0', '0', 'system:user:resetPwd', '#', 'admin', sysdate(), ''),
(1008, '角色查询', 101, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'system:role:query',  '#', 'admin', sysdate(), ''),
(1009, '角色新增', 101, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'system:role:add',    '#', 'admin', sysdate(), ''),
(1010, '角色修改', 101, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'system:role:edit',   '#', 'admin', sysdate(), ''),
(1011, '角色删除', 101, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'system:role:remove', '#', 'admin', sysdate(), ''),
(1012, '角色导出', 101, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'system:role:export', '#', 'admin', sysdate(), ''),
(1013, '菜单查询', 102, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'system:menu:query',  '#', 'admin', sysdate(), ''),
(1014, '菜单新增', 102, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'system:menu:add',    '#', 'admin', sysdate(), ''),
(1015, '菜单修改', 102, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'system:menu:edit',   '#', 'admin', sysdate(), ''),
(1016, '菜单删除', 102, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'system:menu:remove', '#', 'admin', sysdate(), ''),
(1017, '部门查询', 103, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dept:query',  '#', 'admin', sysdate(), ''),
(1018, '部门新增', 103, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dept:add',    '#', 'admin', sysdate(), ''),
(1019, '部门修改', 103, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dept:edit',   '#', 'admin', sysdate(), ''),
(1020, '部门删除', 103, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dept:remove', '#', 'admin', sysdate(), ''),
(1021, '岗位查询', 104, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'system:post:query',  '#', 'admin', sysdate(), ''),
(1022, '岗位新增', 104, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'system:post:add',    '#', 'admin', sysdate(), ''),
(1023, '岗位修改', 104, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'system:post:edit',   '#', 'admin', sysdate(), ''),
(1024, '岗位删除', 104, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'system:post:remove', '#', 'admin', sysdate(), ''),
(1025, '岗位导出', 104, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'system:post:export', '#', 'admin', sysdate(), ''),
(1026, '字典查询', 105, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dict:query',  '#', 'admin', sysdate(), ''),
(1027, '字典新增', 105, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dict:add',    '#', 'admin', sysdate(), ''),
(1028, '字典修改', 105, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dict:edit',   '#', 'admin', sysdate(), ''),
(1029, '字典删除', 105, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dict:remove', '#', 'admin', sysdate(), ''),
(1030, '字典导出', 105, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'system:dict:export', '#', 'admin', sysdate(), ''),
(1031, '参数查询', 106, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'system:config:query',  '#', 'admin', sysdate(), ''),
(1032, '参数新增', 106, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'system:config:add',    '#', 'admin', sysdate(), ''),
(1033, '参数修改', 106, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'system:config:edit',   '#', 'admin', sysdate(), ''),
(1034, '参数删除', 106, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'system:config:remove', '#', 'admin', sysdate(), ''),
(1035, '参数导出', 106, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'system:config:export', '#', 'admin', sysdate(), ''),
(1036, '公告查询', 107, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'system:notice:query',  '#', 'admin', sysdate(), ''),
(1037, '公告新增', 107, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'system:notice:add',    '#', 'admin', sysdate(), ''),
(1038, '公告修改', 107, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'system:notice:edit',   '#', 'admin', sysdate(), ''),
(1039, '公告删除', 107, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'system:notice:remove', '#', 'admin', sysdate(), ''),
(1040, '操作查询', 500, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:query',  '#', 'admin', sysdate(), ''),
(1041, '操作删除', 500, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:remove', '#', 'admin', sysdate(), ''),
(1042, '日志导出', 500, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:operlog:export', '#', 'admin', sysdate(), ''),
(1043, '登录查询', 501, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:query',  '#', 'admin', sysdate(), ''),
(1044, '登录删除', 501, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:remove', '#', 'admin', sysdate(), ''),
(1045, '日志导出', 501, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:export', '#', 'admin', sysdate(), ''),
(1046, '账户解锁', 501, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:logininfor:unlock', '#', 'admin', sysdate(), ''),
(1047, '在线查询', 109, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:online:query',       '#', 'admin', sysdate(), ''),
(1048, '批量强退', 109, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:online:batchLogout', '#', 'admin', sysdate(), ''),
(1049, '单条强退', 109, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'monitor:online:forceLogout', '#', 'admin', sysdate(), '');

-- ----------------------------
-- 2、袋鼠小院业务菜单（menu_id 2000-2999）
-- ----------------------------
-- 同上：不删 sys_role_menu，否则"可重复执行"就是一句会造成损失的话。
-- 只删 V2 自己的 38 个 menu_id。这里原先是 `between 2000 and 2999` 整段删：V6 起的迁移会往这一段
-- 挂新菜单（2100 平台与合规、挂在岗位页下的 2048 岗位审核、挂在报名页下的 2056 标记入职……），
-- 重跑 V2 会把它们一并删掉，而那些迁移不会跟着重跑 —— 后台菜单静默消失、角色授权悬空。
delete from sys_menu where menu_id in (
    2000,
    2010, 2011, 2012, 2013,
    2020, 2021, 2022, 2023, 2024, 2025, 2026,
    2030, 2031, 2032, 2033, 2034,
    2040, 2041, 2042, 2043, 2044, 2045, 2046, 2047,
    2050, 2051, 2052, 2053, 2054, 2055,
    2060, 2061, 2062, 2063, 2064,
    2070, 2071);

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2000, '袋鼠小院', 0, 0, 'kangaroo', null, '', '', 1, 0, 'M', '0', '0', '', 'guide', 'admin', sysdate(), '袋鼠小院业务目录'),
(2010, '小程序账号', 2000, 1, 'appuser',     'kangaroo/appuser/index',     '', 'KgAppUser',     1, 0, 'C', '0', '0', 'kangaroo:appuser:list',     'people',   'admin', sysdate(), '小程序 C 端账号'),
(2020, '门店管理',   2000, 2, 'store',       'kangaroo/store/index',       '', 'KgStore',       1, 0, 'C', '0', '0', 'kangaroo:store:list',       'shopping', 'admin', sysdate(), '门店/商家'),
(2030, '员工授权',   2000, 3, 'grant',       'kangaroo/grant/index',       '', 'KgGrant',       1, 0, 'C', '0', '0', 'kangaroo:grant:list',       'peoples',  'admin', sysdate(), '小程序账号的门店员工授权'),
(2040, '岗位管理',   2000, 4, 'job',         'kangaroo/job/index',         '', 'KgJob',         1, 0, 'C', '0', '0', 'kangaroo:job:list',         'post',     'admin', sysdate(), '岗位发布与上下架'),
(2050, '报名管理',   2000, 5, 'application', 'kangaroo/application/index', '', 'KgApplication', 1, 0, 'C', '0', '0', 'kangaroo:application:list', 'form',     'admin', sysdate(), '岗位报名与履约状态'),
(2060, '面试时段',   2000, 6, 'slot',        'kangaroo/slot/index',        '', 'KgSlot',        1, 0, 'C', '0', '0', 'kangaroo:slot:list',        'date',     'admin', sysdate(), '门店可选面试时段'),
(2070, '站内消息',   2000, 7, 'message',     'kangaroo/message/index',     '', 'KgMessage',     1, 0, 'C', '0', '0', 'kangaroo:message:list',     'message',  'admin', sysdate(), '小程序站内消息');

insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
-- 小程序账号：只读 + 停用/启用，不提供新增与删除（账号由微信登录创建，后台凭空造账号会产生无 openid 的孤儿数据）
(2011, '账号查询', 2010, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appuser:query',  '#', 'admin', sysdate(), ''),
(2012, '账号停用', 2010, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appuser:status', '#', 'admin', sysdate(), '停用后立即踢下线'),
(2013, '账号导出', 2010, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:appuser:export', '#', 'admin', sysdate(), ''),
-- 门店：完整 CRUD + 认证
(2021, '门店查询', 2020, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:store:query',  '#', 'admin', sysdate(), ''),
(2022, '门店新增', 2020, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:store:add',    '#', 'admin', sysdate(), ''),
(2023, '门店修改', 2020, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:store:edit',   '#', 'admin', sysdate(), ''),
(2024, '门店删除', 2020, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:store:remove', '#', 'admin', sysdate(), '仅无岗位无报名时允许'),
(2025, '安心认证', 2020, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:store:cert',   '#', 'admin', sysdate(), '安心商家认证与撤销'),
(2026, '门店导出', 2020, 6, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:store:export', '#', 'admin', sysdate(), ''),
-- 员工授权：授予/撤销
(2031, '授权查询', 2030, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:grant:query',  '#', 'admin', sysdate(), ''),
(2032, '授予员工', 2030, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:grant:add',    '#', 'admin', sysdate(), ''),
(2033, '修改角色', 2030, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:grant:edit',   '#', 'admin', sysdate(), ''),
(2034, '撤销授权', 2030, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:grant:revoke', '#', 'admin', sysdate(), '撤销后小程序侧会话立即失去员工身份'),
-- 岗位：CRUD + 上下架
(2041, '岗位查询', 2040, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:job:query',   '#', 'admin', sysdate(), ''),
(2042, '岗位新增', 2040, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:job:add',     '#', 'admin', sysdate(), ''),
(2043, '岗位修改', 2040, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:job:edit',    '#', 'admin', sysdate(), ''),
(2044, '岗位删除', 2040, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:job:remove',  '#', 'admin', sysdate(), '仅草稿且无报名时允许'),
(2045, '上下架',   2040, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:job:publish', '#', 'admin', sysdate(), '发布/暂停/关闭'),
(2046, '岗位导出', 2040, 6, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:job:export',  '#', 'admin', sysdate(), ''),
(2047, '重算名额', 2040, 7, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:job:recalc',  '#', 'admin', sysdate(), '按报名数据纠正已录用人数'),
-- 报名：只读 + 审核/终止，不提供新增与删除（报名只能由小程序用户发起）
(2051, '报名查询', 2050, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:application:query',     '#', 'admin', sysdate(), ''),
(2052, '报名审核', 2050, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:application:audit',     '#', 'admin', sysdate(), '通过/驳回'),
(2053, '确认录用', 2050, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:application:confirm',   '#', 'admin', sysdate(), '商家确认录用'),
(2054, '终止申请', 2050, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:application:terminate', '#', 'admin', sysdate(), ''),
(2055, '报名导出', 2050, 5, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:application:export',    '#', 'admin', sysdate(), ''),
-- 面试时段
(2061, '时段查询', 2060, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:slot:query',  '#', 'admin', sysdate(), ''),
(2062, '时段新增', 2060, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:slot:add',    '#', 'admin', sysdate(), ''),
(2063, '时段修改', 2060, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:slot:edit',   '#', 'admin', sysdate(), '仅未被占用时允许'),
(2064, '时段删除', 2060, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:slot:remove', '#', 'admin', sysdate(), '仅未被占用时允许'),
-- 站内消息：只读（消息由业务流程产生，后台不手工造）
(2071, '消息查询', 2070, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:message:query', '#', 'admin', sysdate(), '');

-- ----------------------------
-- 3、业务字典
-- ----------------------------
-- 只删 V2 自己的 8 个字典类型。这里原先是 `like 'kg_%'`：V6 起每个迁移都建自己的 kg_ 字典，
-- 重跑 V2 会把它们全删掉。
-- 反过来，后续迁移不要往这 8 个类型里追加取值 —— 重跑 V2 会把追加的值删掉；
-- 需要的取值直接写进本脚本（kg_msg_category 的 group 群聊就是这样加进来的）。
delete from sys_dict_data where dict_type in ('kg_job_type', 'kg_job_status', 'kg_salary_type', 'kg_board_type',
                                              'kg_app_status', 'kg_staff_role', 'kg_msg_category', 'kg_slot_status');
delete from sys_dict_type where dict_type in ('kg_job_type', 'kg_job_status', 'kg_salary_type', 'kg_board_type',
                                              'kg_app_status', 'kg_staff_role', 'kg_msg_category', 'kg_slot_status');

insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark) values
('袋鼠-岗位分类',   'kg_job_type',       '0', 'admin', sysdate(), '义工换宿/兼职旅行/全职旅行/在线兼职'),
('袋鼠-岗位状态',   'kg_job_status',     '0', 'admin', sysdate(), '草稿/招聘中/暂停/已关闭'),
('袋鼠-计薪方式',   'kg_salary_type',    '0', 'admin', sysdate(), '日薪/月薪/计件/无薪资'),
('袋鼠-食宿',       'kg_board_type',     '0', 'admin', sysdate(), '包食宿/包住宿/包三餐/包部分/不包'),
('袋鼠-报名状态',   'kg_app_status',     '0', 'admin', sysdate(), '报名履约状态机'),
('袋鼠-员工角色',   'kg_staff_role',     '0', 'admin', sysdate(), '主理人/门店管家/员工'),
('袋鼠-消息分类',   'kg_msg_category',   '0', 'admin', sysdate(), '系统/报名/面试/签约/订单/会话/群聊'),
('袋鼠-面试时段状态','kg_slot_status',   '0', 'admin', sysdate(), '可选/已占用/已关闭');

insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark) values
(1, '义工换宿', 'volunteer', 'kg_job_type', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(2, '兼职旅行', 'parttime',  'kg_job_type', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(3, '全职旅行', 'fulltime',  'kg_job_type', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(4, '在线兼职', 'online',    'kg_job_type', '', 'info',    'N', '0', 'admin', sysdate(), ''),

(1, '草稿',   'draft',     'kg_job_status', '', 'info',    'Y', '0', 'admin', sysdate(), ''),
(2, '招聘中', 'published', 'kg_job_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(3, '暂停',   'paused',    'kg_job_status', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(4, '已关闭', 'closed',    'kg_job_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),

(1, '日薪', 'daily',    'kg_salary_type', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(2, '月薪', 'monthly',  'kg_salary_type', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(3, '计件', 'piece',    'kg_salary_type', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(4, '无薪资', 'none',   'kg_salary_type', '', 'info',    'Y', '0', 'admin', sysdate(), ''),

(1, '包食宿',  'all',  'kg_board_type', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(2, '包住宿',  'room', 'kg_board_type', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(3, '包三餐',  'meal', 'kg_board_type', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(4, '包部分',  'part', 'kg_board_type', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(5, '不包',    'none', 'kg_board_type', '', 'info',    'Y', '0', 'admin', sysdate(), ''),

(1,  '已提交',     'submitted',        'kg_app_status', '', 'info',    'Y', '0', 'admin', sysdate(), ''),
(2,  '审核通过',   'review_passed',    'kg_app_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(3,  '审核未通过', 'review_rejected',  'kg_app_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(4,  '已撤回',     'withdrawn',        'kg_app_status', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(5,  '已预约面试', 'interview_booked', 'kg_app_status', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(6,  '面试已完成', 'interview_done',   'kg_app_status', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(7,  '用户已确认', 'user_confirmed',   'kg_app_status', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(8,  '商家已确认', 'store_confirmed',  'kg_app_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
-- 「已签署责任书」而不是「已签署」：后端 ApplicationStatus.label 用的是前者，
-- 小程序显示的就是它。字典这边写「已签署」会让同一条报名在两端显示成两个词。
(9,  '已签署责任书', 'contract_signed',  'kg_app_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(10, '已入职',     'onboarded',        'kg_app_status', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(11, '已终止',     'terminated',       'kg_app_status', '', 'danger',  'N', '0', 'admin', sysdate(), ''),

(1, '主理人',   'manager', 'kg_staff_role', '', 'danger',  'N', '0', 'admin', sysdate(), ''),
(2, '门店管家', 'keeper',  'kg_staff_role', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(3, '员工',     'staff',   'kg_staff_role', '', 'primary', 'Y', '0', 'admin', sysdate(), ''),

(1, '系统', 'system',    'kg_msg_category', '', 'info',    'N', '0', 'admin', sysdate(), ''),
(2, '报名', 'apply',     'kg_msg_category', '', 'primary', 'N', '0', 'admin', sysdate(), ''),
(3, '面试', 'interview', 'kg_msg_category', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(4, '签约', 'contract',  'kg_msg_category', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(5, '订单', 'order',     'kg_msg_category', '', 'success', 'N', '0', 'admin', sysdate(), ''),
(6, '会话', 'chat',      'kg_msg_category', '', 'info',    'N', '0', 'admin', sysdate(), ''),
-- 群聊：消息中心"群聊"页签的分类（WP09 接线）。直接写在 V2 里而不是由后续迁移追加，理由见上方字典删除处
(7, '群聊', 'group',     'kg_msg_category', '', 'primary', 'N', '0', 'admin', sysdate(), ''),

(1, '可选',   'open',   'kg_slot_status', '', 'success', 'Y', '0', 'admin', sysdate(), ''),
(2, '已占用', 'booked', 'kg_slot_status', '', 'warning', 'N', '0', 'admin', sysdate(), ''),
(3, '已关闭', 'closed', 'kg_slot_status', '', 'info',    'N', '0', 'admin', sysdate(), '');
