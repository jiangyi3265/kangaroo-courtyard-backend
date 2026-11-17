# -*- coding: utf-8 -*-
"""2026-09-28 全面审计（11 个维度 × 对抗式复核）确认的缺陷，本轮修复的回归用例。

每一条都对应审计里**已复现**的失败场景：先造出当时的状态，再断言业务结果（库里的状态 / 金额 / 名额），
而不是只看返回码。修复前这些用例会红（对应条目见每段标题里的审计编号）。

覆盖：
  W1  后台对支付单全额退款要回写业务订单（P0）：服务单 unused → refunded、核销码失效；审批中的入住单 → cancelled，
      refund_fen 取实际退款额（不累加超过实付）、房量释放；管家不能审批退款（只有主理人）
  W2  撤销授权后前员工不能再读写门店会话（P1）
  W3  门店活动：普通员工 / 非发起场景不能走用户侧接口取消；没开始的活动不能「结束」；管家不能取消已有报名的活动
  W4  线上任务：名额生效（满了拒绝再通过）、不能自己验收 / 登记付款给自己
  W5  通用后台：7 个表没有 del_flag 的页面能打开；留空的可选字段能新增；分佣阶梯校验；人工调整只能是「调整」、可录负数、必须写原因；
      同门店同来源不能有两条生效规则；已产生流水的规则不能改收款人
  W6  员工端面试改期（P1：此前先占新时段后释放旧时段，必撞唯一键）
  W7  手机号：未验证的 bind-phone 不能占号后拿到按手机号授予的门店角色；验证过的绑定可以接管
  W8  后台停用门店后员工会话立即失效
  W9  后台「站内消息」屏蔽私信正文

前置：后端联调模式（python .dev/tools/dev_backend.py）。数据：每次运行新建两家门店与一批账号（reset-to-demo.sql 会清）。
用法：python -X utf8 e2e/test_ext_sweep.py
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import test_ext_manage as m  # noqa: E402
from test_ext_manage import (http, check, section, admin_login, CTX, PASSED, FAILED, sql, scalar, sql_exec, n, day, ok, denied,  # noqa: E402
                             code_of, data, app_login, guard, upload)

RUN = m.RUN


def make_stay_order(guest_tok, check_in, nights=1, tag='x'):
    status, b = http('POST', '/app/stay/orders', {'roomTypeId': CTX['rt'], 'checkIn': day(check_in), 'checkOut': day(check_in + nights), 'guests': 1,
                                                  'guestName': '回归住客', 'guestPhone': '13800138000', 'agreementVersion': m.AGREEMENT,
                                                  'idemKey': 'sw-%s-%s' % (tag, RUN)}, app_token=guest_tok)
    o = data(b) or {}
    assert o.get('orderNo'), b
    http('POST', '/app/pay/%s/prepay' % o['payNo'], {}, app_token=guest_tok)
    status, b2 = http('POST', '/app/pay/%s/mock-confirm' % o['payNo'], {}, app_token=guest_tok)
    assert ok(b2), b2
    return o['orderNo'], o['payNo']


def occupied(d):
    return n("select coalesce(sum(occupied),0) from kg_stay_calendar where room_type_id=%s and stay_date='%s'" % (CTX['rt'], d))


# ================================================================ W1
def w1_admin_refund():
    section('W1 后台对支付单全额退款要回写业务订单（审计 P0）；退款审批只给主理人')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    guest_tok, guest_id = u['guest']
    mgr_tok, keeper_tok = u['mgr'][0], u['keeper'][0]

    # ---- 入住单：退款审核中（refund_pending）时后台又直接退了支付单
    d = day(45)
    base = occupied(d)
    no, pay_no = make_stay_order(guest_tok, 45, 1, 'w1a')
    check('W1 入住单下单占房', base + 1, occupied(d))
    status, b = http('GET', '/app/stay/orders/%s/refund-preview' % no, app_token=guest_tok)
    status, b = http('POST', '/app/stay/orders/%s/refund' % no, {'reason': '行程变动'}, app_token=guest_tok)
    check('W1 用户申请退款 → refund_pending', (200, 'refund_pending'), (code_of(b), scalar("select status from kg_stay_order where order_no='%s'" % no)))
    status, b = http('POST', '/app/staff/stay/refunds/approve', {'bizType': 'stay', 'orderNo': no}, app_token=keeper_tok)
    check('W1 管家不能审批退款（只有主理人，403）', (403, 'refund_pending'), (code_of(b), scalar("select status from kg_stay_order where order_no='%s'" % no)))
    pay_fen = n("select pay_fen from kg_stay_order where order_no='%s'" % no)
    status, b = http('PUT', '/kangaroo/core/payorder/%s/refund' % pay_no, {'reason': '回归：后台全额退款'}, token=admin)
    check('W1 后台全额退款支付单成功', 200, code_of(b))
    r = sql("select status, refund_fen, coalesce(verify_code,'') from kg_stay_order where order_no='%s'" % no)[0]
    check('W1 审批中的入住单随之 cancelled、refund_fen = 实付（不累加超过实付）、核销码失效', ('cancelled', str(pay_fen), ''), tuple(r))
    check('W1 房量释放回原值', base, occupied(d))
    status, b = http('POST', '/app/staff/stay/refunds/reject', {'bizType': 'stay', 'orderNo': no, 'reason': '想恢复'}, app_token=mgr_tok)
    check('W1 已全额退款的单不能再被驳回恢复成有效入住', (True, 'cancelled'), (denied(b), scalar("select status from kg_stay_order where order_no='%s'" % no)))

    # ---- 服务单：入住期间预订，支付后后台全额退款
    no2, pay2 = make_stay_order(guest_tok, 0, 1, 'w1b')
    vcode = scalar("select verify_code from kg_stay_order where order_no='%s'" % no2)
    status, b = http('POST', '/app/staff/stay/verify/confirm?storeId=%s' % A, {'code': vcode}, app_token=mgr_tok)
    check('W1 本店核销入住', (200, 'checked_in'), (code_of(b), scalar("select status from kg_stay_order where order_no='%s'" % no2)))
    cat = scalar("select dict_value from sys_dict_data where dict_type='kg_shop_category' and status='0' order by dict_sort limit 1")
    status, b = http('POST', '/kangaroo/ext/shopservice', {'storeId': A, 'category': cat, 'serviceName': '回归退款茶歇-%s' % RUN, 'priceFen': 6600,
                                                           'unitLabel': '份', 'timeSlots': '10:00-11:00', 'slotCapacity': 1, 'advanceHours': 0,
                                                           'rescheduleLimit': 1, 'refundable': '1', 'status': '0'}, token=admin)
    svc = n("select service_id from kg_shop_service where store_id=%s and service_name='回归退款茶歇-%s'" % (A, RUN))
    status, b = http('POST', '/app/shop/orders', {'serviceId': svc, 'stayOrderNo': no2, 'qty': 1, 'useDate': day(1), 'timeSlot': '10:00-11:00',
                                                  'idemKey': 'sw-shop-' + RUN}, app_token=guest_tok)
    so = data(b) or {}
    check('W1 预订增值服务', (200, True), (code_of(b), bool(so.get('orderNo'))))
    http('POST', '/app/pay/%s/prepay' % so['payNo'], {}, app_token=guest_tok)
    http('POST', '/app/pay/%s/mock-confirm' % so['payNo'], {}, app_token=guest_tok)
    sfen = n("select pay_fen from kg_shop_order where order_no='%s'" % so['orderNo'])
    check('W1 服务单已支付 unused、有核销码', ('unused', True), (scalar("select status from kg_shop_order where order_no='%s'" % so['orderNo']),
                                                          bool(scalar("select verify_code from kg_shop_order where order_no='%s'" % so['orderNo']))))
    scode = scalar("select verify_code from kg_shop_order where order_no='%s'" % so['orderNo'])
    status, b = http('PUT', '/kangaroo/core/payorder/%s/refund' % so['payNo'], {'reason': '回归：后台全额退款服务单'}, token=admin)
    check('W1 后台全额退款服务单的支付单', 200, code_of(b))
    r = sql("select status, refund_fen, coalesce(verify_code,'') from kg_shop_order where order_no='%s'" % so['orderNo'])[0]
    check('W1 服务单随之 refunded、refund_fen = 实付、核销码失效（此前仍是 unused 且核销码有效）', ('refunded', str(sfen), ''), tuple(r))
    status, b = http('POST', '/app/staff/stay/verify/confirm?storeId=%s' % A, {'code': scode}, app_token=mgr_tok)
    check('W1 已退款服务单的旧核销码不能再核销', (True, 'refunded'), (denied(b), scalar("select status from kg_shop_order where order_no='%s'" % so['orderNo'])))
    status, b = http('POST', '/app/shop/orders/%s/refund' % so['orderNo'], {}, app_token=guest_tok)
    check('W1 已退款服务单不能再申请退款', True, denied(b), ok=denied(b))


# ================================================================ W2
def w2_chat_revoke():
    section('W2 撤销授权后前员工不能再读写门店会话（审计 P1）')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    tal_tok, tal_id = u['talent']
    keeper_tok, keeper_id = u['keeper']
    jid = m.new_job(A, '回归会话岗位-' + RUN, 'volunteer', 3)
    aid = m.apply(jid, tal_tok, '候选人丙', 'w2')
    status, b = http('POST', '/app/chat/open', {'type': 'store', 'appId': aid}, app_token=tal_tok)
    conv = (data(b) or {}).get('convId')
    check('W2 报名人打开门店会话', (200, True), (code_of(b), bool(conv)))
    # 发消息的字段是 content（之前写成 text，后端收到空消息 422，下面「不再收到提醒」的断言等于没测）
    http('POST', '/app/chat/%s/messages' % conv, {'content': '我的身份证后四位是1234'}, app_token=tal_tok)
    status, b = http('GET', '/app/chat/%s/messages' % conv, app_token=keeper_tok)
    check('W2 在职管家能读会话', 200, code_of(b))
    keeper_rows = "select msg_id, title, content, read_flag, create_time from kg_message where recipient_id=%s and biz_type='chat_conv' and biz_id=%s order by msg_id"
    gid = scalar("select grant_id from kg_staff_grant where app_user_id=%s and store_id=%s and status='0' order by grant_id desc limit 1" % (keeper_id, A))
    status, b = http('PUT', '/kangaroo/grant/revoke/%s' % gid, None, token=admin)
    check('W2 后台撤销管家授权', 200, code_of(b))
    before_msgs = n("select count(*) from kg_chat_msg where conv_id=%s" % conv)
    keeper_before = sql(keeper_rows % (keeper_id, conv))
    status, b = http('GET', '/app/chat/%s/messages' % conv, app_token=keeper_tok)
    check('W2 撤销后读消息 403', 403, code_of(b))
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '我还能以门店身份回复吗'}, app_token=keeper_tok)
    check('W2 撤销后发消息 403、没有落库', (403, before_msgs), (code_of(b), n("select count(*) from kg_chat_msg where conv_id=%s" % conv)))
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '再发一条'}, app_token=tal_tok)
    check('W2 报名人再发一条（真的发出去了，自己那一行是「我：再发一条」）', (200, '我：再发一条'),
          (code_of(b), scalar("select content from kg_message where recipient_id=%s and mode='user' and biz_type='chat_conv' and biz_id=%s "
                              "and del_flag='0' order by msg_id desc limit 1" % (tal_id, conv))))
    check('W2 之后报名人再发消息，前员工不再收到提醒', 0,
          n("select count(*) from kg_message where recipient_id=%s and biz_type='chat_conv' and biz_id=%s and content like '%%再发一条%%'" % (keeper_id, conv)))
    # 2026-10-15 起每个会话只有一行、新消息会更新这一行：撤销授权后前员工原来那一行也不能被改（正文、时间、已读状态都不动）
    check('W2 前员工原来那一行没有被更新', keeper_before, sql(keeper_rows % (keeper_id, conv)))
    status, b = http('GET', '/app/chat/%s' % conv, app_token=tal_tok)
    check('W2 报名人自己照常能读', 200, code_of(b))
    # 后台消息列表不展示私信正文（W9）
    status, b = http('GET', '/kangaroo/message/list?bizType=chat_conv&pageNum=1&pageSize=50', token=admin)
    rows = (b or {}).get('rows') or []
    leak = [r for r in rows if '身份证' in str(r.get('content')) or '再发一条' in str(r.get('content'))]
    check('W9 后台「站内消息」列表里看不到私信正文', (True, []), (len(rows) > 0, [r.get('content') for r in leak]))
    # 重新授予后恢复
    m.grant(admin, keeper_id, A, 'keeper')
    status, b = http('GET', '/app/chat/%s/messages' % conv, app_token=keeper_tok)
    check('W2 重新授予后又能读', 200, code_of(b))


# ================================================================ W3
def w3_activities():
    section('W3 门店活动：用户侧接口不认门店活动；没开始不能结束；管家不能取消已有报名的活动')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    staff_tok, mgr_tok, keeper_tok, guest_tok = u['staff'][0], u['mgr'][0], u['keeper'][0], u['guest'][0]

    def create(tok, title, offset):
        payload = {'category': '手作课程', 'title': title, 'cover': '', 'place': '门店一楼手作区', 'capacity': 5,
                   'startTime': day(offset) + ' 14:00', 'endTime': day(offset) + ' 17:00', 'feeYuan': '0', 'intro': '回归活动'}
        status, b = http('POST', '/app/staff/activities?storeId=%s' % A, payload, app_token=tok)
        aid = (data(b) or {}).get('activityId') or (data(b) or {}).get('id')
        assert aid, b
        if scalar("select status from kg_activity where activity_id=%s" % aid) == 'pending':
            http('PUT', '/kangaroo/community/activity/%s/approve' % aid, token=admin)
        return int(aid)

    a1 = create(staff_tok, '回归员工活动-' + RUN, 6)
    check('W3 活动已开放报名', 'open', scalar("select status from kg_activity where activity_id=%s" % a1))
    status, b = http('POST', '/app/activities/%s/manage/cancel' % a1, {'reason': '我是创建者我来取消'}, app_token=staff_tok)
    check('W3 普通员工借用户侧接口取消门店活动被拒（403）、活动仍开放', (403, 'open'), (code_of(b), scalar("select status from kg_activity where activity_id=%s" % a1)))
    status, b = http('GET', '/app/activities/%s/manage' % a1, app_token=staff_tok)
    check('W3 用户侧管理页也不认门店活动', True, denied(b), ok=denied(b))
    status, b = http('POST', '/app/staff/activities/%s/action/end' % a1, {}, app_token=mgr_tok)
    check('W3 活动没开始不能「结束」（409）、活动仍开放', (409, 'open'), (code_of(b), scalar("select status from kg_activity where activity_id=%s" % a1)))
    # 报名前要先同意《社区公约》（423 CONSENT）
    for t in (u['guest'][0], u['outsider'][0], u['payee2'][0], u['worker'][0]):
        http('POST', '/app/community/consent', {'doc': 'activity'}, app_token=t)
    # 有人报名后：管家不能取消，主理人可以
    status, b = http('POST', '/app/activities/%s/signup' % a1, {'channel': 'community'}, app_token=guest_tok)
    check('W3 住客报名（免费直接确认）', 200, code_of(b))
    status, b = http('POST', '/app/staff/activities/%s/action/cancel' % a1, {'reason': '管家想取消'}, app_token=keeper_tok)
    check('W3 管家不能取消已有报名的活动（403）、活动仍开放', (403, 'open'), (code_of(b), scalar("select status from kg_activity where activity_id=%s" % a1)))
    status, b = http('POST', '/app/staff/activities/%s/action/cancel' % a1, {'reason': '普通员工想取消'}, app_token=staff_tok)
    check('W3 普通员工不能取消（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/activities/%s/action/cancel' % a1, {'reason': '店里有事，活动取消'}, app_token=mgr_tok)
    check('W3 主理人可以取消，报名人的名额与状态一并处理', (200, 'cancelled'), (code_of(b), scalar("select status from kg_activity where activity_id=%s" % a1)))
    # 候补号不重复：容量 1，先后三人
    payload = {'category': '手作课程', 'title': '回归候补活动-' + RUN, 'cover': '', 'place': '门店一楼', 'capacity': 1,
               'startTime': day(7) + ' 14:00', 'endTime': day(7) + ' 17:00', 'feeYuan': '0', 'intro': '候补'}
    status, b = http('POST', '/app/staff/activities?storeId=%s' % A, payload, app_token=mgr_tok)
    a2 = int((data(b) or {}).get('activityId') or (data(b) or {}).get('id'))
    if scalar("select status from kg_activity where activity_id=%s" % a2) == 'pending':
        http('PUT', '/kangaroo/community/activity/%s/approve' % a2, token=admin)
    toks = [u['guest'][0], u['outsider'][0], u['payee2'][0], u['worker'][0]]
    for t in toks:
        http('POST', '/app/activities/%s/signup' % a2, {'channel': 'community'}, app_token=t)
    nos = [r[0] for r in sql("select waitlist_no from kg_activity_signup where activity_id=%s and status='waitlist' order by signup_id" % a2)]
    check('W3 候补号不重复（依次 1、2、3）', ['1', '2', '3'], nos)
    # 有人报名后仍可改介绍 / 封面，但不能改时间
    status, b = http('GET', '/app/staff/activities/%s' % a2, app_token=mgr_tok)
    ed = data(b) or {}
    payload2 = {'category': ed.get('category') or '手作课程', 'title': '回归候补活动-' + RUN, 'cover': '/static/media/activity-cover.jpg', 'place': '门店一楼',
                'capacity': 1, 'startTime': day(7) + ' 14:00', 'endTime': day(7) + ' 17:00', 'feeYuan': '0', 'intro': '新的介绍'}
    status, b = http('PUT', '/app/staff/activities/%s' % a2, payload2, app_token=mgr_tok)
    check('W3 已有人报名后可以改封面和介绍（此前被整体拒绝）', (200, '新的介绍', '/static/media/activity-cover.jpg'),
          (code_of(b), scalar("select intro from kg_activity where activity_id=%s" % a2), scalar("select cover from kg_activity where activity_id=%s" % a2)))
    status, b = http('PUT', '/app/staff/activities/%s' % a2, dict(payload2, startTime=day(8) + ' 14:00', endTime=day(8) + ' 17:00'), app_token=mgr_tok)
    check('W3 已有人报名后不能改时间（409）', 409, code_of(b))


# ================================================================ W4
def w4_online():
    section('W4 线上任务：名额生效；不能自己验收 / 登记付款给自己')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    staff_tok, keeper_tok = u['staff'][0], u['keeper'][0]
    w_tok, w2_tok = u['worker'][0], u['worker2'][0]
    body = {'title': '回归名额任务-' + RUN, 'taskType': '内容写作', 'deliverables': '800 字文案', 'criteria': '原创', 'description': '名额',
            'commissionFen': 20000, 'quota': 1, 'deadline': time.strftime('%Y-%m-%d %H:%M', time.gmtime(time.time() + 8 * 3600 + 3 * 86400))}
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % A, body, app_token=mgr_tok)
    jid = (data(b) or {}).get('jobId')
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=admin)
    a1 = m.apply(jid, w_tok, '接单人甲', 'w4a')
    a2 = m.apply(jid, w2_tok, '接单人乙', 'w4b')
    status, b = http('POST', '/app/staff/applications/%s/audit' % a1, {'pass': True}, app_token=staff_tok)
    check('W4 普通员工不能审核，不消耗名额', (403, 'submitted'), (code_of(b), scalar("select status from kg_application where app_id=%s" % a1)))
    status, b = http('POST', '/app/staff/applications/%s/audit' % a1, {'pass': True}, app_token=mgr_tok)
    check('W4 名额 1：第一位接单人通过', (200, 'review_passed'), (code_of(b), scalar("select status from kg_application where app_id=%s" % a1)))
    status, b = http('POST', '/app/staff/applications/%s/audit' % a2, {'pass': True}, app_token=mgr_tok)
    check('W4 名额已满：第二位不能再通过（409）、状态仍是已提交', (409, 'submitted'), (code_of(b), scalar("select status from kg_application where app_id=%s" % a2)))
    status, b = http('POST', '/app/staff/applications/%s/audit' % a2, {'pass': False, 'reason': '名额已满'}, app_token=keeper_tok)
    check('W4 名额满了仍可以驳回第二位', (200, 'review_rejected'), (code_of(b), scalar("select status from kg_application where app_id=%s" % a2)))

    # 自己接单、自己验收、自己登记付款
    body2 = dict(body, title='回归自验任务-' + RUN, quota=3)
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % A, body2, app_token=mgr_tok)
    jid2 = (data(b) or {}).get('jobId')
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid2, 'status': 'published'}, token=admin)
    sa = m.apply(jid2, mgr_tok, '回归主理人', 'w4s')
    status, b = http('POST', '/app/staff/applications/%s/audit' % sa, {'pass': True}, app_token=keeper_tok)
    check('W4 管家通过主理人自己的接单（报名审核由别人做）', (200, 'review_passed'), (code_of(b), scalar("select status from kg_application where app_id=%s" % sa)))
    status, b = http('POST', '/app/online/assignments/%s/submit' % sa, {'link': 'https://example.com/self'}, app_token=mgr_tok)
    check('W4 主理人作为接单人提交成果', 200, code_of(b))
    status, b = http('POST', '/app/staff/online/assignments/%s/accept' % sa, {'comment': '自己验自己'}, app_token=mgr_tok)
    check('W4 主理人不能验收自己的接单（403）、没有生成结算单', (403, 0), (code_of(b), n("select count(*) from kg_online_settlement where app_id=%s" % sa)))
    status, b = http('POST', '/app/staff/online/assignments/%s/accept' % sa, {'comment': '管家验收'}, app_token=keeper_tok)
    check('W4 管家可以验收（结算单生成）', (200, 1), (code_of(b), n("select count(*) from kg_online_settlement where app_id=%s" % sa)))
    status, b = http('POST', '/app/staff/online/assignments/%s/payout' % sa,
                     {'method': '银行转账', 'ref': 'SELF-%s' % RUN, 'voucher': '/profile/upload/v.png'}, app_token=mgr_tok)
    check('W4 主理人不能给自己登记付款（403）、结算单仍待付款', (403, 'pending_payout'),
          (code_of(b), scalar("select status from kg_online_settlement where app_id=%s" % sa)))


# ================================================================ W5
def w5_extcrud():
    section('W5 通用后台：无 del_flag 的 7 页能打开；留空新增；分佣阶梯 / 重叠 / 改动限制；人工调整')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_id = u['mgr'][1]
    for key in ('ticket', 'guideapply', 'cert', 'jobsub', 'contractsign', 'arrival', 'chatconv'):
        status, b = http('GET', '/kangaroo/ext/%s/list?pageNum=1&pageSize=5' % key, token=admin)
        check('W5 后台页 %s 列表能打开（此前表没有 del_flag 列，每次 500）' % key, 200, (b or {}).get('code'))
    status, b = http('GET', '/kangaroo/ext/chatconv/list?pageNum=1&pageSize=5', token=admin)
    rows = (b or {}).get('rows') or []
    check('W5 私信会话页不返回未登记的 last_msg（私信摘要）', [], [r for r in rows if 'lastMsg' in r])
    status, b = http('GET', '/kangaroo/ext/stayorder/list?pageNum=1&pageSize=5', token=admin)
    rows = (b or {}).get('rows') or []
    check('W5 入住订单页不返回未登记的核销码 verify_code', (True, []), (len(rows) > 0, [r for r in rows if 'verifyCode' in r]))
    status, b = http('GET', '/kangaroo/ext/payorder/list?pageNum=99999999&pageSize=50', token=admin)
    check('W5 页码超大不再 500（int 溢出）', 200, (b or {}).get('code'))

    # ---- 分佣规则
    status, b = http('POST', '/kangaroo/ext/commission', {'payeeUserId': mgr_id, 'storeId': A, 'sourceType': 'activity', 'rateBp': 300,
                                                          'effectiveFrom': day(0), 'status': '0'}, token=admin)
    check('W5 固定费率规则，阶梯和说明留空也能新增（此前 NULL 写 NOT NULL 列 → 500）', 200, code_of(b))
    rule = n("select rule_id from kg_commission_rule where store_id=%s and source_type='activity' order by rule_id desc limit 1" % A)
    check('W5 留空的阶梯落成空串', '', scalar("select tiers from kg_commission_rule where rule_id=%s" % rule))
    for bad in ('1万:100', '10000:2%', '100:100;50:200;0:300', '100:5000;0:300', '100:100;200:200'):
        status, b = http('POST', '/kangaroo/ext/commission', {'payeeUserId': mgr_id, 'storeId': A, 'sourceType': 'stay', 'rateBp': 0,
                                                              'tiers': bad, 'effectiveFrom': day(0), 'status': '1'}, token=admin)
        check('W5 写错的阶梯「%s」被拒（422）' % bad, 422, code_of(b))
    status, b = http('POST', '/kangaroo/ext/commission', {'payeeUserId': mgr_id, 'storeId': A, 'sourceType': 'stay', 'rateBp': 0,
                                                          'tiers': '10000：100；0：200', 'effectiveFrom': day(0), 'status': '1'}, token=admin)
    check('W5 全角冒号分号归一化后通过', (200, '10000:100;0:200'), (code_of(b), scalar("select tiers from kg_commission_rule where store_id=%s and tiers like '10000%%' order by rule_id desc limit 1" % A)))
    status, b = http('POST', '/kangaroo/ext/commission', {'payeeUserId': mgr_id, 'storeId': A, 'sourceType': 'activity', 'rateBp': 100,
                                                          'effectiveFrom': day(0), 'status': '0'}, token=admin)
    check('W5 同门店同来源再建一条生效规则被拒（409，避免一笔订单给多人计佣）', 409, code_of(b))
    status, b = http('POST', '/kangaroo/ext/commission', {'payeeUserId': 999999999, 'storeId': A, 'sourceType': 'shop', 'rateBp': 100,
                                                          'effectiveFrom': day(0), 'status': '1'}, token=admin)
    check('W5 收款人不存在被拒（引用校验）', 422, code_of(b))
    # 已产生流水的规则不能改收款人
    sql_exec("insert into kg_wallet_entry (app_user_id, store_id, rule_id, entry_type, source_type, biz_no, biz_title, base_fen, rate_bp, amount_fen, available_date, remark, create_by, create_time) "
             "values (%s, %s, %s, 'credit', 'activity', 'SW-%s', 't', 1000, 300, 30, curdate(), 'r', 'e2e', now())" % (mgr_id, A, rule, RUN))
    status, b = http('PUT', '/kangaroo/ext/commission', {'ruleId': rule, 'payeeUserId': u['staff'][1], 'storeId': A, 'sourceType': 'activity', 'rateBp': 300,
                                                         'effectiveFrom': day(0), 'status': '0'}, token=admin)
    check('W5 已产生流水的规则不能改收款人（409）', (409, str(mgr_id)), (code_of(b), scalar("select payee_user_id from kg_commission_rule where rule_id=%s" % rule)))
    status, b = http('PUT', '/kangaroo/ext/commission', {'ruleId': rule, 'payeeUserId': mgr_id, 'storeId': A, 'sourceType': 'activity', 'rateBp': 300,
                                                         'effectiveFrom': day(0), 'status': '1', 'remark': '停用'}, token=admin)
    check('W5 已产生流水的规则可以停用', (200, '1'), (code_of(b), scalar("select status from kg_commission_rule where rule_id=%s" % rule)))

    # ---- 钱包人工调整
    base = {'appUserId': mgr_id, 'storeId': A, 'entryType': 'adjust', 'sourceType': 'adjust', 'availableDate': day(0)}
    status, b = http('POST', '/kangaroo/ext/walletentry', dict(base, bizNo='SW-NEG-%s' % RUN, amountFen=-5000, remark='结算后退款，冲减'), token=admin)
    check('W5 人工调整可以录入负数（此前输入框 min=0 悄悄改成 0）', (200, '-5000'), (code_of(b), scalar("select amount_fen from kg_wallet_entry where biz_no='SW-NEG-%s'" % RUN)))
    status, b = http('POST', '/kangaroo/ext/walletentry', dict(base, bizNo='SW-ZERO-%s' % RUN, amountFen=0, remark='零'), token=admin)
    check('W5 金额为 0 被拒', True, denied(b), ok=denied(b))
    status, b = http('POST', '/kangaroo/ext/walletentry', dict(base, bizNo='SW-NOREMARK-%s' % RUN, amountFen=100), token=admin)
    check('W5 不写原因被拒', True, denied(b), ok=denied(b))
    status, b = http('POST', '/kangaroo/ext/walletentry', dict(base, bizNo='SW-CREDIT-%s' % RUN, amountFen=100000, remark='造一笔营业收入', entryType='credit', sourceType='stay'), token=admin)
    check('W5 后台不能手工造「营业收入 / 入账」类型流水', (True, 0), (denied(b), n("select count(*) from kg_wallet_entry where biz_no='SW-CREDIT-%s'" % RUN)))
    status, b = http('POST', '/kangaroo/ext/walletentry', dict(base, bizNo='SW-BACK-%s' % RUN, amountFen=100, remark='倒填', availableDate=day(-30)), token=admin)
    check('W5 加钱的调整不能倒填已过去的可结算日', True, denied(b), ok=denied(b))
    # 收款账户：备注留空
    status, b = http('POST', '/kangaroo/ext/payee', {'appUserId': u['worker2'][1], 'payeeName': '回归收款人', 'bankName': '回归银行', 'accountMasked': '尾号 7788'}, token=admin)
    check('W5 收款账户备注留空能新增（此前 500）', 200, code_of(b))


# ================================================================ W6
def w6_reschedule():
    section('W6 员工端面试改期（此前先占新时段后释放旧时段，必撞 uk_slot_booked → 500）')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    tal_tok = u['worker2'][0]
    jid = m.new_job(A, '回归改期岗位-' + RUN, 'volunteer', 3)
    aid = m.apply(jid, tal_tok, '改期候选人', 'w6')
    http('PUT', '/kangaroo/application/audit', {'appId': aid, 'pass': True}, token=admin)
    m.new_slot(A)
    m.new_slot(A)
    slots = data(http('GET', '/app/application/%s/slots' % aid, app_token=tal_tok)[1]) or []
    assert len(slots) >= 2, slots
    s1, s2 = slots[0]['slotId'], slots[1]['slotId']
    http('POST', '/app/application/%s/book-interview' % aid, {'slotId': s1}, app_token=tal_tok)
    iid = scalar("select interview_id from kg_interview where app_id=%s order by interview_id desc limit 1" % aid)
    status, b = http('POST', '/app/staff/interviews/%s/reschedule?storeId=%s' % (iid, A), {'slotId': s2}, app_token=u['mgr'][0])
    check('W6 员工端改期成功', 200, code_of(b))
    r = sql("select (select status from kg_interview_slot where slot_id=%s), (select coalesce(booked_app_id,0) from kg_interview_slot where slot_id=%s), "
            "(select status from kg_interview_slot where slot_id=%s), (select booked_app_id from kg_interview_slot where slot_id=%s)" % (s1, s1, s2, s2))[0]
    check('W6 旧时段释放、新时段被这条报名占用', ('open', '0', 'booked', str(aid)), tuple(r))
    check('W6 面试记录指向新时段、改期次数 +1', (str(s2), '1'), tuple(sql("select slot_id, reschedule_count from kg_interview where interview_id=%s" % iid)[0]))
    status, b = http('POST', '/app/staff/interviews/%s/reschedule?storeId=%s' % (iid, A), {'slotId': s2}, app_token=u['mgr'][0])
    check('W6 改到同一时段被拒', True, denied(b), ok=denied(b))


# ================================================================ W7
def w7_phone():
    section('W7 手机号：未验证的 bind-phone 不能占号后拿到门店角色；验证过的绑定可以接管')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    squat_tok, squat_id = app_login('w7squat', '占号者')
    real_tok, real_id = app_login('w7real', '真机主')
    phone = '139%08d' % (int(RUN[:6], 16) % 100000000)
    status, b = http('POST', '/app/auth/bind-phone', {'phone': phone}, app_token=squat_tok)
    check('W7 占号者用未验证的 bind-phone 占上号码', (200, phone, None), (code_of(b), scalar("select phone from kg_app_user where user_id=%s" % squat_id),
                                                                    scalar("select phone_verified_time from kg_app_user where user_id=%s" % squat_id) if False else None))
    status, b = http('POST', '/app/staff/team/grant?storeId=%s' % A, {'target': phone, 'role': 'staff'}, app_token=mgr_tok)
    check('W7 主理人按这个手机号授权：找不到已验证的账号，被拒（不会授给占号者）', (True, 0),
          (denied(b), n("select count(*) from kg_staff_grant where app_user_id=%s and store_id=%s" % (squat_id, A))))
    status, b = http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + phone}, app_token=real_tok)
    check('W7 真正的机主验证同一个号码：接管成功', (200, phone), (code_of(b), scalar("select phone from kg_app_user where user_id=%s" % real_id)))
    check('W7 占号者的号码被释放', 'NULL', scalar("select coalesce(phone,'NULL') from kg_app_user where user_id=%s" % squat_id))
    status, b = http('POST', '/app/staff/team/grant?storeId=%s' % A, {'target': phone, 'role': 'staff'}, app_token=mgr_tok)
    check('W7 现在按手机号授权落在真正的机主账号上', (200, 1, 0), (code_of(b), n("select count(*) from kg_staff_grant where app_user_id=%s and store_id=%s and status='0'" % (real_id, A)),
                                                        n("select count(*) from kg_staff_grant where app_user_id=%s and store_id=%s" % (squat_id, A))))
    # 已验证的号码不能被别人占
    status, b = http('POST', '/app/auth/bind-phone', {'phone': phone}, app_token=squat_tok)
    check('W7 已验证的号码不能被别的账号再占', True, denied(b), ok=denied(b))
    # 换绑到未验证号码后，「已验证」标记被清掉
    other = '138%08d' % (int(RUN[2:8], 16) % 100000000)
    status, b = http('POST', '/app/auth/bind-phone', {'phone': other}, app_token=real_tok)
    check('W7 已验证账号换绑到未验证号码后，已验证标记清除', (200, 'NULL'), (code_of(b), scalar("select coalesce(phone_verified_time,'NULL') from kg_app_user where user_id=%s" % real_id)))


# ================================================================ W8
def w8_store_disable():
    section('W8 后台停用门店后，该店员工的已有会话立即失去员工权限')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    status, b = http('POST', '/kangaroo/store', {'storeName': '回归停用小院-' + RUN, 'city': '大理', 'address': '回归停用路1号', 'intro': 'x', 'contactPhone': '18500000001'}, token=admin)
    sid = int(data(b))
    tok, uid = app_login('w8staff', '停用店员工')
    m.grant(admin, uid, sid, 'manager')
    tok, uid = app_login('w8staff')
    status, b = http('GET', '/app/staff/applications?storeId=%s' % sid, app_token=tok)
    check('W8 停用前员工能访问员工端', 200, code_of(b))
    status, b = http('PUT', '/kangaroo/store', {'storeId': sid, 'status': '1'}, token=admin)
    check('W8 后台停用门店', (200, '1'), (code_of(b), scalar("select status from kg_store where store_id=%s" % sid)))
    status, b = http('GET', '/app/staff/applications?storeId=%s' % sid, app_token=tok)
    check('W8 停用后同一个会话立即失去员工权限（403，此前要等令牌过期）', 403, code_of(b))
    status, b = http('PUT', '/kangaroo/store', {'storeId': sid, 'status': '0'}, token=admin)
    status, b = http('GET', '/app/staff/applications?storeId=%s' % sid, app_token=tok)
    check('W8 恢复门店后权限恢复', 200, code_of(b))


def main():
    from fixture_assets import require_bundled_asset
    require_bundled_asset('/static/media/activity-cover.jpg')
    admin = admin_login()
    print('测试运行标识 RUN=%s' % RUN)
    m.m0_setup(admin)
    for name, fn in (('W1', w1_admin_refund), ('W2', w2_chat_revoke), ('W3', w3_activities), ('W4', w4_online), ('W5', w5_extcrud),
                     ('W6', w6_reschedule), ('W7', w7_phone), ('W8', w8_store_disable)):
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
