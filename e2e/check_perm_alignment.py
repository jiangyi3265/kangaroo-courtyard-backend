# -*- coding: utf-8 -*-
"""后台按钮的权限标识，与后端接口上的 @PreAuthorize，是否一一对应。

为什么要有它：读码时发现过两处不对齐，而此前没有任何检查能发现 ——
  · 小程序账号页的停用开关没挂 v-hasPermi，后端却要 kangaroo:appuser:status：
    没有这个权限的运营会看到一个"点下去才 403"的开关；
  · kangaroo:job:recalc 有后端接口、有菜单权限，岗位页却没有任何按钮用它：
    名额计数漂移之后，运营在后台没有任何办法纠正。
C06 的 R10 比的是"后端标识 ↔ sys_menu"，比不到前端按钮这一层。

两个方向都查：
  1. 前端每一个 v-hasPermi 的标识，后端都必须真的在校验它
     （否则按钮的显隐和后端的放行是两套规则，迟早对不上）；
  2. 后端每一个**写动作**的标识，前端都必须有一个按钮挂着它
     （否则就是一个有权限、有接口、却点不到的能力）。
     :list 与 :query 不算写动作 —— 它们守的是页面与详情接口，
     由菜单与"修改"按钮间接触发，不需要单独的按钮。
"""
import glob
import io
import os
import re
import sys

# 按本文件位置定位项目根：写死的绝对路径在项目目录改名后指向不存在的目录，glob 一个文件都扫不到，
# 只剩 ext_perms 那一部分在比对，照样打印"通过"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BACK = os.path.join(ROOT, 'RuoYi-Vue', 'ruoyi-kangaroo', 'src', 'main', 'java')
FRONT = os.path.join(ROOT, 'RuoYi-Vue3', 'src', 'views', 'kangaroo')

READ_ONLY = ('list', 'query')


def collect(pattern, root, exts):
    found = {}
    for ext in exts:
        for f in glob.glob(os.path.join(root, '**', '*' + ext), recursive=True):
            text = io.open(f, encoding='utf-8', errors='replace').read()
            for m in re.finditer(pattern, text):
                found.setdefault(m.group(1), os.path.relpath(f, ROOT).replace(os.sep, '/'))
    return found


def main():
    back = collect(r"hasPermi\('(kangaroo:[a-z]+:[a-z]+)'\)", BACK, ['.java'])
    front = collect(r"(kangaroo:[a-z]+:[a-z]+)", FRONT, ['.vue'])
    # 通用增删改查页（ExtEntity 登记 + ExtCrud.vue 渲染）：后端按 @extEntities.perm(...) 动态校验、
    # 前端按 meta 动态挂 v-hasPermi，字面量扫描两边都看不到。按同一份登记补进来；
    # 实体有写动作却没有页面组件（views/kangaroo/ext/<key>/index.vue）时，照样会被第 2 条报出来。
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import ext_perms
    for key, e in ext_perms.entities().items():
        for perm in e['backend']:
            back.setdefault(perm, e['file'])
    for perm, where in ext_perms.button_perms().items():
        front.setdefault(perm, where)

    ok = 0
    bad = []

    # 1. 前端有、后端不校验的
    for perm, where in sorted(front.items()):
        if perm in back:
            ok += 1
        else:
            bad.append('前端 %s 挂了 %s，但后端没有任何接口校验它' % (where, perm))

    # 2. 后端写动作、前端没有按钮的
    for perm, where in sorted(back.items()):
        action = perm.rsplit(':', 1)[-1]
        if action in READ_ONLY:
            continue
        if perm in front:
            ok += 1
        else:
            bad.append('后端 %s 校验 %s，但后台页面上没有任何按钮挂着它（有接口却点不到）'
                       % (where, perm))

    print('后端 kangaroo 权限 %d 个，前端按钮用到 %d 个' % (len(back), len(front)))
    if bad:
        print('[不对齐] %d 处：' % len(bad))
        for b in bad:
            print('  - ' + b)
        print('通过 %d / 共 %d' % (ok, ok + len(bad)))
        return 1
    print('通过 %d / 共 %d' % (ok, ok))
    return 0


if __name__ == '__main__':
    sys.exit(main())
