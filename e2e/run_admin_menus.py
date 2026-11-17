# -*- coding: utf-8 -*-
"""驱动 check-admin-menus.mjs：建受限角色与用户 → 两个账号各自真实登录 → 跑浏览器用例 → 删干净。

受限角色的建法与 C12（test_restricted_role.py）完全相同（复用它的 setup_role / teardown_role），
只授予 kangaroo:store:list 与 kangaroo:store:query。C12 验的是接口层一律 403，
这里验的是同一个账号登录后台之后，界面上看到的是什么。

无论浏览器用例成败，finally 里都会删掉角色与用户，并回库确认没有残留 —— 有残留就判失败。
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import admin_login, scalar, BASE  # noqa: E402
from test_restricted_role import (  # noqa: E402
    setup_role, teardown_role, login_as, USER_NAME, PASSWORD_PLAIN, ROLE_KEY)

APP = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                   '袋鼠旅游2')
ADMIN_URL = os.environ.get('ADMIN_TEST_URL', 'http://127.0.0.1:8081/')


def main():
    rc = 1
    setup_role()
    try:
        _, body = login_as(USER_NAME, PASSWORD_PLAIN)
        limited = body.get('token')
        if not limited:
            print('受限账号登录失败：%s' % body.get('msg'))
            return 1
        env = dict(os.environ, KG_ADMIN_TOKEN=admin_login(), KG_RESTRICTED_TOKEN=limited,
                   ADMIN_TEST_URL=ADMIN_URL, FLOW_API_BASE=BASE)
        sys.stdout.flush()
        rc = subprocess.run(['node', 'scripts/check-admin-menus.mjs'], cwd=APP, env=env,
                            shell=(os.name == 'nt')).returncode
    finally:
        teardown_role()
        left = int(scalar("select count(*) from sys_user where user_name=%s" % repr(USER_NAME)))
        left += int(scalar("select count(*) from sys_role where role_key=%s" % repr(ROLE_KEY)))
        if left:
            print('[FAIL] 受限角色/用户没有删干净，残留 %d 行' % left)
            rc = rc or 1
        else:
            print('受限角色与用户已删除，无残留')
    return rc


if __name__ == '__main__':
    sys.exit(main())
