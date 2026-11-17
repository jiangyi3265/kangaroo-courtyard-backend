# -*- coding: utf-8 -*-
"""管理后台修复项的回归测试（本轮新增）。

覆盖四件事，全部走真实 HTTP 再回数据库核对，不拿接口返回码当结论：

  T1  岗位：把可选字段（可开始日期 / 最短停留 / 薪资区间）清空，数据库里必须真的变成 NULL。
      修之前 MyBatis 动态 SET 的 <if test="x != null"> 会跳过 null 列，
      页面弹「修改成功」而值原封不动。

  T2  面试时段：把「限定岗位」清空，job_id 必须真的变成 NULL。同上。

  T3  回归底线：对同一个岗位点一次上架 / 暂停（PUT /kangaroo/job/changeStatus），
      title / start_date / min_days / salary_min / salary_max 必须保持不变，
      只有 status 与时间戳允许变。
      「后台整表单编辑」与「内部局部更新」拆成两条 SQL 之后，这是必须守住的底线：
      changeStatus 若误用全量覆盖语句，一次状态切换就会把这些列清成 NULL。

  T4  导出 Excel：枚举列必须是中文，不能是 online / piece / published 这类原始码。

用法：
    python e2e/test_admin_fixes.py         # 需要后端已在 8080 运行
"""
import io
import os
import sys
import time
import urllib.request
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import (  # noqa: E402
    BASE, http, sql, scalar, sql_exec, check, section, admin_login, PASSED, FAILED)


def norm(v):
    """mysql -N 输出里 NULL 就是字面量 NULL；统一成 Python 的 None 便于比对。"""
    return None if v is None or v == 'NULL' else v


def post_binary(path, token):
    """RuoYi 的导出是 form 表单 POST + 二进制响应，不能走 JSON 的 http()。"""
    req = urllib.request.Request(
        BASE + path, data=b'',
        headers={'Content-Type': 'application/x-www-form-urlencoded',
                 'Authorization': 'Bearer ' + token},
        method='POST')
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status, r.read()


# 前缀必须是 '回归-'：e2e/reset-to-demo.sql 的演示基线白名单按这个前缀剔除测试数据，
# 不守约定的话测试造的岗位会一直沉淀在库里。
JOB_TITLE = '回归-后台可选字段清空'
# 每轮唯一：V4 给同门店同区间加了唯一索引，固定时间点一旦有残留就再也建不出来。
SLOT_START = time.strftime('2028-%m-%d %H:%M:00', time.localtime())


def main():
    admin_token = admin_login()

    # ------------------------------------------------------------ 准备数据
    section('T0 准备：新建一个可选字段全部填满的岗位')
    store_id = scalar("select store_id from kg_store where del_flag='0' order by store_id limit 1")
    assert store_id, '库里没有可用门店，先跑 seed_demo_data.py'

    city = scalar('select city from kg_store where store_id=%s' % store_id)
    payload = {
        'storeId': int(store_id),
        'title': JOB_TITLE,
        'jobType': 'parttime',
        'city': city,
        'salaryType': 'daily',
        'headcount': 2,
        'startDate': '2026-10-01',
        'minDays': 7,
        'salaryMin': 120,
        'salaryMax': 200,
        'status': 'draft',
        'remoteFlag': '1',
        'insuranceFlag': '1',
    }
    status, body = http('POST', '/kangaroo/job', payload, token=admin_token)
    check('T0 建岗位成功', 200, body.get('code'))
    job_id = scalar("select job_id from kg_job where title=%s order by job_id desc limit 1"
                    % repr(JOB_TITLE))
    assert job_id, '岗位没建出来，后续用例无意义'

    row = sql('select start_date, min_days, salary_min, salary_max from kg_job where job_id=%s'
              % job_id)[0]
    filled = [norm(x) for x in row]
    check('T0 四个可选字段确实写进去了', True, filled,
          ok=all(x is not None for x in filled))

    # ------------------------------------------------------------ T1
    section('T1 岗位可选字段清空后必须落库为 NULL')
    status, body = http('GET', '/kangaroo/job/%s' % job_id, token=admin_token)
    check('T1 详情可读', 200, body.get('code'))
    form = body.get('data') or {}
    # 模拟后台表单的真实行为：整对象回填后把四个可选字段清空再整体提交
    for f in ('startDate', 'minDays', 'salaryMin', 'salaryMax'):
        form[f] = None
    # 薪资区间要能留空，计薪方式必须同时改成「无薪资」，否则会被必填校验挡下
    form['salaryType'] = 'none'
    status, body = http('PUT', '/kangaroo/job', form, token=admin_token)
    check('T1 提交清空返回成功', 200, body.get('code'))

    row = sql('select start_date, min_days, salary_min, salary_max from kg_job where job_id=%s'
              % job_id)[0]
    for name, val in zip(('start_date', 'min_days', 'salary_min', 'salary_max'), row):
        check('T1 %s 已清空为 NULL' % name, None, norm(val))

    # ------------------------------------------------------------ T3
    section('T3 上架 / 暂停不得连带改动其它列')
    # 先把字段填回去，否则「被清空」这件事观察不到
    status, body = http('GET', '/kangaroo/job/%s' % job_id, token=admin_token)
    form = body.get('data') or {}
    form.update({'startDate': '2026-11-11', 'minDays': 9, 'salaryMin': 150,
                 'salaryMax': 260, 'salaryType': 'daily'})
    status, body = http('PUT', '/kangaroo/job', form, token=admin_token)
    check('T3 前置：字段填回成功', 200, body.get('code'))

    cols = 'title, date(start_date), min_days, salary_min, salary_max, status'
    names = ('title', 'start_date', 'min_days', 'salary_min', 'salary_max')
    before = sql('select %s from kg_job where job_id=%s' % (cols, job_id))[0]
    check('T3 前置：字段确已填回', True, [norm(x) for x in before[:5]],
          ok=all(norm(x) is not None for x in before[:5]))

    status, body = http('PUT', '/kangaroo/job/changeStatus',
                        {'jobId': int(job_id), 'status': 'published'}, token=admin_token)
    check('T3 上架成功', 200, body.get('code'))
    after = sql('select %s from kg_job where job_id=%s' % (cols, job_id))[0]
    for i, name in enumerate(names):
        check('T3 上架后 %s 未被改动' % name, norm(before[i]), norm(after[i]))
    check('T3 上架后 status 确实变了', 'published', norm(after[5]))

    status, body = http('PUT', '/kangaroo/job/changeStatus',
                        {'jobId': int(job_id), 'status': 'paused'}, token=admin_token)
    check('T3 暂停成功', 200, body.get('code'))
    after2 = sql('select %s from kg_job where job_id=%s' % (cols, job_id))[0]
    for i, name in enumerate(names):
        check('T3 暂停后 %s 未被改动' % name, norm(before[i]), norm(after2[i]))
    check('T3 暂停后 status 确实变了', 'paused', norm(after2[5]))

    # ------------------------------------------------------------ T2
    section('T2 面试时段「限定岗位」清空后必须落库为 NULL')
    slot_payload = {
        'storeId': int(store_id),
        'jobId': int(job_id),
        'startTime': SLOT_START,
        'endTime': SLOT_START.replace(':00', ':30', 1),
        'capacity': 1,
        'status': 'open',
    }
    status, body = http('POST', '/kangaroo/slot', slot_payload, token=admin_token)
    check('T2 建时段成功', 200, body.get('code'))
    slot_id = scalar('select slot_id from kg_interview_slot where store_id=%s '
                     'and start_time=%s order by slot_id desc limit 1'
                     % (store_id, repr(SLOT_START)))
    if not slot_id:
        # 建出来了却查不到，最常见的原因是时间被时区平移了，所以把实际落库值打出来
        rows = sql('select slot_id, start_time from kg_interview_slot '
                   'where store_id=%s order by slot_id desc limit 3' % store_id)
        raise AssertionError('时段没按 %s 落库，最近三条为 %s' % (SLOT_START, rows))
    check('T2 提交什么时间就存什么时间（不被时区平移）', SLOT_START,
          scalar('select start_time from kg_interview_slot where slot_id=%s' % slot_id))
    check('T2 前置：job_id 已写入', str(job_id),
          norm(scalar('select job_id from kg_interview_slot where slot_id=%s' % slot_id)))

    status, body = http('GET', '/kangaroo/slot/%s' % slot_id, token=admin_token)
    form = body.get('data') or {}
    form['jobId'] = None
    status, body = http('PUT', '/kangaroo/slot', form, token=admin_token)
    check('T2 提交清空返回成功', 200, body.get('code'))
    check('T2 job_id 已清空为 NULL', None,
          norm(scalar('select job_id from kg_interview_slot where slot_id=%s' % slot_id)))

    # ------------------------------------------------------------ T4
    section('T4 导出 Excel 的枚举列必须是中文')
    status, blob = post_binary('/kangaroo/job/export', admin_token)
    check('T4 导出接口返回 200', 200, status)
    check('T4 导出的是 xlsx（zip magic）', b'PK', blob[:2])

    text = ''
    try:
        zf = zipfile.ZipFile(io.BytesIO(blob))
        text = b''.join(zf.read(n) for n in zf.namelist()
                        if n.endswith('.xml')).decode('utf-8', 'replace')
    except Exception as e:                                  # noqa: BLE001
        print('  [注意] 导出内容不是可解析的 xlsx：%s' % e)

    # 这些是 @Excel(readConverterExp) 应当翻译掉的原始码
    raw_codes = ('volunteer', 'parttime', 'fulltime', 'online',
                 'daily', 'monthly', 'piece', 'none',
                 'draft', 'published', 'paused', 'closed')
    found_raw = [c for c in raw_codes if '>%s<' % c in text]
    cn_words = ('义工换宿', '兼职旅行', '全职旅行', '在线兼职',
                '日薪', '月薪', '计件', '无薪资',
                '草稿', '招聘中', '暂停', '已关闭')
    found_cn = [w for w in cn_words if w in text]
    check('T4 导出里没有英文枚举原始码', [], found_raw)
    check('T4 导出里出现了中文枚举', True, found_cn, ok=bool(found_cn))

    # ------------------------------------------------------------ 清理
    # ------------------------------------------------------------ T5
    section('T5 被失效占用卡住的时段，运营必须能处理')
    # 时间点必须每轮不同：V4 给同门店同区间加了唯一索引，
    # 而这条用例的前一半就是在制造"删不掉的时段"，一旦某轮失败、残留没清掉，
    # 下一轮用同一个时间点连建都建不出来（第一次跑就撞上了）。
    t5_start = time.strftime('2027-%m-%d %H:%M:00', time.localtime())
    status, body = http('POST', '/kangaroo/slot',
                        {'storeId': int(store_id), 'startTime': t5_start,
                         'endTime': t5_start.replace(':00', ':30', 1), 'status': 'open'},
                        token=admin_token)
    check('T5 建一个时段', 200, body.get('code'))
    t5_slot = scalar('select slot_id from kg_interview_slot where store_id=%s '
                     'and start_time=%s order by slot_id desc limit 1'
                     % (store_id, repr(t5_start)))
    assert t5_slot, '时段没建出来'

    # 找一条已经到终态的报名，把它做成这个时段的占用者。
    # 这正是"释放没成功"留下的残局：状态是 booked，但占用者早就走完流程了。
    # 必须挑一条**还没占着别的时段**的终态报名：
    # kg_interview_slot.booked_app_id 上有唯一索引，一条报名只能占一个时段，
    # 拿已经占着别处的那条来构造残局会直接撞 1062。
    dead_app = scalar("select a.app_id from kg_application a "
                      "where a.status in ('terminated','withdrawn','review_rejected','onboarded') "
                      "  and not exists (select 1 from kg_interview_slot sl "
                      "                   where sl.booked_app_id = a.app_id) "
                      "order by a.app_id desc limit 1")
    if not dead_app:
        print('  [跳过] 库里没有终态报名，无法构造这个残局')
    else:
        sql_exec("update kg_interview_slot set status='booked', booked_app_id=%s "
                 "where slot_id=%s" % (dead_app, t5_slot))
        check('T5 前置：时段已处于"被终态报名占用"的残局', 'booked',
              scalar('select status from kg_interview_slot where slot_id=%s' % t5_slot))

        status, body = http('DELETE', '/kangaroo/slot/%s' % t5_slot, token=admin_token)
        check('T5 运营可以删掉被失效占用卡住的时段', 200, body.get('code'))
        check('T5 删除确实生效（软删标记已置位）', '0',
              scalar("select count(*) from kg_interview_slot "
                     "where slot_id=%s and del_flag='0'" % t5_slot))

    # 反面：占用仍然有效时必须照旧拒绝，不能因为放宽守卫把这层保护也丢了
    live_app = scalar("select a.app_id from kg_application a "
                      "join kg_interview_slot sl on sl.booked_app_id = a.app_id "
                      "where sl.status='booked' and sl.del_flag='0' "
                      "  and a.status not in ('terminated','withdrawn','review_rejected','onboarded') "
                      "limit 1")
    if not live_app:
        print('  [跳过] 当前没有"活跃报名正占用着的时段"，这一面无法验证')
    else:
        live_slot = scalar('select slot_id from kg_interview_slot '
                           'where booked_app_id=%s limit 1' % live_app)
        status, body = http('DELETE', '/kangaroo/slot/%s' % live_slot, token=admin_token)
        check('T5 占用仍然有效时，删除依然被拒绝', 500, body.get('code'))
        check('T5 被拒的删除没有生效', '0',
              scalar("select del_flag from kg_interview_slot where slot_id=%s" % live_slot))

    # ------------------------------------------------------------ T6
    section('T6 编辑被失效占用卡住的时段（forceReleaseSlot 的唯一调用路径）')
    t6_start = time.strftime('2029-%m-%d %H:%M:00', time.localtime())
    status, body = http('POST', '/kangaroo/slot',
                        {'storeId': int(store_id), 'startTime': t6_start,
                         'endTime': t6_start.replace(':00', ':30', 1), 'status': 'open'},
                        token=admin_token)
    check('T6 建一个时段', 200, body.get('code'))
    t6_slot = scalar('select slot_id from kg_interview_slot where store_id=%s '
                     'and start_time=%s order by slot_id desc limit 1'
                     % (store_id, repr(t6_start)))
    assert t6_slot, '时段没建出来'

    dead2 = scalar("select a.app_id from kg_application a "
                   "where a.status in ('terminated','withdrawn','review_rejected','onboarded') "
                   "  and not exists (select 1 from kg_interview_slot sl "
                   "                   where sl.booked_app_id = a.app_id) "
                   "order by a.app_id desc limit 1")
    if not dead2:
        print('  [跳过] 库里没有空闲的终态报名，无法构造这个残局')
    else:
        sql_exec("update kg_interview_slot set status='booked', booked_app_id=%s "
                 "where slot_id=%s" % (dead2, t6_slot))
        check('T6 前置：时段处于「被终态报名占用」的残局', 'booked',
              scalar('select status from kg_interview_slot where slot_id=%s' % t6_slot))

        # 取回整对象再提交，完全模拟后台表单的行为 ——
        # 关键在于回填进来的 status 就是 'booked'，以前这一定会被当成"手工设置占用状态"而拒绝
        status, body = http('GET', '/kangaroo/slot/%s' % t6_slot, token=admin_token)
        form = body.get('data') or {}
        check('T6 前置：表单回填的 status 确实是 booked', 'booked', form.get('status'))
        form['remark'] = 'T6 编辑备注'
        status, body = http('PUT', '/kangaroo/slot', form, token=admin_token)
        check('T6 原样回传 booked 的表单不再被拒', 200, body.get('code'))
        check('T6 编辑确实落库了', 'T6 编辑备注',
              scalar('select remark from kg_interview_slot where slot_id=%s' % t6_slot))
        check('T6 时段被放回可约池', 'open',
              scalar('select status from kg_interview_slot where slot_id=%s' % t6_slot))
        check('T6 占用标记被清空', None,
              norm(scalar('select booked_app_id from kg_interview_slot where slot_id=%s'
                          % t6_slot)))

        # 保存失败时不能把时段释放掉：故意提交一个过去的开始时间，让校验抛错
        sql_exec("update kg_interview_slot set status='booked', booked_app_id=%s "
                 "where slot_id=%s" % (dead2, t6_slot))
        status, body = http('GET', '/kangaroo/slot/%s' % t6_slot, token=admin_token)
        form = body.get('data') or {}
        form['startTime'] = '2020-01-01 10:00:00'
        form['endTime'] = '2020-01-01 10:30:00'
        status, body = http('PUT', '/kangaroo/slot', form, token=admin_token)
        check('T6 改到过去的时间被拒绝', 500, body.get('code'))
        check('T6 保存失败时时段没有被释放（释放排在校验之后且有事务）', 'booked',
              scalar('select status from kg_interview_slot where slot_id=%s' % t6_slot))
        check('T6 保存失败时占用标记也没有被清掉', str(dead2),
              norm(scalar('select booked_app_id from kg_interview_slot where slot_id=%s'
                          % t6_slot)))

        # 收尾：把它释放并删掉
        sql_exec("update kg_interview_slot set status='open', booked_app_id=null "
                 "where slot_id=%s" % t6_slot)
        http('DELETE', '/kangaroo/slot/%s' % t6_slot, token=admin_token)

    section('清理本用例造的数据')
    http('DELETE', '/kangaroo/slot/%s' % slot_id, token=admin_token)
    # 时段删除是软删（写 del_flag），行还在表里，所以要按 del_flag 判断
    check('时段已删除（软删标记已置位）', '0',
          scalar("select count(*) from kg_interview_slot "
                 "where slot_id=%s and del_flag='0'" % slot_id))
    # 岗位此刻是 paused：按业务规则非草稿岗位只能关闭、不能删除，
    # 这是正确行为，所以这里走关闭而不是删除。
    status, body = http('PUT', '/kangaroo/job/changeStatus',
                        {'jobId': int(job_id), 'status': 'closed'}, token=admin_token)
    check('测试岗位已关闭', 200, body.get('code'))
    check('测试岗位不再处于招聘中', 'closed',
          scalar('select status from kg_job where job_id=%s' % job_id))
    left = scalar("select count(*) from kg_job where title=%s and del_flag='0' "
                  "and status <> 'closed'" % repr(JOB_TITLE))
    check('没有残留的未关闭测试岗位', '0', left)

    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
