# -*- coding: utf-8 -*-
"""从环境变量或本地（已 gitignore 的）数据源配置里取数据库口令。

不在任何入库文件里硬编码数据库口令：
  1. 优先读环境变量 KG_DB_PASSWORD；
  2. 其次从 RuoYi-Vue/ruoyi-admin/src/main/resources/application-druid.yml
     里解析 ${DB_PASSWORD:xxx} 的本地默认值 —— 该文件被 .gitignore 排除，
     只存在于开发机上；
  3. 都取不到就直接报错退出，而不是猜一个口令。
"""
import os
import re
import sys

_DRUID = os.path.join('ruoyi-admin', 'src', 'main', 'resources', 'application-druid.yml')


def _from_druid(backend_root):
    path = os.path.join(backend_root, _DRUID)
    if not os.path.exists(path):
        return None
    with open(path, encoding='utf-8') as f:
        for line in f:
            m = re.search(r'password:\s*\$\{DB_PASSWORD:([^}]*)\}', line)
            if m:
                return m.group(1)
    return None


def db_password(backend_root):
    pwd = os.environ.get('KG_DB_PASSWORD')
    if pwd:
        return pwd
    pwd = _from_druid(backend_root)
    if pwd:
        return pwd
    sys.stderr.write(
        '[配置缺失] 拿不到数据库口令。请设置环境变量 KG_DB_PASSWORD，'
        '或确认 %s 存在且含 ${DB_PASSWORD:...}。\n'
        '不会使用任何内置的默认口令。\n' % _DRUID)
    raise SystemExit(2)
