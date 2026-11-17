# -*- coding: utf-8 -*-
"""从后端源码里读出「通用增删改查」（ExtEntity + 后台 ExtCrud.vue）实际会校验、会渲染的权限标识。

为什么需要它：V7 起的后台页面不再逐页手写按钮，而是由 ExtEntity 登记驱动 ——
后端 KgExtAdminController 用 @extEntities.perm(#entity, 'list'|'query'|'add'|'edit'|'remove') 动态拼权限，
前端 ExtCrud.vue 用 v-hasPermi="[perm('add')]" / v-hasPermi="[a.perm]" 动态挂按钮。
只按字面量扫 hasPermi('kangaroo:x:y') 与 .vue 里的 'kangaroo:x:y' 的检查（R10、C20）对这一整类页面是瞎的：
它们会把 130 多个真实在用的菜单权限报成"死权限"，把 19 个真有按钮的动作报成"点不到"——
而真正的缺陷（例如 V12 删掉了 V8 的投保申请菜单）反倒淹没在误报里。

口径（与运行时一致）：
  · 实体权限：list、query 总是校验；add / edit / remove 取决于 readOnly() / noAdd() / noDelete()，以及单独一句 <变量>.allowAdd|allowEdit|allowDelete = false；
  · 行动作：.action(key, label, method, url, perm, …) 的 perm；
  · 导出：后端拒绝式实体白名单与前端实体条件分别读取；未知语法失败，不互相补齐；
  · 前端"有按钮"：views/kangaroo/ext/<key>/index.vue 存在且写着 entity="<key>"（ExtCrud 才会渲染它的按钮）。
"""
import glob
import io
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
BE_ROOT = os.path.dirname(HERE)
ROOT = os.path.dirname(BE_ROOT)
EXT_SRC = os.path.join(BE_ROOT, 'ruoyi-kangaroo', 'src', 'main', 'java', 'com', 'ruoyi', 'kangaroo', 'ext')
EXT_VIEWS = os.path.join(ROOT, 'RuoYi-Vue3', 'src', 'views', 'kangaroo', 'ext')

_STR = r'"((?:[^"\\]|\\.)*)"'


def _statement(text, start):
    """从 start 起取到本条 Java 语句结束的分号（跳过字符串与括号内的分号）。"""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == '\\' else 1
        elif c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c == ';' and depth <= 0:
            return text[start:i]
        i += 1
    return text[start:]


def _top_level(chain):
    """把括号里的内容全部去掉，只留最外层的调用名：.field(ExtField.of(..).readOnly()).noAdd() → .field().noAdd()"""
    out = []
    depth = 0
    i = 0
    n = len(chain)
    while i < n:
        c = chain[i]
        if c == '"':
            j = i + 1
            while j < n and chain[j] != '"':
                j += 2 if chain[j] == '\\' else 1
            if depth == 0:
                out.append(chain[i:j + 1])
            i = j + 1
            continue
        if c == '(':
            if depth == 0:
                out.append('(')
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                out.append(')')
        elif depth == 0:
            out.append(c)
        i += 1
    return re.sub(r'\s+', '', ''.join(out))



def _without_java_comments(text):
    """Mask comments while retaining string literals and source positions."""
    out = list(text)
    i, quote = 0, None
    while i < len(text):
        c = text[i]
        if quote:
            if c == '\\':
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in ('"', "'"):
            quote = c
        elif text.startswith('//', i) or text.startswith('/*', i):
            if text.startswith('//', i):
                end = text.find('\n', i)
                end = len(text) if end < 0 else end
            else:
                end = text.find('*/', i + 2)
                if end < 0:
                    raise ValueError('Unclosed Java comment in export controller')
                end += 2
            for p in range(i, end):
                if text[p] not in '\r\n':
                    out[p] = ' '
            i = end
            continue
        i += 1
    return ''.join(out)


def _read_source(path):
    with io.open(path, encoding='utf-8') as source:
        return source.read()


def _java_body(text, start):
    """Read one method body; braces in strings/comments cannot end the scope."""
    depth, i, quote = 1, start + 1, None
    while i < len(text):
        c = text[i]
        if quote:
            if c == '\\':
                i += 2
                continue
            if c == quote:
                quote = None
        elif text.startswith('//', i):
            i = text.find('\n', i)
            if i < 0:
                break
            continue
        elif text.startswith('/*', i):
            end = text.find('*/', i + 2)
            if end < 0:
                raise ValueError('Unclosed Java comment in export controller')
            i = end + 2
            continue
        elif c in ('"', "'"):
            quote = c
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if not depth:
                return text[start + 1:i]
        i += 1
    raise ValueError('Unclosed export controller method')


def _entity_literals(value, language):
    # Only literal key lists are understood. New dynamic syntax must fail closed,
    # not silently report that the action has no permission.
    import ast
    import json
    try:
        values = json.loads('[' + value + ']') if language == 'java' else ast.literal_eval('[' + value + ']')
    except Exception as exc:
        raise ValueError('Unrecognized export entity allowlist') from exc
    if (not isinstance(values, list) or not values
            or any(not isinstance(key, str) or not re.fullmatch('[a-z][a-z0-9]*', key) for key in values)
            or len(values) != len(set(values))):
        raise ValueError('Export allowlist must contain unique literal entity keys')
    return set(values)


def backend_export_entities(text):
    text = _without_java_comments(text)
    mappings = list(re.finditer(r'@PostMapping\(\s*"/\{entity\}/export"\s*\)', text))
    if not mappings:
        return set()
    if len(mappings) != 1:
        raise ValueError('Ambiguous dynamic export mapping')
    mapping = mappings[0]
    decorators = text[text.rfind('}', 0, mapping.start()) + 1:mapping.start()]
    expected = r'@PreAuthorize\(\s*"@ss\.hasPermi\(@extEntities\.perm\(#entity,\s*\x27export\x27\)\)"\s*\)'
    if not re.search(expected, decorators):
        raise ValueError('Dynamic export route lacks the expected export PreAuthorize guard')
    opening = text.find('{', mapping.end())
    if opening < 0 or not re.search(r'public\s+void\s+export\s*\(', text[mapping.end():opening]):
        raise ValueError('Unrecognized dynamic export method declaration')
    body = _java_body(text, opening)
    guards = list(re.finditer(r'if\s*\(\s*!\s*Arrays\.asList\(([^)]*)\)\.contains\(\s*entity\s*\)\s*\)\s*throw\s+new\s+ServiceException\b', body))
    if len(guards) != 1 or body[:guards[0].start()].strip():
        raise ValueError('Dynamic export must begin with one explicit rejecting entity allowlist')
    return _entity_literals(guards[0].group(1), 'java')


def frontend_export_entities(text):
    text = re.sub(r'<!--.*?-->', '', text, flags=re.S)
    permission = r'v-hasPermi\s*=\s*"\s*\[\s*perm\(\s*\x27export\x27\s*\)\s*\]\s*"'
    total = len(re.findall(permission, text))
    if not total:
        return set()
    matched, keys = 0, set()
    for col in re.finditer(r'<el-col\b([^>]*)>(.*?)</el-col>', text, re.S):
        buttons = re.findall(r'<el-button\b[^>]*>', col.group(2))
        exported = [button for button in buttons if re.search(permission, button)]
        if not exported:
            continue
        if any(not re.search(r'@click\s*=\s*"handleExport"', button) for button in exported):
            raise ValueError('Export permission is not attached to a working export button')
        condition = re.search(r'\bv-if\s*=\s*"([^"]*)"', col.group(1))
        allow = re.fullmatch(r'\s*\[(.*?)\]\.includes\(\s*(?:props\.)?entity\s*\)\s*', condition.group(1) if condition else '')
        if not allow:
            raise ValueError('Unrecognized entity condition on export button')
        keys |= _entity_literals(allow.group(1), 'javascript')
        matched += len(exported)
    if matched != total:
        raise ValueError('An export permission button could not be scoped to its entity condition')
    return keys


def entities():
    """返回 {key: {'perm': 前缀, 'backend': set(后端会校验的标识), 'buttons': set(ExtCrud 会渲染成按钮的标识), 'file': 源文件}}"""
    out = {}
    head = re.compile(r'(?:(\w+)\s*=\s*)?new\s+ExtEntity\(\s*' + r'\s*,\s*'.join([_STR] * 5) + r'\s*\)')
    for f in glob.glob(os.path.join(EXT_SRC, '**', '*.java'), recursive=True):
        text = _read_source(f)
        for m in head.finditer(text):
            var, key, perm = m.group(1), m.group(2), m.group(6)
            chain = _statement(text, m.start())
            # 只看实体这一层的链式调用：ExtField.of(...).readOnly() 是"某个字段只读"，不是整个实体只读
            top = _top_level(chain[len(m.group(0)):])
            # 也有写成单独一句的：entry.allowEdit = false;（钱包流水就是这样关掉修改的）
            off = set(re.findall(r'\b%s\.allow(Add|Edit|Delete)\s*=\s*false\s*;' % re.escape(var), text)) if var else set()
            ro = '.readOnly()' in top
            add = not ro and '.noAdd()' not in top and 'Add' not in off
            edit = not ro and 'Edit' not in off
            remove = not ro and '.noDelete()' not in top and 'Delete' not in off
            backend = {perm + ':list', perm + ':query'}
            buttons = {perm + ':query'}
            for flag, act in ((add, 'add'), (edit, 'edit'), (remove, 'remove')):
                if flag:
                    backend.add(perm + ':' + act)
                    buttons.add(perm + ':' + act)
            for a in re.finditer(r'\.action\(\s*' + r'\s*,\s*'.join([_STR] * 5), chain):
                backend.add(a.group(5))
                buttons.add(a.group(5))
            out[key] = {'perm': perm, 'backend': backend, 'buttons': buttons,
                        'file': os.path.relpath(f, ROOT).replace(os.sep, '/')}
    controller = os.path.join(BE_ROOT, 'ruoyi-kangaroo', 'src', 'main', 'java', 'com', 'ruoyi', 'kangaroo', 'controller', 'admin', 'KgExtAdminController.java')
    crud = os.path.join(EXT_VIEWS, 'ExtCrud.vue')
    for keys, field in ((backend_export_entities(_read_source(controller)), 'backend'),
                        (frontend_export_entities(_read_source(crud)), 'buttons')):
        for key in keys:
            if key not in out:
                raise ValueError('Export references an unregistered ExtEntity: ' + key)
            out[key][field].add(out[key]['perm'] + ':export')
    return out


def page_exists(key):
    """后台有没有这个实体的页面组件（菜单 component = kangaroo/ext/<key>/index）。"""
    p = os.path.join(EXT_VIEWS, key, 'index.vue')
    if not os.path.exists(p):
        return False
    return ('entity="%s"' % key) in _read_source(p)


def backend_perms():
    s = set()
    for e in entities().values():
        s |= e['backend']
    return s


def button_perms():
    """有页面组件的实体，其按钮上挂的全部标识 → 所在页面"""
    out = {}
    for key, e in entities().items():
        if page_exists(key):
            for p in e['buttons']:
                out.setdefault(p, 'RuoYi-Vue3/src/views/kangaroo/ext/%s/index.vue（ExtCrud）' % key)
    return out


if __name__ == '__main__':
    es = entities()
    print('ExtEntity %d 个；后端校验 %d 个标识；有页面的按钮标识 %d 个'
          % (len(es), len(backend_perms()), len(button_perms())))
    for k in sorted(es):
        if not page_exists(k):
            print('  没有页面组件：', k, es[k]['file'])
