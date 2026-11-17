# -*- coding: utf-8 -*-
"""静态检查：标了「登录可选」（@AppAuth(required = false)）的小程序接口里，不能调用会在未登录时抛 401 的
AppUserContext.getUserId() / require()。

为什么要它：2026-09-26 回归发现社区信息流、帖子详情、活动详情等 8 个"游客可浏览"的接口，
注解写着可选登录，方法体里却调了 getUserId()，游客一律 401 —— 分享链接落地页对没登录的人整个打不开。
注解与实现不一致编译器不会报，只能靠这条检查。

口径：方法上 @AppAuth(required = false)，或类上 @AppAuth(required = false) 且方法没有另标 @AppAuth。
未登录可选时应当用 AppUserContext.currentUserId()（未登录返回 null）。
"""
import glob
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, '..', 'ruoyi-kangaroo', 'src', 'main', 'java', 'com', 'ruoyi', 'kangaroo', 'controller', 'app')
THROWING = ('AppUserContext.getUserId()', 'AppUserContext.require()', 'AppUserContext.requireStaff()')


def methods(text):
    for m in re.finditer(r'((?:@\w+(?:\([^)]*\))?\s*)+)public AjaxResult (\w+)\(', text):
        i = text.index('{', m.end())
        depth, j = 0, i
        while True:
            if text[j] == '{':
                depth += 1
            elif text[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        yield m.group(1), m.group(2), text[i:j]


def main():
    bad = []
    for f in sorted(glob.glob(os.path.join(APP, '*.java'))):
        text = io.open(f, encoding='utf-8').read()
        cls_optional = re.search(r'@AppAuth\(required\s*=\s*false\)\s*(?:@\w+(?:\([^)]*\))?\s*)*public class', text) is not None
        # 类里的私有 helper（如 me() / uid()）若调用了会抛的方法，也要算进去
        throwing_helpers = set()
        for hm in re.finditer(r'private (?:static )?\w+ (\w+)\(\)\s*\{([^}]*)\}', text):
            if any(t in hm.group(2) for t in THROWING):
                throwing_helpers.add(hm.group(1))
        for ann, name, body in methods(text):
            optional = 'required = false' in ann or (cls_optional and '@AppAuth' not in ann)
            if not optional:
                continue
            hits = [t for t in THROWING if t in body] + [h + '()' for h in throwing_helpers if re.search(r'\b' + h + r'\(\)', body)]
            if hits:
                bad.append('%s#%s 标了登录可选，却调用了 %s' % (os.path.basename(f), name, ', '.join(hits)))
    for b in bad:
        print('[不一致] ' + b)
    print('检查了 %s；不一致 %d 处' % (os.path.normpath(APP), len(bad)))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
