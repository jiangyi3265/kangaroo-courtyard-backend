# -*- coding: utf-8 -*-
"""给「小程序员工端与面试链路」浏览器用例（check-staff-journey.mjs）准备数据。

全部用真实 HTTP 造，并且只造在**本次运行自己新建的一家门店**里：
门店停用、授权撤销、账号停用这些动作都会作用在这家门店与这些账号上，
不碰任何演示数据。

造出来的东西：
  门店 1 家（本次新建）
  岗位 3 个：已发布（报名都挂在它下面）/ 草稿 / 已关闭（给 B03 看"对小程序不可见"）
  员工 2 个：A、B，都授予「员工」角色（给 D01/D02/D08 与 C08 的双人抢审）
  应聘者 6 个，各自一条报名：
    pass      已提交 → 员工 A 在小程序里点「通过」，随后本人预约、面试、表态（C07/C09/C12/C13）
    reject    已提交 → 员工 A 点「不合适」，先空原因被拦、再填原因（C07），随后被后台停用（A09）
    race      已提交 → 员工 A、B 同时打开详情，A 先过、B 后点（C08）
    withdraw  已预约面试（占着一个时段）→ 本人在进度页撤回（C06）
    notify    已提交 → 后台通过，本人在小程序看进度与消息（C23 / E08）
    decline   已预约面试（占着一个时段）→ 本人面试后点「不合适」（C13 的第三个选项）
  时段 4 个：两个空闲的给 pass 选，一个被 withdraw 占着，一个被 decline 占着

输出：最后一行 `SETUP=<json>`。令牌只经环境变量交给浏览器用例，不落盘。
"""
import json
import os
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import http, scalar, admin_login  # noqa: E402

RUN = uuid.uuid4().hex[:6]


def must(query):
    v = scalar(query)
    if v is None or v == 'NULL':
        raise RuntimeError('应当有结果却查不到：' + query)
    return v


def ok(body, what):
    if not isinstance(body, dict) or body.get('code') != 200:
        raise RuntimeError('%s 失败：%s' % (what, body))
    return body


def app_login(tag):
    _, body = http('POST', '/app/auth/wx-login', {'code': 'stf-%s-%s' % (RUN, tag)})
    ok(body, '小程序登录 ' + tag)
    return body['token'], int(body['account']['id'])


def main():
    admin = admin_login()
    city = '大理'

    # ---------------------------------------------------------------- 门店
    store_name = '回归员工端门店-' + RUN
    ok(http('POST', '/kangaroo/store', {
        'storeName': store_name, 'city': city, 'address': '回归测试路 1 号',
        'contactPhone': '13700000009'}, token=admin)[1], '新建门店')
    store_id = int(must("select store_id from kg_store where store_name=%s and del_flag='0'"
                        % repr(store_name)))

    # ---------------------------------------------------------------- 岗位
    def new_job(title, status):
        ok(http('POST', '/kangaroo/job', {
            'storeId': store_id, 'title': title, 'jobType': 'parttime', 'city': city,
            'salaryType': 'none', 'headcount': 5}, token=admin)[1], '新建岗位 ' + title)
        jid = int(must("select job_id from kg_job where title=%s and del_flag='0'" % repr(title)))
        if status != 'draft':
            ok(http('PUT', '/kangaroo/job/changeStatus',
                    {'jobId': jid, 'status': 'published'}, token=admin)[1], '发布 ' + title)
        if status == 'closed':
            ok(http('PUT', '/kangaroo/job/changeStatus',
                    {'jobId': jid, 'status': 'closed'}, token=admin)[1], '关闭 ' + title)
        return jid

    job_title = '回归员工端岗位-' + RUN
    job_id = new_job(job_title, 'published')
    draft_title = '回归员工端草稿-' + RUN
    new_job(draft_title, 'draft')
    closed_title = '回归员工端已关闭-' + RUN
    new_job(closed_title, 'closed')

    # ---------------------------------------------------------------- 员工
    staff = {}
    for tag in ('staffA', 'staffB'):
        tok, uid = app_login(tag)
        _, body = http('POST', '/kangaroo/grant',
                       {'appUserId': uid, 'storeId': store_id, 'roleCode': 'staff'}, token=admin)
        ok(body, '授予 ' + tag)
        gid = int(must("select grant_id from kg_staff_grant where app_user_id=%s and store_id=%s"
                       % (uid, store_id)))
        staff[tag] = {'token': tok, 'userId': uid, 'grantId': gid}

    # ---------------------------------------------------------------- 时段
    base_ts = time.time() + 96 * 3600

    def new_slot(i):
        start = base_ts + i * 2 * 3600
        s_txt = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(start))
        e_txt = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(start + 1800))
        ok(http('POST', '/kangaroo/slot', {'storeId': store_id, 'startTime': s_txt,
                                           'endTime': e_txt}, token=admin)[1], '新建时段')
        return int(must("select slot_id from kg_interview_slot where store_id=%s and del_flag='0' "
                        "and start_time=%s" % (store_id, repr(s_txt))))

    slots = [new_slot(i) for i in range(1, 5)]

    # ---------------------------------------------------------------- 应聘者
    arrival = time.strftime('%Y-%m-%d', time.localtime(time.time() + 7 * 86400))
    people = {
        'pass': '回归应聘甲', 'reject': '回归应聘乙', 'race': '回归应聘丙',
        'withdraw': '回归应聘丁', 'notify': '回归应聘戊', 'decline': '回归应聘己',
    }
    users = {}
    for n, (key, real) in enumerate(people.items()):
        tok, uid = app_login(key)
        phone = '1391%03d%04d' % (int(RUN[:3], 16) % 1000, n + 1)
        _, body = http('POST', '/app/application', {
            'jobId': job_id, 'name': real, 'age': 24, 'phone': phone,
            'arrival': arrival, 'days': 14, 'skills': '咖啡、前台',
            'idemKey': 'stf-%s-%s' % (RUN, key)}, app_token=tok)
        ok(body, '报名 ' + key)
        app_id = int(must('select app_id from kg_application where app_user_id=%s and job_id=%s '
                          'order by app_id desc limit 1' % (uid, job_id)))
        app_no = must('select app_no from kg_application where app_id=%s' % app_id)
        users[key] = {'token': tok, 'userId': uid, 'appId': app_id, 'appNo': app_no,
                      'realName': real, 'phone': phone}

    # withdraw 推到"已预约面试"并占住第 3 个时段（撤回时要看它被释放）
    w = users['withdraw']
    ok(http('PUT', '/kangaroo/application/audit', {'appId': w['appId'], 'pass': True},
            token=admin)[1], '后台审核通过 withdraw')
    ok(http('POST', '/app/application/%s/book-interview' % w['appId'], {'slotId': slots[2]},
            app_token=w['token'])[1], 'withdraw 预约')
    w['slotId'] = slots[2]

    # decline 同样推到"已预约面试"并占住第 4 个时段：面试后点「不合适」要看到报名终止、时段释放（C13）
    d = users['decline']
    ok(http('PUT', '/kangaroo/application/audit', {'appId': d['appId'], 'pass': True},
            token=admin)[1], '后台审核通过 decline')
    ok(http('POST', '/app/application/%s/book-interview' % d['appId'], {'slotId': slots[3]},
            app_token=d['token'])[1], 'decline 预约')
    d['slotId'] = slots[3]

    out = {
        'run': RUN, 'storeId': store_id, 'storeName': store_name, 'city': city,
        'jobId': job_id, 'jobTitle': job_title,
        'draftTitle': draft_title, 'closedTitle': closed_title,
        'freeSlots': slots[:2], 'staff': staff, 'users': users,
    }
    print('SETUP=' + json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
