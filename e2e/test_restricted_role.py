# -*- coding: utf-8 -*-
"""后台受限角色的越权验证。

## 为什么必须单独测这个

这个库里**只有一个后台角色**（超级管理员）和**一个后台用户**（admin），
而 admin 持有 `*:*:*` 通配权限 —— 它绕过所有 `@PreAuthorize` 判定。
也就是说：37 个 `kangaroo:*` 权限标识虽然都配了菜单、也都写在了 controller 上，
**但从来没有任何一次请求真正经过过权限判定**。

既有的 R10 用例只比对了"代码里的权限标识"与"sys_menu 里的菜单"两个集合是否一一对应，
那是配置完整性检查，不是越权检查。一个权限标识写错成 `kangaroo:store:lst`，
R10 会报出来；但"某个只被授予了门店权限的运营，能不能调岗位接口"这件事，
在本项目里从头到尾没有被验证过。

所以这里真的建一个受限角色、真的建一个用户、真的用它登录，再逐个接口打过去。

## 这个测试会写系统表

它会往 `sys_role` / `sys_user` / `sys_role_menu` / `sys_user_role` 插入记录，
跑完在 finally 里删干净（按外键顺序）。**只能在开发或测试库执行。**

用法：
    python e2e/test_restricted_role.py       # 需要后端已在 8080 运行
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import (  # noqa: E402
    http, sql, scalar, sql_exec, check, section, PASSED, FAILED, REDIS_DB)
from redis_client import MiniRedis  # noqa: E402

ROLE_KEY = 'kg_restricted_probe'
USER_NAME = 'kg_probe_user'
# 若依的密码是 BCrypt。这一串是 'admin123' 的 BCrypt 值，与内置 admin 用户库里存的同一个，
# 直接复用，避免在测试里引入 BCrypt 依赖，也避免把任何新口令写进仓库。
PASSWORD_PLAIN = 'admin123'

# 只授予门店的查看与查询，别的一概不给
GRANTED = ('kangaroo:store:list', 'kangaroo:store:query')


def login_as(username, password):
    """走与 admin_login 相同的真实登录路径，只是换个账号。"""
    status, body = http('GET', '/captchaImage')
    assert status == 200 and body.get('code') == 200, body
    uuid_ = body['uuid']
    code = ''
    if body.get('captchaEnabled', True):
        r = MiniRedis(db=REDIS_DB)
        try:
            raw = r.get('captcha_codes:' + uuid_)
        finally:
            r.close()
        assert raw is not None, 'Redis 里找不到验证码答案'
        code = raw.decode('utf-8').strip().strip('"')
    return http('POST', '/login', {'username': username, 'password': password,
                                   'code': code, 'uuid': uuid_})


def setup_role():
    """建受限角色与用户。密码哈希直接复制 admin 的，不新增任何口令。"""
    pwd_hash = scalar("select password from sys_user where user_name='admin'")
    assert pwd_hash, '库里没有 admin 用户，无法复用密码哈希'

    sql_exec("delete from sys_user_role where user_id in "
             "(select user_id from sys_user where user_name=%s)" % repr(USER_NAME))
    sql_exec("delete from sys_user where user_name=%s" % repr(USER_NAME))
    sql_exec("delete from sys_role_menu where role_id in "
             "(select role_id from sys_role where role_key=%s)" % repr(ROLE_KEY))
    sql_exec("delete from sys_role where role_key=%s" % repr(ROLE_KEY))

    sql_exec(
        "insert into sys_role (role_name, role_key, role_sort, data_scope, "
        " menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time) "
        "values ('受限角色探针', %s, 99, '1', 1, 1, '0', '0', 'e2e', sysdate())" % repr(ROLE_KEY))
    role_id = scalar("select role_id from sys_role where role_key=%s" % repr(ROLE_KEY))

    # 授予的菜单 = 被授权的按钮 + 它们所在的目录与页面（否则登录后连菜单都进不去）
    perms = ','.join(repr(p) for p in GRANTED)
    sql_exec(
        "insert into sys_role_menu (role_id, menu_id) "
        "select %s, menu_id from sys_menu where perms in (%s)" % (role_id, perms))
    sql_exec(
        "insert ignore into sys_role_menu (role_id, menu_id) "
        "select %s, m.parent_id from sys_menu m where m.perms in (%s) and m.parent_id > 0"
        % (role_id, perms))
    sql_exec(
        "insert ignore into sys_role_menu (role_id, menu_id) "
        "select %s, p.parent_id from sys_menu m join sys_menu p on p.menu_id = m.parent_id "
        "where m.perms in (%s) and p.parent_id > 0" % (role_id, perms))

    sql_exec(
        "insert into sys_user (user_name, nick_name, user_type, status, del_flag, "
        " password, create_by, create_time) "
        "values (%s, '受限角色探针', '00', '0', '0', %s, 'e2e', sysdate())"
        % (repr(USER_NAME), repr(pwd_hash)))
    user_id = scalar("select user_id from sys_user where user_name=%s" % repr(USER_NAME))
    sql_exec("insert into sys_user_role (user_id, role_id) values (%s, %s)" % (user_id, role_id))
    return role_id, user_id


def teardown_role():
    sql_exec("delete from sys_user_role where user_id in "
             "(select user_id from sys_user where user_name=%s)" % repr(USER_NAME))
    sql_exec("delete from sys_user where user_name=%s" % repr(USER_NAME))
    sql_exec("delete from sys_role_menu where role_id in "
             "(select role_id from sys_role where role_key=%s)" % repr(ROLE_KEY))
    sql_exec("delete from sys_role where role_key=%s" % repr(ROLE_KEY))


def main():
    section('P0 建立受限角色与用户')
    role_id, user_id = setup_role()
    granted_menus = scalar("select count(*) from sys_role_menu where role_id=%s" % role_id)
    check('P0 受限角色已建立', True, role_id is not None, ok=role_id is not None)
    check('P0 只授予了少量菜单（不是全量）', True,
          0 < int(granted_menus) < 20, ok=0 < int(granted_menus) < 20)

    try:
        section('P1 受限账号能正常登录')
        status, body = login_as(USER_NAME, PASSWORD_PLAIN)
        check('P1 受限账号登录成功', 200, body.get('code'))
        token = body.get('token')
        assert token, '拿不到受限账号的令牌，后续无意义'

        status, body = http('GET', '/getInfo', token=token)
        check('P1 getInfo 返回该账号', USER_NAME, (body.get('user') or {}).get('userName'))
        perms = body.get('permissions') or []
        check('P1 该账号不持有通配权限', False, '*:*:*' in perms)
        check('P1 该账号只拿到被授予的那几个权限', sorted(GRANTED), sorted(perms))

        section('P2 被授予的接口可以访问')
        status, body = http('GET', '/kangaroo/store/list', token=token)
        check('P2 门店列表可访问', 200, body.get('code'))
        store_id = scalar("select store_id from kg_store where del_flag='0' limit 1")
        status, body = http('GET', '/kangaroo/store/%s' % store_id, token=token)
        check('P2 门店详情可访问', 200, body.get('code'))

        section('P3 未被授予的接口必须 403（这是本文件的核心）')
        # 覆盖增删改查各类动作，不只挑一个
        denied = [
            ('POST', '/kangaroo/store', {'storeName': '越权探针', 'city': '测试城'}, '新增门店'),
            ('PUT', '/kangaroo/store', {'storeId': int(store_id), 'remark': 'x'}, '编辑门店'),
            ('DELETE', '/kangaroo/store/%s' % store_id, None, '删除门店'),
            ('PUT', '/kangaroo/store/cert/%s?certified=true' % store_id, None, '安心认证'),
            ('GET', '/kangaroo/job/list', None, '岗位列表'),
            ('POST', '/kangaroo/job', {'title': 'x'}, '新增岗位'),
            ('PUT', '/kangaroo/job/changeStatus', {'jobId': 1, 'status': 'published'}, '岗位上下架'),
            ('PUT', '/kangaroo/job/recalc?jobId=1', None, '重算名额'),
            ('GET', '/kangaroo/application/list', None, '报名列表'),
            ('PUT', '/kangaroo/application/audit', {'appId': 1, 'pass': True}, '报名审核'),
            ('PUT', '/kangaroo/application/terminate', {'appId': 1}, '终止报名'),
            ('GET', '/kangaroo/appuser/list', None, '小程序账号列表'),
            ('PUT', '/kangaroo/appuser/changeStatus', {'userId': 1, 'status': '1'}, '停用账号'),
            ('GET', '/kangaroo/grant/list', None, '员工授权列表'),
            ('POST', '/kangaroo/grant', {'appUserId': 1, 'storeId': 1, 'roleCode': 'manager'}, '授予员工'),
            ('GET', '/kangaroo/slot/list', None, '面试时段列表'),
            ('POST', '/kangaroo/slot', {'storeId': 1}, '新增时段'),
            ('GET', '/kangaroo/message/list', None, '站内消息列表'),
            ('POST', '/kangaroo/store/export', None, '门店导出'),
        ]
        for method, path, payload, label in denied:
            status, body = http(method, path, payload, token=token)
            check('P3 未授权的「%s」被拒绝' % label, 403, body.get('code'))

        section('P4 前端菜单也只下发被授权的部分')
        status, body = http('GET', '/getRouters', token=token)
        routers = body.get('data') or []

        def flat(rs, acc):
            for r in rs:
                acc.append(r)
                flat(r.get('children') or [], acc)
            return acc

        all_routes = flat(routers, [])
        paths = [str(r.get('path', '')) for r in all_routes]
        kangaroo_leafs = [p for p in paths if p in
                          ('store', 'job', 'application', 'slot', 'grant', 'appuser', 'message')]
        check('P4 侧边栏出现了门店管理', True, 'store' in kangaroo_leafs,
              ok='store' in kangaroo_leafs)
        check('P4 侧边栏不包含未授权的业务页', [],
              [p for p in kangaroo_leafs if p != 'store'])

        section('P5 越权请求不能改动任何数据')
        left = scalar("select count(*) from kg_store where store_name='越权探针'")
        check('P5 被拒的新增没有落库', '0', left)
        still = scalar("select del_flag from kg_store where store_id=%s" % store_id)
        check('P5 被拒的删除没有生效', '0', still)

    finally:
        section('清理：删除受限角色与用户')
        teardown_role()
        left_u = scalar("select count(*) from sys_user where user_name=%s" % repr(USER_NAME))
        left_r = scalar("select count(*) from sys_role where role_key=%s" % repr(ROLE_KEY))
        check('清理后无残留用户', '0', left_u)
        check('清理后无残留角色', '0', left_r)

    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
