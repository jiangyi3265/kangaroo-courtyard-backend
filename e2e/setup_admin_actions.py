# -*- coding: utf-8 -*-
"""给「后台报名动作」浏览器用例准备数据。

后台报名页的审核 / 确认录用 / 标记入职 / 终止四个按钮，都要求报名处于特定状态，
而这些状态只能由小程序端一步步推出来（预约面试、结束面试、表态、签约都是用户本人的动作）。
在浏览器里把两端都点一遍既慢又脆，所以分工：
  · 这个脚本用真实 HTTP 把报名推到各自需要的状态（小程序端的动作）；
  · check-admin-actions.mjs 只负责在后台浏览器里点那几个按钮、并回服务端核对结果。
两边各自证明各自那一层，互不冒充。

输出：最后一行是 `SETUP=<json>`，由 verify.py 转交给浏览器用例。
"""
import json
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import sign_contract, http, scalar, sql_exec, admin_login  # noqa: E402

RUN = uuid.uuid4().hex[:6]


def app_login(tag):
    st, body = http('POST', '/app/auth/wx-login', {'code': 'act-%s-%s' % (RUN, tag)})
    assert body.get('code') == 200, body
    return body['token'], body['account']['id']


def must(query):
    """必须查到一行的查询。查不到就把查询本身带出来，而不是在下游某个 int(None) 上炸。"""
    v = scalar(query)
    if v is None or v == 'NULL':
        raise RuntimeError('应当有结果却查不到：' + query)
    return v


def ts(offset_hours):
    return time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(time.time() + offset_hours * 3600))


def main():
    admin = admin_login()
    store_id = must("select store_id from kg_store where del_flag='0' and status='0' "
                      "order by store_id limit 1")
    city = scalar('select city from kg_store where store_id=%s' % store_id)
    store_name = scalar('select store_name from kg_store where store_id=%s' % store_id)

    title = '回归-后台动作-' + RUN
    st, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': title, 'jobType': 'parttime', 'city': city,
        'salaryType': 'none', 'headcount': 10}, token=admin)
    assert body.get('code') == 200, body
    job_id = must("select job_id from kg_job where title=%s" % repr(title))
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': int(job_id), 'status': 'published'},
         token=admin)
    arrival = time.strftime('%Y-%m-%d', time.localtime(time.time() + 7 * 86400))


    # 「重算名额」要验"真的按报名明细重算了"：另建一个岗位，把计数列故意写歪
    drift_title = '回归-计数漂移-' + RUN
    st, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': drift_title, 'jobType': 'parttime', 'city': city,
        'salaryType': 'none', 'headcount': 5}, token=admin)
    assert body.get('code') == 200, body
    drift_id = must("select job_id from kg_job where title=%s" % repr(drift_title))
    sql_exec('update kg_job set applied_count=999, hired_count=77 where job_id=%s' % drift_id)

    # 时段从这家门店现有最晚的那个时段之后开始排。
    # 按"当前时间 + 固定偏移"排的话，连跑两次（中间没重置数据）就会和上一次留下的时段重叠，
    # 被"同门店不能重叠"正确地拒掉 —— 第二次跑就是这么红的。
    latest = scalar("select date_format(max(end_time), '%%Y-%%m-%%d %%H:%%i:%%s') "
                    "from kg_interview_slot where store_id=%s and del_flag='0'" % store_id)
    floor_ts = time.time() + 96 * 3600
    if latest and latest != 'NULL':
        latest_ts = time.mktime(time.strptime(latest, '%Y-%m-%d %H:%M:%S'))
        floor_ts = max(floor_ts, latest_ts + 3600)
    slot_seq = [0]

    def new_slot():
        slot_seq[0] += 1
        start = floor_ts + slot_seq[0] * 2 * 3600
        s_txt = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(start))
        e_txt = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(start + 1800))
        st_, b_ = http('POST', '/kangaroo/slot', {
            'storeId': int(store_id), 'startTime': s_txt, 'endTime': e_txt}, token=admin)
        assert b_.get('code') == 200, (b_, 'latest=%r floor=%s start=%s' % (
            latest, time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(floor_ts)), s_txt))
        return must("select slot_id from kg_interview_slot where store_id=%s and del_flag='0' "
                    "and start_time=%s" % (store_id, repr(s_txt)))

    def submit(tag):
        tok, uid = app_login(tag)
        st_, b_ = http('POST', '/app/application', {
            'jobId': int(job_id), 'name': '后台动作-' + tag, 'age': 25,
            'phone': '1370013%04d' % (hash(tag + RUN) % 10000), 'arrival': arrival,
            'days': 10, 'idemKey': 'act-%s-%s' % (RUN, tag)}, app_token=tok)
        assert b_.get('code') == 200, b_
        app_id = must('select app_id from kg_application where app_user_id=%s and job_id=%s '
                        'order by app_id desc limit 1' % (uid, job_id))
        app_no = must('select app_no from kg_application where app_id=%s' % app_id)
        return tok, int(app_id), app_no

    def to_booked(tag):
        tok, app_id, app_no = submit(tag)
        http('PUT', '/kangaroo/application/audit', {'appId': app_id, 'pass': True}, token=admin)
        slot = new_slot()
        st_, b_ = http('POST', '/app/application/%s/book-interview' % app_id,
                       {'slotId': int(slot)}, app_token=tok)
        assert b_.get('code') == 200, b_
        return tok, app_id, app_no, int(slot)

    out = {'run': RUN, 'jobId': int(job_id), 'jobTitle': title,
           'storeId': int(store_id), 'storeName': store_name,
           'drift': {'jobId': int(drift_id), 'title': drift_title}}

    # 1. 两条"已提交"：给后台的 通过 / 驳回
    for key in ('pass', 'reject'):
        _, app_id, app_no = submit(key)
        out[key] = {'appId': app_id, 'appNo': app_no}

    # 1b. 一条"已预约面试"：给后台的 终止。
    #     已提交的报名在后台只有 通过 / 驳回 两个动作（状态机如此），没有终止；
    #     第一次跑就是找不到「终止」按钮红的。推到已预约面试，还能顺带验证终止会释放时段。
    _, app_id, app_no, slot = to_booked('terminate')
    out['terminate'] = {'appId': app_id, 'appNo': app_no, 'slotId': slot}

    # 2. 推到"用户已确认"：给后台的 确认录用
    tok, app_id, app_no, slot = to_booked('confirm')
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(app_id)
    http('POST', '/app/application/%s/finish-interview' % app_id, {'durationSeconds': 600},
         app_token=tok)
    http('POST', '/app/application/%s/confirm' % app_id, {'intent': 'accept'}, app_token=tok)
    assert scalar('select status from kg_application where app_id=%s' % app_id) == 'user_confirmed'
    out['confirm'] = {'appId': app_id, 'appNo': app_no}

    # 3. 推到"已签约"：给后台的 标记入职（签约这一步只能由用户本人发起）
    tok, app_id, app_no, slot = to_booked('onboard')
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(app_id)
    http('POST', '/app/application/%s/finish-interview' % app_id, {'durationSeconds': 600},
         app_token=tok)
    http('POST', '/app/application/%s/confirm' % app_id, {'intent': 'accept'}, app_token=tok)
    http('PUT', '/kangaroo/application/confirm/%s' % app_id, None, token=admin)
    sign_contract(app_id, tok)
    assert scalar('select status from kg_application where app_id=%s' % app_id) == 'contract_signed'
    out['onboard'] = {'appId': app_id, 'appNo': app_no, 'slotId': slot}

    # 4. 一个被**有效**占用的时段：报名停在"已预约面试"（给 C11：改不了也删不了）
    tok, app_id, app_no, slot = to_booked('active')
    out['activeSlot'] = {'slotId': slot, 'appId': app_id, 'appNo': app_no}

    # 5. 一个被**失效**占用卡住的时段（给 F14）：
    #    正常流程里终止会释放时段，造不出这个残局；这正是 F14 要验的"历史脏数据能被清掉"，
    #    所以用 SQL 把占用写回去 —— 与后端回归 T5/T6 造残局的方式一致。
    tok, app_id, app_no, slot = to_booked('stale')
    http('PUT', '/kangaroo/application/terminate',
         {'appId': app_id, 'reason': '造失效占用'}, token=admin)
    sql_exec("update kg_interview_slot set status='booked', booked_app_id=%s where slot_id=%s"
             % (app_id, slot))
    out['staleSlot'] = {'slotId': slot, 'appId': app_id}

    # 6. 面试已结束、用户还没表态的报名：给 C14（此时后台不能出现「确认录用」）
    tok, app_id, app_no, slot = to_booked('early')
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(app_id)
    http('POST', '/app/application/%s/finish-interview' % app_id, {'durationSeconds': 600},
         app_token=tok)
    assert scalar('select status from kg_application where app_id=%s' % app_id) == 'interview_done'
    out['early'] = {'appId': app_id, 'appNo': app_no}

    # 7. 名额已满：一个只招 1 人的岗位，两个人都到了"用户已确认"，先用接口录用一个占满名额，
    #    留下第二个给后台去点「确认录用」—— 应当被拒、名额不被超扣（C16）
    full_title = '回归-名额已满-' + RUN
    st, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': full_title, 'jobType': 'parttime', 'city': city,
        'salaryType': 'none', 'headcount': 1}, token=admin)
    assert body.get('code') == 200, body
    full_job = scalar("select job_id from kg_job where title=%s" % repr(full_title))
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': int(full_job), 'status': 'published'},
         token=admin)
    full_apps = []
    for tag in ('full1', 'full2'):
        ftok, fuid = app_login(tag)
        st_, b_ = http('POST', '/app/application', {
            'jobId': int(full_job), 'name': '名额-' + tag, 'age': 25,
            'phone': '1370014%04d' % (hash(tag + RUN) % 10000), 'arrival': arrival,
            'days': 10, 'idemKey': 'act-%s-%s' % (RUN, tag)}, app_token=ftok)
        assert b_.get('code') == 200, b_
        fid = int(must('select app_id from kg_application where app_user_id=%s and job_id=%s'
                       % (fuid, full_job)))
        http('PUT', '/kangaroo/application/audit', {'appId': fid, 'pass': True}, token=admin)
        fslot = new_slot()
        ok_ = http('POST', '/app/application/%s/book-interview' % fid, {'slotId': int(fslot)},
                   app_token=ftok)[1]
        assert ok_.get('code') == 200, ok_
        from interview_clock_fixture import advance_interview_fixture
        advance_interview_fixture(fid)
        http('POST', '/app/application/%s/finish-interview' % fid, {'durationSeconds': 600},
             app_token=ftok)
        http('POST', '/app/application/%s/confirm' % fid, {'intent': 'accept'}, app_token=ftok)
        assert scalar('select status from kg_application where app_id=%s' % fid) == 'user_confirmed'
        full_apps.append((fid, must('select app_no from kg_application where app_id=%s' % fid)))
    _, cb = http('PUT', '/kangaroo/application/confirm/%s' % full_apps[0][0], None, token=admin)
    assert cb.get('code') == 200, cb
    out['full'] = {'jobId': int(full_job), 'appId': full_apps[1][0], 'appNo': full_apps[1][1]}

    # 8. 一家专门拿来停用的门店，带一个已发布岗位（B04 / D05 的后台那一半：在门店表单里点「停用」）
    s2_name = '回归停用门店-' + RUN
    _, sb = http('POST', '/kangaroo/store', {'storeName': s2_name, 'city': city,
                                             'address': '回归停用路 2 号'}, token=admin)
    assert sb.get('code') == 200, sb
    s2_id = int(must("select store_id from kg_store where store_name=%s and del_flag='0'"
                     % repr(s2_name)))
    s2_job = '回归-停用门店岗位-' + RUN
    _, jb = http('POST', '/kangaroo/job', {
        'storeId': s2_id, 'title': s2_job, 'jobType': 'parttime', 'city': city,
        'salaryType': 'none', 'headcount': 2}, token=admin)
    assert jb.get('code') == 200, jb
    s2_job_id = int(must("select job_id from kg_job where title=%s" % repr(s2_job)))
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': s2_job_id, 'status': 'published'},
         token=admin)
    out['store2'] = {'storeId': s2_id, 'storeName': s2_name, 'jobTitle': s2_job}

    # B11 要验"把可选字段清空后库里真是空"：先让四个可选字段都有值，否则清空这一步验了个寂寞。
    # 放在所有报名之后写：可开始日期会约束到岗日期，写早了上面那些报名会被正确地拒掉。
    # 取 +3 天，仍早于浏览器用例里 B12 那笔并发报名的到岗日（+9 天）。
    start_date = time.strftime('%Y-%m-%d', time.localtime(time.time() + 3 * 86400))
    sql_exec("update kg_job set min_days=3, start_date='%s', salary_type='daily', "
             "salary_min=150, salary_max=200, salary_unit='元/天' where job_id=%s"
             % (start_date, job_id))

    print('SETUP=' + json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
