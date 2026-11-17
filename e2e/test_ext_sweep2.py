# -*- coding: utf-8 -*-
"""2026-09-30 审计修复（第二批 P2 / P3）的回归用例。

与 test_ext_sweep.py（W1–W9，对应 09-28 的 P0/P1 批次）同一思路：每条对应审计里已复现的失败场景，先造出当时的状态，
再断言业务结果（库里的状态 / 计数 / 名额 / 通知），不是只看返回码。

覆盖（括号里是审计条目）：
  Y1  投保 apply-batch 按报名所在门店逐条判权（insurance-apply-batch-cross-store-authz）；报名终止后待处理的投保自动作废、不能再登记保单
      （insurance-pending-survives-termination）
  Y2  评论计数：作者删评论与后台隐藏 / 恢复并发，帖子评论数 = 显示中的评论数（post-comments-counter-drift）
  Y3  住宿评价：房源详情查得到评价、评价不足 5 条不展示好评率、房源 / 门店评分与评价数回写、后台隐藏后回写（stay-detail-reviews-target-type-mismatch、
      store-stay-rating-never-aggregated）
  Y4  审核通过后用户不约面试：门店可终止（普通员工不行）（application-review-passed-cannot-terminate）
  Y5  后台关闭岗位时还没录用的报名一并终止并通知候选人；已关闭岗位不能确认录用、名额不动（closed-job-confirm-hire）
  Y6  全局异常处理：畸形 JSON / 类型不符 / 内容类型不对返回 4xx 而不是 500「系统繁忙」（global-exception-handler-client-errors）
  Y7  活动扩容后候补转正（activity-capacity-increase-no-promote）；费用格式校验（mp-fee-input-number-no-decimal）
  Y8  通用后台删房源 / 房型有未完成订单时被拒（extcrud-delete-no-order-guard）
  Y9  门店运营类型与经纬度校验（store-form-missing-op-type-coords）
  Y10 评价提请平台处理：处理过之后再次提请返回 409 而不是 500（review-rereport-uk-report-once-500）
  Y11 用户改期后终止 / 撤回：新时段被释放（user-reschedule-withdraw-slot-leak）
  Y12 工作台待办的跳转目标（workbench-todo-routes-wrong）
  Y13 停用门店后：岗位筛选、城市岗位计数不再包含，报名被拒（feed-search-ignore-store-status）
  Y14 通知偏好：只改频率不会把用户关掉的「新机会提醒」重新打开（notify-settings-job-new-reenabled）；紧急招募推送的频率与名单约束（urgent-recruit-push-unthrottled）
  Y15 线上任务：审核通过的通知不引到约面试、不能约面试、计薪方式与金额（online-task-approval-interview-flow、online-task-salary-type-fixed-admin-edit、
      online-task-commission-display-truncated）
  Y16 分享统计按员工分组，同名员工不合并（share-stats-grouped-by-nickname）
  Y17 日期 / 图片路径 / 登录资料 / 上传限流（strict-date-validation-invalid-dates-500、upload-path-whitelist-bypass、wx-login-nickname-avatar-unvalidated、upload-no-rate-limit）
  Y18 签署责任书必须是报名人本人（contract-sign-name-not-validated）
  Y19 房源入住 / 退房时间格式（stay-checkout-time-free-text-progress）
  Y20 点赞 / 收藏并发切换不死锁、计数不漂（like-favorite-toggle-deadlock）
  Y21 员工操作日志：管家只看本人（keeper-sees-all-op-logs）；门店任务不能自己验收、工时不超时间窗（store-task-self-check-points）
  Y22 商家确认超时：用户已确认愿意前往后，商家超期没确认 → 系统自动终止并通知双方（application-confirm-timeout-not-implemented）
  Y23 下架房源改了内容要重新审核、后台恢复上线不能绕过（admin-restore-stay-online-skips-review）
  Y24 看板报名转化率口径（dashboard-conversion-rate-wrong）
  Y25 线上任务：过了截止时间不再接单 / 验收后 7 天不付款可申诉（online-task-deadline-not-enforced、online-settlement-pending-payout-no-dispute）
  （精选评价不发积分在 test_ext_manage S5 里。）

前置：后端联调模式（python .dev/tools/dev_backend.py）。数据：每次运行新建两家门店与一批账号（reset-to-demo.sql 会清）。
用法：python -X utf8 e2e/test_ext_sweep2.py
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import test_kangaroo_flow as kf  # noqa: E402
import test_ext_manage as m  # noqa: E402
import test_ext_community as c  # noqa: E402
from test_ext_manage import (http, check, section, admin_login, CTX, PASSED, FAILED, sql, scalar, sql_exec, n, day, ok, denied,  # noqa: E402
                             code_of, data, app_login, guard, upload, parallel, msgs)

RUN = m.RUN

# 一张 1x1 的 PNG，上传限流用例用
PNG = bytes.fromhex('89504e470d0a1a0a0000000d4948445200000001000000010806000000'
                    '1f15c4890000000d49444154789c6360000002000001e221bc330000000049454e44ae426082')


def raw(method, path, body_bytes, app_token=None, ctype='application/json;charset=UTF-8'):
    """发一个不经过 json.dumps 的原始请求体（畸形 JSON 用）"""
    headers = {'Content-Type': ctype}
    if app_token:
        headers['X-App-Token'] = app_token
    req = urllib.request.Request(kf.BASE + path, data=body_bytes, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(text)
        except Exception:  # noqa: BLE001
            return e.code, {'raw': text}


def make_stay_order(guest_tok, check_in, nights=1, tag='x'):
    status, b = http('POST', '/app/stay/orders', {'roomTypeId': CTX['rt'], 'checkIn': day(check_in), 'checkOut': day(check_in + nights), 'guests': 1,
                                                  'guestName': '回归住客', 'guestPhone': '13800138000', 'agreementVersion': m.AGREEMENT,
                                                  'idemKey': 'sw2-%s-%s' % (tag, RUN)}, app_token=guest_tok)
    o = data(b) or {}
    assert o.get('orderNo'), b
    http('POST', '/app/pay/%s/prepay' % o['payNo'], {}, app_token=guest_tok)
    status, b2 = http('POST', '/app/pay/%s/mock-confirm' % o['payNo'], {}, app_token=guest_tok)
    assert ok(b2), b2
    return o['orderNo'], o['payNo']


def to_confirmed_in(store_id, tok, name, tag, headcount=2):
    """在指定门店建岗位并把 tok 这个用户的报名推进到「商家已确认录用」"""
    admin = CTX['admin']
    job = m.new_job(store_id, '回归岗位-%s-%s' % (tag, RUN), 'volunteer', headcount)
    aid = m.apply(job, tok, name, tag)
    http('PUT', '/kangaroo/application/audit', {'appId': aid, 'pass': True}, token=admin)
    m.new_slot(store_id)
    slots = data(http('GET', '/app/application/%s/slots' % aid, app_token=tok)[1]) or []
    assert slots, '需要可选面试时段'
    http('POST', '/app/application/%s/book-interview' % aid, {'slotId': slots[0]['slotId']}, app_token=tok)
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(aid)
    http('POST', '/app/application/%s/finish-interview' % aid, {}, app_token=tok)
    http('POST', '/app/application/%s/confirm' % aid, {'intent': 'accept'}, app_token=tok)
    status, b = http('PUT', '/kangaroo/application/confirm/%s' % aid, token=admin)
    assert ok(b), b
    return job, aid


def todo_rows(tok, store_id):
    """工作台待办是按「已逾期 / 今天 / 本周 / 暂无截止」分组下发的，摊平成一张列表"""
    status, b = http('GET', '/app/staff/workbench/todos?storeId=%s' % store_id, app_token=tok)
    return [it for g in ((data(b) or {}).get('groups') or []) for it in (g.get('items') or [])]


# ================================================================ Y1 投保：跨店判权 + 终止后自动作废
def y1_insurance():
    section('Y1 投保 apply-batch 按报名所在门店逐条判权；报名终止后待处理的投保自动作废')
    admin, A, B, u = CTX['admin'], CTX['A'], CTX['B'], CTX['u']
    k_tok, k_id = u['keeper']
    m.grant(admin, k_id, B, 'staff')          # 本店（A）的管家，同时是 B 店的普通员工
    k_tok = app_login('keeper')[0]
    plan = n("select plan_id from kg_insurance_plan where status='0' and del_flag='0' order by plan_id limit 1")
    job_b, app_b = to_confirmed_in(B, u['outsider'][0], '路人甲', 'y1b')
    status, b = http('POST', '/app/staff/insurance/apply-batch?storeId=%s' % A, {'appIds': [app_b], 'planId': plan}, app_token=k_tok)
    r = data(b) or {}
    check('Y1 A 店管家以 A 店身份批量投保 B 店报名（他在 B 店只是普通员工）：整批失败、库里没有申请',
          (0, [app_b], 0), (r.get('ok'), [f.get('appId') for f in r.get('failed') or []], n("select count(*) from kg_insurance_apply where app_id=%s" % app_b)))
    status, b = http('POST', '/app/staff/insurance/apply-batch?storeId=%s' % B, {'appIds': [app_b], 'planId': plan}, app_token=u['far'][0])
    check('Y1 对照：B 店主理人自己批量投保成功、落一条待处理', (1, 1), ((data(b) or {}).get('ok'),
                                                                     n("select count(*) from kg_insurance_apply where app_id=%s and status='pending'" % app_b)))
    ins_id = n("select apply_id from kg_insurance_apply where app_id=%s" % app_b)
    # 报名被终止 → 待处理的投保自动作废，平台不能再登记保单号
    status, b = http('POST', '/app/staff/applications/%s/terminate' % app_b, {'reason': '回归：候选人放弃'}, app_token=u['far'][0])
    check('Y1 B 店主理人终止这条报名', (200, 'terminated'), (code_of(b), scalar("select status from kg_application where app_id=%s" % app_b)))
    check('Y1 待处理的投保申请随之自动作废（rejected、有说明）', ('rejected', True),
          (scalar("select status from kg_insurance_apply where apply_id=%s" % ins_id),
           '自动作废' in str(scalar("select handle_note from kg_insurance_apply where apply_id=%s" % ins_id))))
    status, b = http('PUT', '/kangaroo/staffops/insapply/%s/effect' % ins_id, {'reason': 'POL-Y1-' + RUN}, token=admin)
    check('Y1 平台不能再给已作废的申请登记保单号；候选人没有收到「保险已生效」', (True, 0),
          (denied(b), msgs(u['outsider'][1], '用工保险已生效')))


# ================================================================ Y2 评论计数并发
def y2_comment_counter():
    section('Y2 作者删评论与后台隐藏 / 恢复并发：帖子评论数 = 显示中且未删除的评论数')
    admin = CTX['admin']
    a_tok, a_id = c.app_login('y2a')
    b_tok, b_id = c.app_login('y2b')
    c.consent(a_tok, 'community')
    c.consent(b_tok, 'community')
    CTX['y2'] = (a_tok, a_id, b_tok, b_id)
    b = c.publish(a_tok, c.TAG + ' 评论计数竞争', '正文')
    pid = int(c.data(b).get('postId') or 0)
    CTX['y2_post'] = pid
    cids = []
    for i in range(6):
        status, b = http('POST', '/app/community/posts/%s/comments' % pid, {'content': '回归第%d条评论' % i}, app_token=b_tok)
        cids.append(int(c.data(b).get('commentId') or 0))
    check('Y2 准备：6 条评论，计数 = 6', ('6', 6), (c.post_row(pid, 'comments')[0], len([x for x in cids if x])))

    def ops(i):
        cid = cids[i % 6]
        if i % 3 == 0:
            return http('DELETE', '/app/community/comments/%s' % cid, app_token=b_tok)[1]
        if i % 3 == 1:
            return http('PUT', '/kangaroo/community/comment/%s/hide' % cid, {'reason': '回归'}, token=admin)[1]
        return http('PUT', '/kangaroo/community/comment/%s/show' % cid, None, token=admin)[1]

    res = parallel(ops, range(18), 8)
    visible = n("select count(*) from kg_post_comment where post_id=%s and status='visible' and del_flag='0'" % pid)
    check('Y2 并发删除 / 隐藏 / 恢复之后：帖子评论数 = 库里显示中且未删除的评论数', visible, int(c.post_row(pid, 'comments')[0]))
    check('Y2 并发操作没有返回 500「系统繁忙」', [], [x.get('msg') for x in res if x.get('code') == 500 and '系统繁忙' in str(x.get('msg'))])


# ================================================================ Y3 住宿评价
def y3_stay_reviews():
    section('Y3 住宿评价：详情查得到、评分回写房源与门店、评价不足 5 条不展示好评率、后台隐藏后回写')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    guest_tok, guest_id = u['guest']
    stay = CTX['stay']
    no, pay_no = make_stay_order(guest_tok, 50, 1, 'y3')
    sql_exec("update kg_stay_order set status='completed' where order_no='%s'" % no)
    oid = n("select order_id from kg_stay_order where order_no='%s'" % no)
    status, b = http('POST', '/app/reviews', {'bizType': 'stay_order', 'bizId': oid, 'dims': [5, 4, 5, 4, 5], 'content': '回归：房间干净，主人热情',
                                              'anonymous': 'false'}, app_token=guest_tok)
    check('Y3 已完成的入住单可以写评价', 200, code_of(b))
    rid = n("select review_id from kg_review where biz_type='stay_order' and biz_id=%s" % oid)
    check('Y3 评价落库：对象类型 room、对象 = 房源', ('room', str(stay)), tuple(sql("select target_type, target_id from kg_review where review_id=%s" % rid)[0]))
    check('Y3 房源评价数 / 评分回写（此前没有任何代码回写，列表永远是「暂无评价」）', ('1', '5.0'),
          tuple(sql("select review_count, rating from kg_stay where stay_id=%s" % stay)[0]))
    check('Y3 门店评价数 / 评分回写', ('1', '5.00'), tuple(sql("select review_count, rating from kg_store where store_id=%s" % A)[0]))
    status, b = http('GET', '/app/stay/%s' % stay)
    d = data(b) or {}
    rv = d.get('reviewLatest') or {}
    check('Y3 房源详情的「最新评价」能查到（此前按 target_type=stay 查，永远为空）', (True, '回归：房间干净，主人热情'), (bool(rv), rv.get('content')))
    check('Y3 评价不足 5 条时不展示好评率', None, d.get('goodRate'))
    # 后台隐藏该评价（通用编辑：显示 = 隐藏）
    status, b = http('PUT', '/kangaroo/ext/review', {'reviewId': rid, 'status': '1'}, token=admin)
    check('Y3 后台隐藏评价', (200, '1'), (code_of(b), scalar("select status from kg_review where review_id=%s" % rid)))
    check('Y3 隐藏后房源 / 门店聚合回写：评价数 0、评分为空', (('0', '1'), ('0', '1')),
          (tuple(sql("select review_count, rating is null from kg_stay where stay_id=%s" % stay)[0]),
           tuple(sql("select review_count, rating is null from kg_store where store_id=%s" % A)[0])))
    status, b = http('GET', '/app/stay/%s' % stay)
    check('Y3 隐藏后详情里不再展示这条评价', None, (data(b) or {}).get('reviewLatest'))
    status, b = http('PUT', '/kangaroo/ext/review', {'reviewId': rid, 'status': '0'}, token=admin)
    check('Y3 恢复显示后聚合回写回来', ('1', '5.0'), tuple(sql("select review_count, rating from kg_stay where stay_id=%s" % stay)[0]))
    CTX['y3_review'] = rid
    # 并发写评价：两个人各有一张已完成的入住单，同时评价同一家房源 / 同一家门店。
    # 评分回写放在事务提交之后、单独的新事务里做（在评价事务里夹一条带子查询的 UPDATE 会对 kg_review 加共享 next-key 锁，互相等对方刚插入的行 → 死锁）
    toks = []
    for k, tag in enumerate(('y3a', 'y3b')):
        t, uid = app_login(tag)
        no2, pay2 = make_stay_order(t, 88 + k, 1, 'y3' + tag)
        sql_exec("update kg_stay_order set status='completed' where order_no='%s'" % no2)
        toks.append((t, n("select order_id from kg_stay_order where order_no='%s'" % no2)))
    before = n("select count(*) from kg_review where target_type='room' and target_id=%s and status='0' and del_flag='0'" % stay)
    res = parallel(lambda i: http('POST', '/app/reviews', {'bizType': 'stay_order', 'bizId': toks[i][1], 'dims': [5, 5, 4, 5, 5], 'content': '回归：并发评价%d' % i,
                                                          'anonymous': 'false'}, app_token=toks[i][0])[1], range(2), 2)
    check('Y3 两人同时评价同一家房源：都成功、没有 500', ([200, 200], []), ([x.get('code') for x in res], [x.get('msg') for x in res if x.get('code') == 500]))
    check('Y3 并发评价之后房源评价数 = 显示中的评价条数（没有丢回写）', (before + 2, before + 2),
          (n("select review_count from kg_stay where stay_id=%s" % stay), n("select count(*) from kg_review where target_type='room' and target_id=%s and status='0' and del_flag='0'" % stay)))


# ================================================================ Y4 审核通过后可终止
def y4_review_passed_terminate():
    section('Y4 审核通过后用户一直不约面试：主理人可以终止，普通员工不行；用户自己走撤回')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    job = m.new_job(A, '回归终止岗位-' + RUN, 'volunteer', 3)
    w_tok = u['worker'][0]
    aid = m.apply(job, w_tok, '接单人甲', 'y4')
    # 待办路由（Y12 用）：此时是待审核
    hit = [r for r in todo_rows(u['mgr'][0], A) if r.get('key') == 'app_review:%s' % aid]
    check('Y12 待办「报名待审核」的跳转带上报名 ID、直接进详情屏', ['/pages/staff/applications?s=2&appId=%s' % aid], [r.get('route') for r in hit])
    http('PUT', '/kangaroo/application/audit', {'appId': aid, 'pass': True}, token=admin)
    check('Y4 准备：审核通过（review_passed）', 'review_passed', scalar("select status from kg_application where app_id=%s" % aid))
    status, b = http('POST', '/app/staff/applications/%s/terminate' % aid, {'reason': '回归：长期不约面试'}, app_token=u['staff'][0])
    check('Y4 普通员工不能终止（403）、状态不变', (403, 'review_passed'), (code_of(b), scalar("select status from kg_application where app_id=%s" % aid)))
    status, b = http('POST', '/app/staff/applications/%s/terminate' % aid, {}, app_token=u['mgr'][0])
    check('Y4 终止必须写原因', (True, 'review_passed'), (denied(b), scalar("select status from kg_application where app_id=%s" % aid)))
    status, b = http('POST', '/app/staff/applications/%s/terminate' % aid, {'reason': '回归：长期不约面试'}, app_token=u['mgr'][0])
    check('Y4 主理人终止 → terminated（此前门店和后台都没有出口）', (200, 'terminated'), (code_of(b), scalar("select status from kg_application where app_id=%s" % aid)))
    check('Y4 候选人收到「报名已终止」通知', 1, msgs(u['worker'][1], '报名已终止'))
    status, b = http('POST', '/app/staff/applications/%s/terminate' % aid, {'reason': '再来一次'}, app_token=u['mgr'][0])
    check('Y4 终态不能再终止', True, denied(b), ok=denied(b))


# ================================================================ Y5 关闭岗位连带结束报名；已关闭岗位不能确认录用
def to_user_confirmed(job, tok, name, tag):
    """把 tok 这个用户在 job 上的报名推进到「用户已确认意向」（user_confirmed），返回报名 ID"""
    admin = CTX['admin']
    aid = m.apply(job, tok, name, tag)
    http('PUT', '/kangaroo/application/audit', {'appId': aid, 'pass': True}, token=admin)
    m.new_slot(CTX['A'])
    slots = data(http('GET', '/app/application/%s/slots' % aid, app_token=tok)[1]) or []
    assert slots, '需要可选面试时段'
    http('POST', '/app/application/%s/book-interview' % aid, {'slotId': slots[0]['slotId']}, app_token=tok)
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(aid)
    http('POST', '/app/application/%s/finish-interview' % aid, {}, app_token=tok)
    http('POST', '/app/application/%s/confirm' % aid, {'intent': 'accept'}, app_token=tok)
    return aid


def y5_closed_job_confirm():
    section('Y5 后台关闭岗位：还没录用的报名同一事务里终止并通知候选人，之后确认录用被拒、名额不动；旧数据（岗位已关、报名还停在用户已确认）确认录用也被拒')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    tok, uid = u['talent'][0], u['talent'][1]
    job = m.new_job(A, '回归下架岗位-' + RUN, 'volunteer', 2)
    aid = to_user_confirmed(job, tok, '候选人丙', 'y5')
    check('Y5 准备：用户已确认意向（user_confirmed）', 'user_confirmed', scalar("select status from kg_application where app_id=%s" % aid))
    before = msgs(uid, '报名已终止')
    status, b = http('PUT', '/kangaroo/job/changeStatus', {'jobId': job, 'status': 'closed'}, token=admin)
    check('Y5 后台关闭岗位，响应 endedApps=1（这条还没录用的报名一并结束）', (200, 'closed', 1),
          (code_of(b), scalar("select status from kg_job where job_id=%s" % job), (b or {}).get('endedApps')))
    check('Y5 关闭岗位的同时报名被终止（terminated），终止原因「岗位已关闭」', ('terminated', '岗位已关闭'),
          (scalar("select status from kg_application where app_id=%s" % aid), scalar("select terminate_reason from kg_application where app_id=%s" % aid)))
    check('Y5 候选人收到「报名已终止」（+1）', 1, msgs(uid, '报名已终止') - before)
    status, b = http('PUT', '/kangaroo/application/confirm/%s' % aid, token=admin)
    msg = str((b or {}).get('msg'))
    check('Y5 已终止的报名确认录用被拒：当前状态【已终止】不能变更为【商家已确认】', '拒绝，提示含「当前状态【已终止】」', msg,
          ok=denied(b) and '当前状态【已终止】' in msg)
    check('Y5 报名仍是 terminated、已录用名额 0', ('terminated', '0'),
          (scalar("select status from kg_application where app_id=%s" % aid), scalar("select hired_count from kg_job where job_id=%s" % job)))
    # 旧数据：这次修复之前关掉的岗位，报名可能还停在「用户已确认」（当时关闭岗位不处理报名）。直接用库关岗位来模拟
    job2 = m.new_job(A, '回归旧关闭岗位-' + RUN, 'volunteer', 2)
    aid2 = to_user_confirmed(job2, tok, '候选人丙', 'y5old')
    sql_exec("update kg_job set status='closed' where job_id=%s" % job2)
    check('Y5 旧数据准备：岗位已关闭、报名仍是 user_confirmed', ('closed', 'user_confirmed'),
          (scalar("select status from kg_job where job_id=%s" % job2), scalar("select status from kg_application where app_id=%s" % aid2)))
    status, b = http('PUT', '/kangaroo/application/confirm/%s' % aid2, token=admin)
    msg = str((b or {}).get('msg'))
    check('Y5 旧数据：岗位已关闭时确认录用被拒，提示含「已关闭」（不再叫人重新上架）', '拒绝，提示含「已关闭」', msg,
          ok=denied(b) and '已关闭' in msg)
    check('Y5 旧数据：报名状态不变、已录用名额 0', ('user_confirmed', '0'),
          (scalar("select status from kg_application where app_id=%s" % aid2), scalar("select hired_count from kg_job where job_id=%s" % job2)))


# ================================================================ Y6 全局异常处理
def y6_exception_handler():
    section('Y6 客户端传错（畸形 JSON / 类型不符 / 内容类型不对）返回 4xx，不再是 500「系统繁忙」+ 堆栈')
    tok = CTX['u']['guest'][0]
    status, b = raw('POST', '/app/reviews', b'{not json', app_token=tok)
    check('Y6 畸形 JSON → 400「请求内容格式不正确」', (400, True), (b.get('code'), '格式不正确' in str(b.get('msg'))))
    status, b = raw('POST', '/app/reviews', b'{"bizType":"stay_order","bizId":"abc","dims":[5]}', app_token=tok)
    check('Y6 字段类型对不上：给出具体的输入提示，不是「系统繁忙」+ 堆栈', (True, False), (b.get('code') != 200, '系统繁忙' in str(b.get('msg'))))
    status, b = http('GET', '/app/stay/abc')
    check('Y6 路径参数类型不符 → 400', (400, False), (b.get('code'), '系统繁忙' in str(b.get('msg'))))
    status, b = raw('POST', '/app/reviews', b'hello', app_token=tok, ctype='text/plain')
    check('Y6 内容类型不对 → 415', (415, False), (b.get('code'), '系统繁忙' in str(b.get('msg'))))
    status, b = http('GET', '/app/stay/list?pageNum=abc')
    check('Y6 分页参数不是数字不再 500', False, '系统繁忙' in str(b.get('msg')))


# ================================================================ Y7 活动
def y7_activity():
    section('Y7 活动扩容后候补转正；费用格式校验')
    A, u = CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    t1, id1 = c.app_login('y7a')
    t2, id2 = c.app_login('y7b')
    for t in (t1, t2):
        c.consent(t, 'activity')
    title = c.TAG + ' 扩容回归-' + RUN
    payload = {'category': '手作课程', 'title': title, 'cover': '', 'place': '门店一楼手作区', 'capacity': 1, 'startTime': c.day(3, '14:00'),
               'endTime': c.day(3, '17:00'), 'feeYuan': '0', 'intro': '回归'}
    status, b = http('POST', '/app/staff/activities?storeId=%s' % A, payload, app_token=mgr_tok)
    aid = int((data(b) or {}).get('activityId') or 0)
    check('Y7 员工创建免费活动（名额 1）', (200, True), (code_of(b), aid > 0))
    if (data(b) or {}).get('status') != 'open':
        http('PUT', '/kangaroo/community/activity/%s/approve' % aid, {}, token=CTX['admin'])
    s1 = c.signup(t1, aid)
    s2 = c.signup(t2, aid)
    check('Y7 第一位报名 confirmed、第二位候补', ('confirmed', 'waitlist'), (c.data(s1).get('status'), c.data(s2).get('status')))
    sid2 = int(c.data(s2).get('signupId') or 0)
    status, b = http('PUT', '/app/staff/activities/%s?storeId=%s' % (aid, A), dict(payload, capacity=2), app_token=mgr_tok)
    check('Y7 门店把名额调到 2（已有人报名：时间 / 地点 / 费用不变，名额不少于已报名人数）', 200, code_of(b))
    check('Y7 候补的人自动转正 confirmed（此前要一直等到有人退出）', ('confirmed', 2, 0),
          (scalar("select status from kg_activity_signup where signup_id=%s" % sid2),
           n("select signed from kg_activity where activity_id=%s" % aid), n("select waitlist from kg_activity where activity_id=%s" % aid)))
    # 费用格式
    for label, fee in (('三位小数', '12.345'), ('不是数字', 'abc'), ('负数', '-5')):
        status, b = http('POST', '/app/staff/activities?storeId=%s' % A, dict(payload, title=title + label, feeYuan=fee), app_token=mgr_tok)
        check('Y7 费用 %s 被拒（422）' % label, (422, 0), (code_of(b), n("select count(*) from kg_activity where title='%s'" % (title + label))))
    status, b = http('POST', '/app/staff/activities?storeId=%s' % A, dict(payload, title=title + '带角', feeYuan='12.5'), app_token=mgr_tok)
    check('Y7 费用 12.5 元 → fee_fen = 1250', (200, '1250'), (code_of(b), scalar("select fee_fen from kg_activity where title='%s'" % (title + '带角'))))
    # 取消 / 结束的活动不能靠编辑重新开启（用户自发活动）
    status, b = http('POST', '/app/staff/activities/%s/action/cancel?storeId=%s' % (aid, A), {'reason': '回归：取消'}, app_token=mgr_tok)
    status, b = http('PUT', '/app/staff/activities/%s?storeId=%s' % (aid, A), dict(payload, capacity=3), app_token=mgr_tok)
    check('Y7 已取消的活动不能再编辑', True, denied(b), ok=denied(b))


# ================================================================ Y8 通用后台删除守卫
def y8_delete_guard():
    section('Y8 通用后台删除房源 / 房型：还有未完成订单时被拒')
    admin, u = CTX['admin'], CTX['u']
    stay, rt = CTX['stay'], CTX['rt']
    no, pay_no = make_stay_order(u['guest'][0], 70, 1, 'y8')
    status, b = http('DELETE', '/kangaroo/ext/roomtype/%s' % rt, token=admin)
    check('Y8 有未完成订单的房型不能删除', (True, '0'), (denied(b), scalar("select del_flag from kg_stay_room_type where room_type_id=%s" % rt)))
    status, b = http('DELETE', '/kangaroo/ext/stay/%s' % stay, token=admin)
    check('Y8 有未完成订单的房源不能删除', (True, '0'), (denied(b), scalar("select del_flag from kg_stay where stay_id=%s" % stay)))
    check('Y8 提示里说明是有订单', True, '订单' in str((b or {}).get('msg')))


# ================================================================ Y9 门店运营类型 / 经纬度
def y9_store_fields():
    section('Y9 门店运营类型与经纬度：取值校验、保存、回读')
    admin, A = CTX['admin'], CTX['A']
    status, b = http('PUT', '/kangaroo/store', {'storeId': A, 'opType': 'bad'}, token=admin)
    check('Y9 运营类型不在 直营 / 代运营 / 加盟 内被拒', (True, 'direct'), (denied(b), scalar("select op_type from kg_store where store_id=%s" % A)))
    status, b = http('PUT', '/kangaroo/store', {'storeId': A, 'latitude': 95, 'longitude': 100}, token=admin)
    check('Y9 纬度超出范围被拒', True, denied(b), ok=denied(b))
    status, b = http('PUT', '/kangaroo/store', {'storeId': A, 'latitude': 25.6}, token=admin)
    check('Y9 只填纬度不填经度被拒', True, denied(b), ok=denied(b))
    status, b = http('PUT', '/kangaroo/store', {'storeId': A, 'opType': 'franchise', 'latitude': 25.6065, 'longitude': 100.2679}, token=admin)
    check('Y9 合法的运营类型与经纬度保存成功', (200, 'franchise', '25.606500', '100.267900'),
          (code_of(b),) + tuple(sql("select op_type, latitude, longitude from kg_store where store_id=%s" % A)[0]))
    status, b = http('GET', '/kangaroo/store/%s' % A, token=admin)
    check('Y9 后台详情回读运营类型', 'franchise', (data(b) or {}).get('opType'))
    status, b = http('PUT', '/kangaroo/store', {'storeId': A, 'opType': 'direct'}, token=admin)
    check('Y9 改回直营', (200, 'direct'), (code_of(b), scalar("select op_type from kg_store where store_id=%s" % A)))


# ================================================================ Y10 评价提请
def y10_review_rereport():
    section('Y10 评价提请平台处理：平台处理完之后再次提请返回 409 而不是 500')
    admin, u = CTX['admin'], CTX['u']
    rid = CTX.get('y3_review')
    if not rid:
        check('Y10 需要 Y3 留下的评价', True, False, ok=False)
        return
    mgr_tok = u['mgr'][0]
    status, b = http('POST', '/app/staff/reviews/%s/report' % rid, {'reason': '疑似刷评'}, app_token=mgr_tok)
    check('Y10 主理人提请平台处理 → 200', 200, code_of(b))
    check('Y10 重复提请（平台还没处理）→ 409', 409, code_of(http('POST', '/app/staff/reviews/%s/report' % rid, {'reason': '再来'}, app_token=mgr_tok)[1]))
    report_id = n("select report_id from kg_report where target_type='review' and target_id=%s" % rid)
    status, b = http('PUT', '/kangaroo/core/report/%s/reject' % report_id, {'reason': '回归：不成立'}, token=admin)
    check('Y10 平台判定不成立（举报已处理）', (200, True), (code_of(b), scalar("select status from kg_report where report_id=%s" % report_id) != 'pending'))
    status, b = http('POST', '/app/staff/reviews/%s/report' % rid, {'reason': '不服，再提请'}, app_token=mgr_tok)
    check('Y10 处理之后再次提请 → 409（此前撞 uk_report_once 是 500）', (409, False), (code_of(b), '系统繁忙' in str((b or {}).get('msg'))))


# ================================================================ Y11 改期后释放时段
def y11_reschedule_release():
    section('Y11 用户改期后被终止 / 撤回：新时段被释放，不会被终止的报名永久占住')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    job = m.new_job(A, '回归改期岗位-' + RUN, 'volunteer', 6)
    tok = u['vol'][0]

    def one(tag, terminate_by_staff):
        aid = m.apply(job, tok, '回归义工', 'y11' + tag)
        http('PUT', '/kangaroo/application/audit', {'appId': aid, 'pass': True}, token=admin)
        for _ in range(2):
            m.new_slot(A)
        slots = data(http('GET', '/app/application/%s/slots' % aid, app_token=tok)[1]) or []
        if len(slots) < 2:
            return aid, None, None
        http('POST', '/app/application/%s/book-interview' % aid, {'slotId': slots[0]['slotId']}, app_token=tok)
        b1 = http('POST', '/app/application/%s/reschedule-interview' % aid, {'slotId': slots[1]['slotId'], 'reason': ''}, app_token=tok)[1]
        return aid, slots, b1

    aid, slots, b1 = one('a', True)
    check('Y11 准备：预约后改期到第二个时段', (True, 200), (slots is not None, code_of(b1) if b1 else None))
    if not slots:
        return
    s0, s1 = slots[0]['slotId'], slots[1]['slotId']
    check('Y11 改期后旧时段放回可约、新时段被占', ('open', None, 'booked', str(aid)),
          (scalar("select status from kg_interview_slot where slot_id=%s" % s0), None if scalar("select booked_app_id from kg_interview_slot where slot_id=%s" % s0) in (None, 'NULL') else 'x',
           scalar("select status from kg_interview_slot where slot_id=%s" % s1), scalar("select booked_app_id from kg_interview_slot where slot_id=%s" % s1)))
    status, b = http('POST', '/app/staff/applications/%s/terminate' % aid, {'reason': '回归：终止'}, app_token=u['mgr'][0])
    check('Y11 主理人终止这条报名', (200, 'terminated'), (code_of(b), scalar("select status from kg_application where app_id=%s" % aid)))
    check('Y11 终止后新时段被释放（booked_app_id 清空、状态 open）、面试记录作废', ('open', True, 'canceled'),
          (scalar("select status from kg_interview_slot where slot_id=%s" % s1),
           scalar("select booked_app_id from kg_interview_slot where slot_id=%s" % s1) in (None, 'NULL'),
           scalar("select status from kg_interview where app_id=%s" % aid)))
    # 并发：改期与撤回同时发生，无论谁先，终态报名都不能占着任何时段
    bad = 0
    for i in range(4):
        aid2, slots2, _ = one('c%d' % i, False)
        if not slots2:
            continue
        parallel(lambda k: (http('POST', '/app/application/%s/reschedule-interview' % aid2, {'slotId': slots2[1]['slotId'], 'reason': ''}, app_token=tok)[1]
                            if k == 0 else http('POST', '/app/application/%s/withdraw' % aid2, {}, app_token=tok)[1]), range(2), 2)
        st = scalar("select status from kg_application where app_id=%s" % aid2)
        held = n("select count(*) from kg_interview_slot where booked_app_id=%s" % aid2)
        if st in ('withdrawn', 'terminated') and held:
            bad += 1
    check('Y11 改期与撤回并发 4 轮：没有任何已撤回的报名还占着时段', 0, bad)


# ================================================================ Y12（待办路由在 Y4 里查）+ 退款待办
def y12_todo_refund_route():
    section('Y12 工作台待办：住宿退款跳到「保险与二消订单 → 退款申请」，不是 Vlog 页')
    A, u = CTX['A'], CTX['u']
    guest_tok = u['guest'][0]
    no, pay_no = make_stay_order(guest_tok, 80, 1, 'y12')
    status, b = http('POST', '/app/stay/orders/%s/refund' % no, {'reason': '行程变动'}, app_token=guest_tok)
    oid = n("select order_id from kg_stay_order where order_no='%s'" % no)
    hit = [r for r in todo_rows(u['mgr'][0], A) if r.get('key') == 'stay_refund:%s' % oid]
    check('Y12 「住宿退款待处理」的跳转目标', ['/pages/staff/insurance?s=4&tab=refund'], [r.get('route') for r in hit])
    # 退款审批后清掉，避免影响别的用例
    http('POST', '/app/staff/stay/refunds/reject', {'bizType': 'stay', 'orderNo': no, 'reason': '回归'}, app_token=u['mgr'][0])


# ================================================================ Y13 停用门店
def y13_disabled_store():
    section('Y13 门店停用后：岗位筛选与城市岗位计数不再包含它的岗位，报名被拒')
    admin = CTX['admin']
    status, b = http('POST', '/kangaroo/store', {'storeName': '回归停用可见性-' + RUN, 'city': '大理', 'address': '回归路 9 号', 'intro': 'x',
                                                  'contactPhone': '18500000002'}, token=admin)
    sid = int(data(b))
    title = '停用可见回归%s' % RUN[:4]
    job = m.new_job(sid, title, 'volunteer', 2)
    tok, uid = app_login('y13')
    ov = lambda: int(((http('GET', '/app/cities/overview?city=' + urllib.parse.quote('大理'))[1]) or {}).get('data', {}).get('jobs') or 0)
    srch = lambda: [j.get('jobId') for j in (data(http('POST', '/app/jobs/search', {'cities': ['大理'], 'types': [title]}, app_token=tok)[1]) or {}).get('jobs', [])]
    before_n, before_s = ov(), srch()
    check('Y13 停用前筛选里有这个岗位', [job], before_s)
    status, b = http('PUT', '/kangaroo/store', {'storeId': sid, 'status': '1'}, token=admin)
    check('Y13 后台停用门店', (200, '1'), (code_of(b), scalar("select status from kg_store where store_id=%s" % sid)))
    check('Y13 停用后筛选里没有它的岗位', [], srch())
    check('Y13 停用后城市主页的在招岗位数少 1', before_n - 1, ov())
    status, b = http('POST', '/app/application', {'jobId': job, 'name': '停用回归', 'age': 24, 'phone': '13500135000', 'arrival': day(3), 'days': 30,
                                                  'idemKey': 'y13-' + RUN}, app_token=tok)
    check('Y13 停用后报名被拒、没有落库', (True, 0), (denied(b), n("select count(*) from kg_application where job_id=%s" % job)))
    status, b = http('PUT', '/kangaroo/store', {'storeId': sid, 'status': '0'}, token=admin)
    check('Y13 恢复营业后筛选里又有了', [job], srch())


# ================================================================ Y14 通知偏好 + 紧急招募
def y14_notify_and_urgent():
    section('Y14 通知偏好不被「改频率」重新打开；紧急招募推送：往期候选人 / 暂停提醒的不推、24 小时内最多 1 次')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    # ---- 偏好
    tok, uid = app_login('y14n')
    status, b = http('POST', '/app/job-subscriptions', {'cities': ['大理'], 'jobTypes': ['咖啡'], 'freq': 'instant'}, app_token=tok)
    check('Y14 准备：建一条岗位订阅', 200, code_of(b))
    status, b = http('POST', '/app/notify-settings', {'rows': [{'key': 'job_new', 'app': False}]}, app_token=tok)
    check('Y14 暂停「新机会提醒」', (200, '0'), (code_of(b), scalar("select enabled from kg_notify_pref where app_user_id=%s and pref_key='job_new'" % uid)))
    status, b = http('POST', '/app/notify-settings', {'freq': 'daily'}, app_token=tok)
    check('Y14 只改推送频率，不会把刚暂停的「新机会提醒」重新打开（此前每次保存都打开）', (200, '0'),
          (code_of(b), scalar("select enabled from kg_notify_pref where app_user_id=%s and pref_key='job_new'" % uid)))
    # ---- 紧急招募
    job = m.new_job(A, '回归紧急招募-' + RUN, 'volunteer', 3)
    other = m.new_job(A, '回归往期岗位-' + RUN, 'volunteer', 3)
    f1, id1 = app_login('y14f1')
    f2, id2 = app_login('y14f2')
    f3, id3 = app_login('y14f3')
    for t in (f1, f2, f3):
        http('POST', '/app/job/favorite/%s?favorite=true' % job, None, app_token=t)
    m.apply(other, f2, '往期候选人', 'y14p')                      # f2 是本店的往期候选人
    http('POST', '/app/notify-settings', {'rows': [{'key': 'job_new', 'app': False}]}, app_token=f3)   # f3 暂停了提醒
    body = {'jobId': job, 'level': 3, 'deadline': day(2) + ' 18:00', 'targets': ['favorites'], 'push': True}
    title = '紧急招募：回归紧急招募-' + RUN
    status, b = http('POST', '/app/staff/dispatch/urgent', body, app_token=mgr_tok)
    r = data(b) or {}
    check('Y14 第一次推送：只推给订阅了、没暂停、不是往期候选人的 1 位', (200, 1, 1, 0, 0),
          (code_of(b), r.get('usersNotified'), msgs(id1, title), msgs(id2, title), msgs(id3, title)))
    status, b = http('POST', '/app/staff/dispatch/urgent', body, app_token=mgr_tok)
    r = data(b) or {}
    check('Y14 24 小时内同一岗位第二次：只更新标记，不再推送', (200, True, 0, 1), (code_of(b), r.get('throttled'), r.get('usersNotified'), msgs(id1, title)))


# ================================================================ Y15 线上任务
def y15_online_task():
    section('Y15 线上任务：计薪方式 / 金额保留角分；审核通过不引到约面试，也不能约面试')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    body = {'title': '回归线上任务-' + RUN, 'taskType': '内容写作', 'deliverables': '500 字文案', 'criteria': '原创', 'description': '回归',
            'commissionFen': 1250, 'quota': 2, 'deadline': time.strftime('%Y-%m-%d %H:%M', time.localtime(time.time() + 3 * 86400))}
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % A, body, app_token=mgr_tok)
    jid = int((data(b) or {}).get('jobId') or 0)
    check('Y15 主理人发布线上任务（佣金 12.50 元）', (200, True), (code_of(b), jid > 0))
    check('Y15 计薪方式是字典里有的「计件」、金额保留两位小数（此前写 fixed、12.50 存成 12）', ('piece', '12.50', '12.50', '元/单'),
          tuple(sql("select salary_type, salary_min, salary_max, salary_unit from kg_job where job_id=%s" % jid)[0]))
    status, b = http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=admin)
    status, b = http('GET', '/app/job/%s' % jid)
    check('Y15 岗位详情的薪资文案是「12.5 元/单」（此前被截成 12）', '12.5', str((data(b) or {}).get('salaryText')).split(' ')[0])
    # 后台编辑这条任务（此前 salary_type=fixed 不在白名单，保存不了）
    status, b = http('PUT', '/kangaroo/job', {'jobId': jid, 'storeId': A, 'jobType': 'online', 'title': '回归线上任务改-' + RUN, 'city': '不限', 'address': '远程',
                                              'salaryType': 'piece', 'salaryMin': 12.5, 'salaryMax': 12.5, 'salaryUnit': '元/单', 'headcount': 2,
                                              'startDate': day(0)}, token=admin)
    check('Y15 后台可以编辑线上任务', 200, code_of(b))
    w_tok, w_id = u['worker2']
    aid = m.apply(jid, w_tok, '接单人乙', 'y15')
    status, b = http('POST', '/app/staff/applications/%s/audit' % aid, {'pass': True}, app_token=u['staff'][0])
    check('Y15 普通员工审核被拒且状态不变', (403, 'submitted'), (code_of(b), scalar("select status from kg_application where app_id=%s" % aid)))
    status, b = http('POST', '/app/staff/applications/%s/audit' % aid, {'pass': True}, app_token=mgr_tok)
    check('Y15 审核通过', (200, 'review_passed'), (code_of(b), scalar("select status from kg_application where app_id=%s" % aid)))
    r = sql("select title, route from kg_message where recipient_id=%s and biz_type='application' and biz_id=%s order by msg_id desc limit 1" % (w_id, aid))
    check('Y15 通知是「接单申请已通过」，落地页不带 s=2（不引到约面试屏）', ('接单申请已通过', False), (r[0][0] if r else None, 's=2' in str(r[0][1]) if r else None))
    m.new_slot(A)
    slots = data(http('GET', '/app/application/%s/slots' % aid, app_token=w_tok)[1]) or []
    status, b = http('POST', '/app/application/%s/book-interview' % aid, {'slotId': (slots[0]['slotId'] if slots else 0)}, app_token=w_tok)
    check('Y15 线上任务不能预约门店的面试时段（没有面试环节）', (True, 0), (denied(b), n("select count(*) from kg_interview_slot where booked_app_id=%s" % aid)))


# ================================================================ Y16 分享统计
def y16_share_stats():
    section('Y16 分享统计按员工分组：同名员工不合并成一行')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    tokens = []
    for tag in ('y16a', 'y16b'):
        t, uid = app_login(tag, '同名员工')
        m.grant(admin, uid, A, 'keeper')
        t, uid = app_login(tag)
        tokens.append((t, uid))
    job = m.new_job(A, '回归分享岗位-' + RUN, 'volunteer', 3)
    for t, uid in tokens:
        status, b = http('POST', '/app/staff/jobs/%s/share' % job, {'channel': 'link'}, app_token=t)
        check('Y16 员工 %s 生成分享码并记一次分享' % uid, 200, code_of(b))
    status, b = http('GET', '/app/staff/jobs/share-stats?storeId=%s&jobId=%s&dim=staff' % (A, job), app_token=u['mgr'][0])
    d = data(b) or {}
    names = [r.get('name') for r in d.get('rows') or []]
    ids = sorted(int(r.get('id')) for r in d.get('allRanks') or [] if r.get('id') is not None)
    check('Y16 两位同名员工是两行（名字后带编号区分），排行榜也是两个人', (2, sorted(x[1] for x in tokens)), (len(names), ids))
    check('Y16 分享榜合计 = 2 次', 2, sum(int(r.get('share') or 0) for r in d.get('allRanks') or []))


# ================================================================ Y17 输入校验
def y17_validation():
    section('Y17 日期 / 图片路径 / 登录资料 / 上传限流')
    admin = CTX['admin']
    a_tok, a_id = CTX['y2'][0], CTX['y2'][1]
    c.verify_phone(a_tok)
    # 日期
    b = c.publish(a_tok, c.TAG + ' 假日期搭子', '去爬山', kind='buddy', eventDate='2026-02-30', category='hike')
    check('Y17 不存在的日期（2026-02-30）发「找搭子」被拒（422），没有落库', (422, 0),
          (b.get('code'), n("select count(*) from kg_post where title='%s'" % (c.TAG + ' 假日期搭子'))))
    status, b = http('GET', '/app/community/buddies?date=2026-02-30')
    check('Y17 用不存在的日期筛选「找搭子」不再 500', False, b.get('code') == 500)
    # 图片路径：逗号夹带外链
    b = c.publish(a_tok, c.TAG + ' 图片路径回归', '正文', images=['/profile/upload/a.png,https://evil.example/x.png', '/profile/upload/ok.png'])
    pid = int(c.data(b).get('postId') or 0)
    imgs = str(scalar("select images from kg_post where post_id=%s" % pid))
    check('Y17 帖子图片：带逗号夹带外链的被丢弃，合法的保留', (False, True), ('evil.example' in imgs, '/profile/upload/ok.png' in imgs))
    # 找搭子分类：客户端没传时按内容推断
    b = c.publish(a_tok, c.TAG + ' 周末一起徒步', '想找人一起爬山徒步', kind='buddy', eventDate=day(5))
    pid2 = int(c.data(b).get('postId') or 0)
    check('Y17 找搭子没传分类时按内容推断为「徒步登山」（此前恒为 other，分类筛选永远为空）', 'hike', scalar("select category from kg_post where post_id=%s" % pid2))
    # 活动封面
    status, b = http('POST', '/app/staff/activities?storeId=%s' % CTX['A'], {'category': '手作课程', 'title': c.TAG + ' 外链封面', 'cover': 'https://evil.example/c.png',
                                                                             'place': '一楼', 'capacity': 2, 'startTime': c.day(3, '14:00'), 'endTime': c.day(3, '17:00'),
                                                                             'feeYuan': '0', 'intro': 'x'}, app_token=CTX['u']['mgr'][0])
    check('Y17 活动封面不是平台上传的图片被拒（422）', 422, code_of(b))
    # 登录资料
    code = 'sw2-%s-login' % RUN
    status, b = http('POST', '/app/auth/wx-login', {'code': code, 'nickname': '长' * 70, 'avatar': 'https://evil.example/a.png'})
    acc = (b or {}).get('account') or {}
    check('Y17 登录带 70 字昵称 + 外链头像：登录成功，昵称 / 头像回退为默认值（此前昵称超长直接 500）',
          (200, '小院同学', True), ((b or {}).get('code'), acc.get('nickname'), not acc.get('avatar') or 'evil' not in str(acc.get('avatar'))))
    status, b = http('POST', '/app/auth/wx-login', {'code': code, 'nickname': '加我微信13800138000'})
    check('Y17 再次登录带联系方式的昵称：不采用（昵称不变）', '小院同学', ((b or {}).get('account') or {}).get('nickname'))
    # 上传限流
    tok, uid = app_login('y17up')
    codes = []
    for i in range(24):
        codes.append(upload('/app/upload/image', tok, 'a%d.png' % i, PNG, 'image/png').get('code'))
    check('Y17 图片上传限流：每分钟 20 张，第 21 张起 429', (20, True), (codes.count(200), 429 in codes[20:]))
    # 上传的文件名（中文 / 空格 / 括号）不能原样进路径，否则之后被「只收平台图片路径」的白名单挡掉
    tok2, uid2 = app_login('y17up2')
    up = upload('/app/upload/image', tok2, '中文 封面(1).png', PNG, 'image/png')
    url = str((up.get('data') or {}).get('url') or up.get('url') or '')
    check('Y17 中文文件名上传成功，路径是 uuid 命名（只含路径安全字符）', (200, True), (up.get('code'), bool(re.match(r'^/profile/upload/\d{4}/\d{2}/\d{2}/[0-9a-f]{32}\.png$', url))))
    status, b = http('GET', '/app/community/feed?page=107374184')
    check('Y17 社区信息流页码极大不再 500（偏移 int 溢出）', False, (b or {}).get('code') == 500)


# ================================================================ Y18 签署责任书
def y18_contract_name():
    section('Y18 签署责任书必须是报名人本人：姓名与报名时一致')
    A = CTX['A']
    tok, uid = app_login('y18')
    job, aid = to_confirmed_in(A, tok, '签署回归甲', 'y18')
    ct = data(http('GET', '/app/application/%s/contract' % aid, app_token=tok)[1]) or {}
    status, b = http('POST', '/app/application/%s/contract/sign' % aid, {'name': '冒名顶替者', 'agree': True, 'hash': ct.get('hash')}, app_token=tok)
    check('Y18 签署姓名与报名姓名不一致被拒（422）、没有签署记录', (422, 0), (code_of(b), n("select count(*) from kg_contract_sign where app_id=%s" % aid)))
    status, b = http('POST', '/app/application/%s/contract/sign' % aid, {'name': '签署 回归甲', 'agree': True, 'hash': ct.get('hash')}, app_token=tok)
    check('Y18 姓名一致（忽略空格）签署成功', (200, 'contract_signed'), (code_of(b), scalar("select status from kg_application where app_id=%s" % aid)))


# ================================================================ Y19 房源入住 / 退房时间
def y19_stay_times():
    section('Y19 房源入住 / 退房时间必须是 HH:mm')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr = u['mgr'][0]
    status, b = http('POST', '/app/staff/stay/stays?storeId=%s' % A, {'stayName': '回归时间校验-' + RUN, 'address': '古城 1 号', 'areaDesc': '古城内',
                                                                   'cover': '/profile/demo/stay.jpg', 'checkoutTime': '中午十二点'}, app_token=mgr)
    check('Y19 员工端退房时间不是 HH:mm 被拒（422）、没有落库', (422, 0), (code_of(b), n("select count(*) from kg_stay where stay_name='回归时间校验-%s'" % RUN)))
    status, b = http('POST', '/app/staff/stay/stays?storeId=%s' % A, {'stayName': '回归时间校验-' + RUN, 'address': '古城 1 号', 'areaDesc': '古城内',
                                                                   'cover': '/profile/demo/stay.jpg', 'checkinTime': '14:00', 'checkoutTime': '12:00'}, app_token=mgr)
    check('Y19 合法的 14:00 / 12:00 保存成功', 200, code_of(b))
    before = scalar("select checkout_time from kg_stay where stay_id=%s" % CTX['stay'])
    status, b = http('PUT', '/kangaroo/ext/stay', {'stayId': CTX['stay'], 'checkoutTime': '25:99'}, token=admin)
    check('Y19 后台通用编辑也不能写非法时间（库里原值不变）', (True, before), (denied(b), scalar("select checkout_time from kg_stay where stay_id=%s" % CTX['stay'])))


# ================================================================ Y20 点赞 / 收藏并发
def y20_like_toggle():
    section('Y20 点赞 / 收藏并发切换：不死锁（无 500）、计数与明细一致')
    a_tok, a_id, b_tok, b_id = CTX['y2']
    pid = CTX['y2_post']
    res = parallel(lambda i: http('POST', '/app/community/posts/%s/like' % pid, None, app_token=(a_tok if i % 2 == 0 else b_tok))[1], range(16), 8)
    check('Y20 两个人各连点 8 次点赞：没有 500', [], [x.get('msg') for x in res if x.get('code') == 500])
    likes = int(scalar("select likes from kg_post where post_id=%s" % pid))
    rows = n("select count(*) from kg_post_like where post_id=%s" % pid)
    check('Y20 点赞数 = 点赞明细条数', rows, likes)
    res = parallel(lambda i: http('POST', '/app/community/posts/%s/favorite' % pid, None, app_token=(a_tok if i % 2 == 0 else b_tok))[1], range(16), 8)
    favs = int(scalar("select favorites from kg_post where post_id=%s" % pid))
    frows = n("select count(*) from kg_favorite where target_type='post' and target_id=%s" % pid)
    check('Y20 收藏：没有 500、收藏数 = 明细条数', ([], frows), ([x.get('msg') for x in res if x.get('code') == 500], favs))
    # 活动收藏（此前是先删后插，与点赞同一类死锁）
    act = n("select activity_id from kg_activity where del_flag='0' order by activity_id desc limit 1")
    res = parallel(lambda i: http('POST', '/app/activities/%s/favorite' % act, None, app_token=(a_tok if i % 2 == 0 else b_tok))[1], range(16), 8)
    arows = n("select count(*) from kg_favorite where target_type='activity' and target_id=%s and app_user_id in (%s,%s)" % (act, a_id, b_id))
    check('Y20 活动收藏两人各连点 8 次（偶数次）：没有 500、最后都是未收藏', ([], 0), ([x.get('msg') for x in res if x.get('code') == 500], arows))


# ================================================================ Y21 操作日志范围 + 任务自验
def y21_logs_and_tasks():
    section('Y21 员工操作日志：管家只看本人；门店任务不能自己验收、实际工时不超过时间窗')
    A, u = CTX['A'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    status, b = http('GET', '/app/staff/team/logs?storeId=%s' % A, app_token=u['keeper'][0])
    check('Y21 管家的操作日志范围是「仅本人」', '仅本人', (data(b) or {}).get('scope'))
    status, b = http('GET', '/app/staff/team/logs?storeId=%s' % A, app_token=mgr_tok)
    check('Y21 主理人的操作日志范围是「本店全部员工」', '本店全部员工', (data(b) or {}).get('scope'))

    def save(tok, **kw):
        body = {'storeId': A, 'title': '回归验收任务-' + RUN, 'taskDate': day(0), 'startTime': '00:00', 'endTime': '23:59', 'estHours': 1,
                'assignMode': 'staff', 'assignees': [mgr_id], 'quota': 1, 'publish': True}
        body.update(kw)
        return http('POST', '/app/manage/tasks', body, app_token=tok)[1]

    b = save(mgr_tok)
    tid = (data(b) or {}).get('taskId')
    check('Y21 主理人把任务指派给自己', (200, True), (code_of(b), bool(tid)))
    http('POST', '/app/manage/tasks/%s/claim' % tid, {}, app_token=mgr_tok)
    status, b = http('POST', '/app/manage/tasks/%s/check' % tid, {'userId': mgr_id, 'hours': 1, 'rating': 5}, app_token=mgr_tok)
    check('Y21 自己执行的任务不能自己验收（403）、没有发积分', (403, 0),
          (code_of(b), n("select count(*) from kg_point_entry where app_user_id=%s and biz_type='task_assignee'" % mgr_id)))
    # 工时不超时间窗：时间窗 1 小时，另一人执行，填 10 小时被拒
    st_tok, st_id = u['staff']
    # 时间窗要在认领之后才收窄：认领校验「任务已结束」，而验收校验「任务已开始」，黑盒里同一个窗口不能同时满足，所以认领后直接改库
    b = save(mgr_tok, title='回归工时任务-' + RUN, assignees=[st_id], startTime='00:00', endTime='23:59')
    tid2 = (data(b) or {}).get('taskId')
    http('POST', '/app/manage/tasks/%s/claim' % tid2, {}, app_token=st_tok)
    sql_exec("update kg_task set start_time='00:00', end_time='01:00' where task_id=%s" % tid2)
    status, b = http('POST', '/app/manage/tasks/%s/check' % tid2, {'userId': st_id, 'hours': 10, 'rating': 5}, app_token=mgr_tok)
    check('Y21 时间窗 1 小时的任务填 10 小时被拒（422）', (422, 'accepted'),
          (code_of(b), scalar("select status from kg_task_assignee where task_id=%s and app_user_id=%s" % (tid2, st_id))))
    status, b = http('POST', '/app/manage/tasks/%s/check' % tid2, {'userId': st_id, 'hours': 1.5, 'rating': 5}, app_token=mgr_tok)
    check('Y21 时间窗内（+1 小时机动）的工时验收成功', (200, 'done'), (code_of(b), scalar("select status from kg_task_assignee where task_id=%s and app_user_id=%s" % (tid2, st_id))))


# ================================================================ Y22 确认超时
def y22_confirm_timeout():
    section('Y22 用户已确认愿意前往、商家超过期限没确认：系统自动终止并通知双方')
    A, u = CTX['A'], CTX['u']
    tok, uid = u['worker'][0], u['worker'][1]
    admin = CTX['admin']
    job = m.new_job(A, '回归确认超时岗位-' + RUN, 'volunteer', 2)
    aid = m.apply(job, tok, '接单人甲', 'y22')
    http('PUT', '/kangaroo/application/audit', {'appId': aid, 'pass': True}, token=admin)
    m.new_slot(A)
    slots = data(http('GET', '/app/application/%s/slots' % aid, app_token=tok)[1]) or []
    http('POST', '/app/application/%s/book-interview' % aid, {'slotId': slots[0]['slotId']}, app_token=tok)
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(aid)
    http('POST', '/app/application/%s/finish-interview' % aid, {}, app_token=tok)
    http('POST', '/app/application/%s/confirm' % aid, {'intent': 'accept'}, app_token=tok)
    check('Y22 准备：用户已确认、确认期限还没到（不会被提前终止）', ('user_confirmed', True),
          (scalar("select status from kg_application where app_id=%s" % aid), scalar("select confirm_deadline > now() from kg_application where app_id=%s" % aid) == '1'))
    before_msgs = (msgs(uid, '报名已终止'), msgs(u['mgr'][1], '确认录用已超时'))
    time.sleep(4)
    check('Y22 期限没到：扫描之后状态不变', 'user_confirmed', scalar("select status from kg_application where app_id=%s" % aid))
    sql_exec("update kg_application set confirm_deadline = date_sub(now(), interval 1 hour) where app_id=%s" % aid)
    st = None
    for _ in range(12):
        time.sleep(1.5)
        st = scalar("select status from kg_application where app_id=%s" % aid)
        if st == 'terminated':
            break
    check('Y22 期限已过：定时任务自动终止（terminated）、写了终止原因', ('terminated', True),
          (st, '系统自动终止' in str(scalar("select terminate_reason from kg_application where app_id=%s" % aid))))
    check('Y22 审计日志里操作方是系统', 'system', scalar("select operator_type from kg_application_log where app_id=%s order by log_id desc limit 1" % aid))
    check('Y22 用户收到「报名已终止」、门店主理人收到「确认录用已超时」（各 +1）', (1, 1),
          (msgs(uid, '报名已终止') - before_msgs[0], msgs(u['mgr'][1], '确认录用已超时') - before_msgs[1]))
    check('Y22 没占过录用名额（hired_count 仍为 0）', '0', scalar("select hired_count from kg_job where job_id=%s" % job))
    # 被终止的报名（没入职）不能因此认领本店义工任务
    body = {'storeId': A, 'title': '回归义工资格-' + RUN, 'taskDate': day(1), 'startTime': '10:00', 'endTime': '12:00', 'estHours': 2,
            'assignMode': 'volunteer', 'assignees': [], 'quota': 1, 'publish': True}
    status, b = http('POST', '/app/manage/tasks', body, app_token=u['mgr'][0])
    tid = (data(b) or {}).get('taskId')
    status, b = http('POST', '/app/manage/tasks/%s/claim' % tid, {}, app_token=tok)
    check('Y22 报名被终止（没入职过）的人不能认领本店义工任务（403）', (403, 0),
          (code_of(b), n("select count(*) from kg_task_assignee where task_id=%s and app_user_id=%s" % (tid, uid))))


# ================================================================ Y23 下架房源改内容后不能直接恢复上线
def new_online_stay(tag):
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr = u['mgr'][0]
    name = '回归下架改内容-%s-%s' % (tag, RUN)
    http('POST', '/app/staff/stay/stays?storeId=%s' % A, {'stayName': name, 'address': '大理古城回归路 2 号', 'areaDesc': '古城内', 'cover': '/profile/demo/stay.jpg'}, app_token=mgr)
    sid = n("select stay_id from kg_stay where store_id=%s and stay_name='%s'" % (A, name))
    http('POST', '/app/staff/stay/stays/%s/room-types' % sid, {'typeName': '回归房', 'roomKind': 'private', 'capacity': 2, 'stock': 2, 'basePriceFen': 15000}, app_token=mgr)
    for qt in ('license', 'fire', 'idcard'):
        upload('/app/staff/stay/stays/%s/qualifications?type=%s&expire=2030-12-31' % (sid, qt), mgr, qt + '.pdf', b'%PDF-1.4 regression')
    http('POST', '/app/staff/stay/stays/%s/submit' % sid, {}, app_token=mgr)
    http('PUT', '/kangaroo/stayops/stay/%s/approve' % sid, token=admin)
    return sid


def y23_offline_edit():
    section('Y23 下架的房源被改了内容：退回草稿、必须重新审核；没改内容的后台可以直接恢复')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr = u['mgr'][0]
    s1 = new_online_stay('a')
    check('Y23 准备：新房源审核上线', 'online', scalar("select status from kg_stay where stay_id=%s" % s1))
    status, b = http('POST', '/app/staff/stay/stays/%s/offline?storeId=%s' % (s1, A), {'reason': '回归：暂停接待'}, app_token=mgr)
    check('Y23 员工把房源下架', (200, 'offline'), (code_of(b), scalar("select status from kg_stay where stay_id=%s" % s1)))
    status, b = http('PUT', '/app/staff/stay/stays/%s?storeId=%s' % (s1, A), {'slogan': '下架期间改了文案'}, app_token=mgr)
    check('Y23 下架期间改了内容 → 退回草稿（此前仍是 offline）', (200, 'draft'), (code_of(b), scalar("select status from kg_stay where stay_id=%s" % s1)))
    status, b = http('PUT', '/kangaroo/stayops/stay/%s/online' % s1, token=admin)
    check('Y23 后台「恢复上线」对改过内容的房源不可用（状态已不是 offline），新内容没有绕过审核', (True, 'draft'), (denied(b), scalar("select status from kg_stay where stay_id=%s" % s1)))
    s2 = new_online_stay('b')
    status, b = http('PUT', '/kangaroo/stayops/stay/%s/offline' % s2, {'reason': '回归：临时下架'}, token=admin)
    status, b = http('PUT', '/kangaroo/stayops/stay/%s/online' % s2, token=admin)
    check('Y23 内容没动的下架房源，后台恢复上线成功', (200, 'online'), (code_of(b), scalar("select status from kg_stay where stay_id=%s" % s2)))


# ================================================================ Y24 看板转化率口径
def y24_dashboard_conversion():
    section('Y24 看板「报名转化率」：只算到过「商家确认录用」的（用户已确认 / 审核后就终止的不算）')
    A, u = CTX['A'], CTX['u']
    f, t = day(-6), day(1)
    where = "store_id=%s and del_flag='0' and submit_time>='%s' and submit_time<'%s'" % (A, f, t)
    total = n("select count(*) from kg_application where " + where)
    conf = n("select count(*) from kg_application a where a.store_id=%s and a.del_flag='0' and a.submit_time>='%s' and a.submit_time<'%s' and "
             "(a.status in ('store_confirmed','contract_signed','onboarded') or (a.status='terminated' and exists "
             "(select 1 from kg_application_log l where l.app_id=a.app_id and l.to_status='store_confirmed')))" % (A, f, t))
    old = n("select count(*) from kg_application where %s and status in ('store_confirmed','user_confirmed','contract_signed','onboarded','terminated')" % where)
    check('Y24 准备：本店近 7 天有报名，且新旧口径算出来不一样（有用户已确认 / 直接终止的报名）', (True, True), (total > 0, old != conf))
    status, b = http('GET', '/app/manage/dashboard/overview?period=7', app_token=u['mgr'][0])
    ms = (data(b) or {}).get('metrics') or []
    got = [x.get('value') for x in ms]
    expect = m.pct(m.java_round(conf * 10000.0 / total)) if total else '—'
    check('Y24 转化率 = 到过「商家确认录用」及之后 ÷ 提交数（库里独立算）', expect, got[2] if len(got) > 2 else None)


# ================================================================ Y25 线上任务：截止时间、结算申诉
def y25_online_deadline_dispute():
    section('Y25 线上任务：过了截止时间不再接单；门店验收后 7 天不付款，接单人可以申诉')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    body = {'title': '回归截止任务-' + RUN, 'taskType': '内容写作', 'deliverables': '300 字', 'criteria': '原创', 'description': '回归', 'commissionFen': 2000, 'quota': 3,
            'deadline': time.strftime('%Y-%m-%d %H:%M', time.localtime(time.time() + 3 * 86400))}
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % A, body, app_token=mgr_tok)
    jid = int((data(b) or {}).get('jobId') or 0)
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=admin)
    w_tok, w_id = u['worker']
    app1 = m.apply(jid, w_tok, '接单人甲', 'y25a')
    status, b = http('POST', '/app/staff/applications/%s/audit' % app1, {'pass': True}, app_token=u['staff'][0])
    check('Y25 普通员工不能审核正常任务，状态不变', (403, 'submitted'), (code_of(b), scalar("select status from kg_application where app_id=%s" % app1)))
    status, b = http('POST', '/app/staff/applications/%s/audit' % app1, {'pass': True}, app_token=mgr_tok)
    check('Y25 主理人可以审核正常任务', 200, code_of(b))
    http('POST', '/app/online/assignments/%s/submit' % app1, {'link': 'https://example.com/y25'}, app_token=w_tok)
    status, b = http('POST', '/app/staff/online/assignments/%s/accept' % app1, {'comment': '通过'}, app_token=mgr_tok)
    check('Y25 准备：验收通过，生成待门店付款的结算单', 'pending_payout', scalar("select status from kg_online_settlement where app_id=%s" % app1))
    status, b = http('POST', '/app/online/assignments/%s/confirm' % app1, {'received': False, 'reason': '回归：门店一直没付款'}, app_token=w_tok)
    check('Y25 验收不足 7 天：不能申诉（409）', (409, 'pending_payout'), (code_of(b), scalar("select status from kg_online_settlement where app_id=%s" % app1)))
    sql_exec("update kg_online_settlement set create_time = date_sub(now(), interval 8 day) where app_id=%s" % app1)
    status, b = http('POST', '/app/online/assignments/%s/confirm' % app1, {'received': False, 'reason': '回归：门店一直没付款'}, app_token=w_tok)
    check('Y25 超过 7 天还没付款：可以申诉 → disputed、生成一条平台申报', (200, 'disputed', 1),
          (code_of(b), scalar("select status from kg_online_settlement where app_id=%s" % app1),
           n("select count(*) from kg_appeal where app_user_id=%s and biz_type='online_settlement' and biz_id=%s" % (w_id, app1))))
    # 截止时间
    w2_tok, w2_id = u['worker2']
    app2 = m.apply(jid, w2_tok, '接单人乙', 'y25b')
    sql_exec("update kg_online_task set deadline_time = date_sub(now(), interval 1 hour) where job_id=%s" % jid)
    status, b = http('POST', '/app/staff/applications/%s/audit' % app2, {'pass': True}, app_token=u['staff'][0])
    check('Y25 普通员工仍先被角色拦截，状态不变', (403, 'submitted'), (code_of(b), scalar("select status from kg_application where app_id=%s" % app2)))
    status, b = http('POST', '/app/staff/applications/%s/audit' % app2, {'pass': True}, app_token=mgr_tok)
    check('Y25 任务过了截止时间：已提交的接单申请不能再审核通过（409）', (409, 'submitted'), (code_of(b), scalar("select status from kg_application where app_id=%s" % app2)))
    tok3, id3 = app_login('y25c')
    status, b = http('POST', '/app/application', {'jobId': jid, 'name': '接单人丙', 'age': 24, 'phone': '13500135000', 'arrival': day(2), 'days': 1,
                                                  'idemKey': 'y25c-' + RUN}, app_token=tok3)
    check('Y25 任务过了截止时间：新的接单报名被拒、没有落库', (True, 0), (denied(b), n("select count(*) from kg_application where app_user_id=%s" % id3)))


def main():
    admin = admin_login()
    print('测试运行标识 RUN=%s（社区用例标识 %s）' % (RUN, c.TAG))
    m.m0_setup(admin)
    for name, fn in (('Y1', y1_insurance), ('Y2', y2_comment_counter), ('Y3', y3_stay_reviews), ('Y4', y4_review_passed_terminate),
                     ('Y5', y5_closed_job_confirm), ('Y6', y6_exception_handler), ('Y7', y7_activity), ('Y8', y8_delete_guard),
                     ('Y9', y9_store_fields), ('Y10', y10_review_rereport), ('Y11', y11_reschedule_release), ('Y12', y12_todo_refund_route),
                     ('Y13', y13_disabled_store), ('Y14', y14_notify_and_urgent), ('Y15', y15_online_task), ('Y16', y16_share_stats),
                     ('Y17', y17_validation), ('Y18', y18_contract_name), ('Y19', y19_stay_times), ('Y20', y20_like_toggle),
                     ('Y21', y21_logs_and_tasks), ('Y22', y22_confirm_timeout), ('Y23', y23_offline_edit),
                     ('Y24', y24_dashboard_conversion), ('Y25', y25_online_deadline_dispute)):
        guard(name, fn)
    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    print('\n测试数据标识 RUN=%s' % RUN)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
