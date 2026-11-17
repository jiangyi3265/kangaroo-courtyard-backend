"""Oct 1 business regressions: real local HTTP/MySQL; external channels explicitly mocked.
Each run creates tagged fixture stores/accounts. verify.py resets local dkxy before/after suite.
"""
from urllib.parse import urlparse
import test_ext_manage as m
from test_ext_manage import http, scalar, sql_exec, check, guard, CTX, PASSED, FAILED, admin_login
from test_kangaroo_flow import BASE
import json
import os
from pathlib import Path


def inventory():
    rt, stay = CTX['rt'], CTX['stay']
    guest = CTX['u']['guest'][0]
    body = {'roomTypeId':rt,'checkIn':m.day(4),'checkOut':m.day(5),'guests':1,
            'guestName':'库存回归','guestPhone':'13800138000','agreementVersion':m.AGREEMENT,'idemKey':'oct-inventory-'+m.RUN}
    b = http('POST','/app/stay/orders',body,app_token=guest)[1]
    assert m.ok(b),b
    cal = int(scalar("select cal_id from kg_stay_calendar where room_type_id=%d and stay_date='%s'"%(rt,m.day(4))))
    check('F01 order persisted and occupied one unit','1',scalar('select occupied from kg_stay_calendar where cal_id=%d'%cal))
    before=scalar('select coalesce(stock,-1) from kg_stay_calendar where cal_id=%d'%cal)
    b=http('PUT','/kangaroo/ext/staycal',{'calId':cal,'stock':0},token=CTX['admin'])[1]
    check('F01 admin cannot lower stock below occupied',409,b.get('code'))
    check('F01 rejected edit preserves calendar stock',before,scalar('select coalesce(stock,-1) from kg_stay_calendar where cal_id=%d'%cal))
    # Restore test row only if old defective version accepted it, then exercise default capacity too.
    sql_exec('update kg_stay_calendar set stock=null where cal_id=%d'%cal)
    b=http('PUT','/kangaroo/ext/roomtype',{'roomTypeId':rt,'stock':0},token=CTX['admin'])[1]
    check('F01 default stock cannot invalidate existing occupied dates',409,b.get('code'))
    check('F01 rejected default edit preserves room capacity','3',scalar('select stock from kg_stay_room_type where room_type_id=%d'%rt))
    sql_exec('update kg_stay_room_type set stock=3 where room_type_id=%d'%rt)
    b=http('PUT','/kangaroo/ext/staycal',{'calId':cal,'stock':2},token=CTX['admin'])[1]
    check('F01 valid remaining capacity can be saved',200,b.get('code'))
    check('F01 valid capacity persisted with occupancy intact','2\t1', '\t'.join(m.sql('select stock,occupied from kg_stay_calendar where cal_id=%d'%cal)[0]))
    second=http('POST','/app/stay/orders',dict(body,idemKey='oct-inventory2-'+m.RUN),app_token=guest)[1]
    assert m.ok(second),second
    sql_exec('update kg_stay_calendar set stock=null where cal_id=%d'%cal)
    sql_exec("update kg_stay set status='offline' where stay_id=%d"%stay)
    path='/app/staff/stay/room-types/%s'%rt
    b=http('PUT',path,{'stock':1},app_token=CTX['u']['mgr'][0])[1]
    check('F01 employee cannot lower default below occupied',409,b['code'])
    check('F01 employee failure preserves default and existing bookings',('3','2'),(scalar('select stock from kg_stay_room_type where room_type_id=%d'%rt),scalar('select occupied from kg_stay_calendar where cal_id=%d'%cal)))
    check('F01 default equal to occupancy accepted',200,http('PUT',path,{'stock':2},app_token=CTX['u']['mgr'][0])[1]['code'])
    sql_exec("update kg_stay set status='online' where stay_id=%d; update kg_stay_room_type set stock=3 where room_type_id=%d"%(stay,rt))
    # Two HTTP operations start together; either may win, but inventory must remain consistent.
    from threading import Barrier
    from concurrent.futures import ThreadPoolExecutor
    gate=Barrier(2)
    def reserve():
        gate.wait(); return http('POST','/app/stay/orders',dict(body,idemKey='oct-race-'+m.RUN),app_token=CTX['u']['outsider'][0])[1]
    def reduce():
        gate.wait(); return http('PUT','/kangaroo/ext/roomtype',{'roomTypeId':rt,'stock':2},token=CTX['admin'])[1]
    with ThreadPoolExecutor(2) as pool:
        a,c=pool.submit(reserve),pool.submit(reduce); result=[a.result(),c.result()]
    check('F01 concurrent reserve and capacity edit cannot both claim last room',1,sum(m.ok(x) for x in result))
    check('F01 no inventory write race exceeds capacity','0',scalar('select count(*) from kg_stay_calendar c join kg_stay_room_type r on r.room_type_id=c.room_type_id where c.room_type_id=%d and c.occupied>coalesce(c.stock,r.stock)'%rt))
    check('F01 concurrency causes no unhandled error',True,all(x.get('code')!=500 or '订满' in str(x.get('msg')) or '不可订' in str(x.get('msg')) for x in result))


def contract():
    tok=CTX['u']['worker'][0]
    job=m.new_job(CTX['A'],'签约证据-'+m.RUN,'volunteer',3)
    aid=m.to_store_confirmed(job,tok,'签约同学','evidence')
    b=http('POST','/app/application/%s/sign'%aid,{},app_token=tok)[1]
    check('F02 legacy sign cannot bypass contract evidence',409,b.get('code'))
    check('F02 rejected legacy sign leaves status unchanged','store_confirmed',scalar('select status from kg_application where app_id=%d'%aid))
    check('F02 rejected legacy sign emits no signed notification','0',scalar("select count(*) from kg_message where biz_type='application' and biz_id=%d and title='责任书已签署'"%aid))
    # Old-version failure is recorded above; restore only this fixture to verify the supported path.
    sql_exec("update kg_application set status='store_confirmed' where app_id=%d"%aid)
    ct=http('GET','/app/application/%s/contract'%aid,app_token=tok)[1]['data']
    b=http('POST','/app/application/%s/contract/sign'%aid,{'name':ct['signerDefault'],'hash':ct['hash'],'agree':True},app_token=tok)[1]
    check('F02 supported signature succeeds',200,b.get('code'))
    check('F02 signature evidence persisted','1',scalar("select count(*) from kg_contract_sign where app_id=%d and content_hash is not null and signed_time is not null"%aid))
    check('F02 supported signature advances business state','contract_signed',scalar('select status from kg_application where app_id=%d'%aid))


def draft():
    u, store = CTX['u'], CTX['A']
    body = {'storeId':store,'title':'跨年草稿-'+m.RUN,'taskDate':str(int(m.day(0)[:4])+1)+'-01-02',
            'startTime':'10:00','endTime':'12:00','estHours':2,'priority':'high','assignMode':'staff',
            'assignees':[u['staff'][1]],'quota':1,'publish':False,'place':'','roleName':'','note':''}
    b=http('POST','/app/manage/tasks',body,app_token=u['mgr'][0])[1]
    assert m.ok(b),b
    tid=b['data']['taskId']; CTX['draft']=tid; CTX['draftBody']=body
    d=http('GET','/app/manage/tasks/%s'%tid,app_token=u['mgr'][0])[1]['data']
    check('F03 re-open draft preserves raw year, hours, priority and time',
          (body['taskDate'],'10:00','12:00',2,'high'),
          (str(d['taskDate'])[:10],str(d['startTime'])[:5],str(d['endTime'])[:5],d['estHours'],d['priority']))
    check('F03 draft preserves assigned employee',[u['staff'][1]],[int(a['userId']) for a in d['assignees']])
    check('F03 editing keeps empty raw fields instead of display defaults',('','',''),(d['editPlace'] or '',d['editRoleName'] or '',d['editNote'] or ''))
    check('F03 draft hidden from employee',404,http('GET','/app/manage/tasks/%s'%tid,app_token=u['staff'][0])[1]['code'])


def team_talent():
    u,A=CTX['u'],CTX['A']; tok,uid=u['talent']
    job=m.new_job(A,'同行隐私-'+m.RUN); CTX['teamJob']=job
    aid=m.apply(job,tok,'候选人丙丙','team'); CTX['talentApp']=aid
    path='/app/jobs/%d/team'%job
    d=http('POST',path,{'visible':True},app_token=tok)[1]['data']
    check('F04 explicit join persists and is visible',(True,[uid]),(d['joined'],[int(x['userId']) for x in d['members']]))
    mark='/app/staff/talents/%s/mark?storeId=%s'%(uid,A)
    check('F05 hide talent accepted',200,http('POST',mark,{'what':'hidden','on':True},app_token=u['mgr'][0])[1]['code'])
    search='/app/staff/talents/search?storeId=%s'%A
    rows=http('POST',search,{},app_token=u['mgr'][0])[1]['data']['rows']
    check('F05 hidden candidate absent from normal pool',False,uid in [x['userId'] for x in rows])
    hidden=http('POST',search,{'hidden':True},app_token=u['mgr'][0])[1]['data']['rows']
    check('F05 hidden management retrieves masked candidate',(True,False),(uid in [x['userId'] for x in hidden],any('phone' in x for x in hidden)))
    check('F05 cross-store restore rejected',403,http('POST',mark,{'what':'hidden','on':False},app_token=u['far'][0])[1]['code'])
    check('F05 owner restores hidden candidate',200,http('POST',mark,{'what':'hidden','on':False},app_token=u['mgr'][0])[1]['code'])
    check('F05 restoration persisted','0',scalar('select hidden from kg_talent_mark where store_id=%s and app_user_id=%s'%(A,uid)))
    rows=http('POST',search,{},app_token=u['mgr'][0])[1]['data']['rows']
    check('F05 restored candidate returns to pool',True,uid in [x['userId'] for x in rows])
    for state in ['withdrawn','review_rejected','terminated']:
        sql_exec("update kg_application set status='%s' where app_id=%d"%(state,aid))
        d=http('GET',path,app_token=tok)[1]['data']
        check('F04 '+state+' removes membership and eligibility',(False,None,[]),(d['joined'],d['myAppId'],d['members']))
        check('F04 '+state+' cannot rejoin',403,http('POST',path,{'visible':True},app_token=tok)[1]['code'])
    sql_exec("update kg_application set status='review_pending' where app_id=%d"%aid)
    sql_exec("update kg_app_user set status='1' where user_id=%d"%uid)
    check('F04 stopped account no longer appears',[],http('GET',path)[1]['data']['members'])
    check('F06 stopped user old token denied immediately',403,http('GET','/app/auth/account',app_token=tok)[1]['code'])
    sql_exec("update kg_app_user set status='0' where user_id=%d"%uid)
    sql_exec("update kg_job set status='draft' where job_id=%d"%job)
    check('F04 private job team inaccessible',404,http('GET',path)[1]['code'])
    sql_exec("update kg_job set status='published' where job_id=%d"%job)


def reviews():
    A,uid,stay=CTX['A'],CTX['u']['guest'][1],CTX['stay']
    # Controlled own-store fixture: historical/public/hidden rows and >one page.
    for i in range(53):
        sql_exec("insert into kg_review(target_type,target_id,store_id,app_user_id,biz_type,biz_id,rating,content,anonymous,status,create_time) "
                 "values('room',%d,%d,%d,'stay_order',%d,%d,'分页评价-%s-%d','1','%s',date_sub(now(),interval %d day))"%
                 (stay,A,uid,800000000+A*100+i,1+i%5,m.RUN,i,'1' if i==52 else '0',60 if i==51 else 0))
    path='/app/stores/%d/reviews?stayId=%d&filter=%s'%(A,stay,m.urllib.parse.quote('住宿'))
    d=http('GET',path)[1]['data']; CTX['reviews']=d
    check('F07 first page filters hidden records and advertises next',(52,50,True),(d['filteredTotal'],len(d['list']),d['hasMore']))
    e=http('GET',path+'&pageNum=2')[1]['data']
    check('F07 second page accessible without duplicates',(2,False,False),(len(e['list']),e['hasMore'],bool(set(x['reviewId'] for x in d['list'])&set(x['reviewId'] for x in e['list']))))
    e=http('GET',path+'&days=30&sort=high')[1]['data']
    check('F07 time filtering changes persisted result set',51,e['filteredTotal'])
    check('F07 score sorting is actual descending',True,all(e['list'][i]['rating']>=e['list'][i+1]['rating'] for i in range(len(e['list'])-1)))
    check('F07 anonymous identity remains private',True,all(x['name']=='匿名用户' and not x['avatar'] for x in e['list']))
    check('F07 invalid date filter rejected',422,http('GET',path+'&days=-1')[1]['code'])


def save_browser_fixture():
    p=Path(__file__).resolve().parents[2]/'.dev'/'fullflow-fixture.json'
    p.write_text(json.dumps(CTX,ensure_ascii=False),encoding='utf-8')


def linked_service():
    guest=CTX['u']['worker2'][0]; A=CTX['A']
    b=http('POST','/app/stay/orders',{'roomTypeId':CTX['rt'],'checkIn':m.day(7),'checkOut':m.day(9),'guests':1,
           'guestName':'关联订单','guestPhone':'13800138000','agreementVersion':m.AGREEMENT,'idemKey':'linked-'+m.RUN},app_token=guest)[1]
    assert m.ok(b),b
    order=b['data']; no=order['orderNo']
    assert m.ok(http('POST','/app/pay/%s/mock-confirm'%order['payNo'],{},app_token=guest)[1])
    code=scalar("select verify_code from kg_stay_order where order_no='%s'"%no)
    resolve='/app/staff/stay/verify/resolve?storeId='+str(A)
    # Move only this isolated order across its stored checkout-time boundary; never change system time.
    sql_exec("update kg_stay_order set check_in=curdate()-interval 1 day,check_out=curdate(),stay_snapshot=json_set(stay_snapshot,'$.checkoutTime','00:00') where order_no='%s'"%no)
    check('F14 checkout cutoff rejects stay redemption',False,http('POST',resolve,{'code':code},app_token=CTX['u']['mgr'][0])[1]['data']['canVerify'])
    denied=http('POST','/app/staff/stay/verify/confirm?storeId='+str(A),{'code':code},app_token=CTX['u']['mgr'][0])[1]
    check('F14 rejected checkout redemption preserves order',(True,'confirmed'),(denied['code']!=200,scalar("select status from kg_stay_order where order_no='%s'"%no)))
    sql_exec("update kg_stay_order set check_out=curdate()+interval 1 day where order_no='%s'"%no)
    check('F14 before checkout cutoff allows stay redemption',True,http('POST',resolve,{'code':code},app_token=CTX['u']['mgr'][0])[1]['data']['canVerify'])
    sql_exec("update kg_stay_order set check_in='%s',check_out='%s' where order_no='%s'"%(m.day(7),m.day(9),no))
    cat=scalar("select dict_value from sys_dict_data where dict_type='kg_shop_category' and status='0' order by dict_sort limit 1")
    b=http('POST','/kangaroo/ext/shopservice',{'storeId':A,'category':cat,'serviceName':'关联服务-'+m.RUN,'priceFen':1000,'unitLabel':'份',
           'timeSlots':'10:00-11:00','slotCapacity':5,'advanceHours':0,'rescheduleLimit':1,'refundable':'1','status':'0'},token=CTX['admin'])[1]
    assert m.ok(b),b
    sid=int(scalar("select service_id from kg_shop_service where store_id=%d and service_name='关联服务-%s'"%(A,m.RUN)))
    b=http('POST','/app/shop/orders',{'serviceId':sid,'stayOrderNo':no,'qty':1,'useDate':m.day(8),'timeSlot':'10:00-11:00','idemKey':'linked-service-'+m.RUN},app_token=guest)[1]
    assert m.ok(b),b
    child=b['data']
    paid=http('POST','/app/shop/orders',{'serviceId':sid,'stayOrderNo':no,'qty':1,'useDate':m.day(8),'timeSlot':'10:00-11:00','idemKey':'linked-paid-'+m.RUN},app_token=guest)[1]['data']
    assert m.ok(http('POST','/app/pay/%s/mock-confirm'%paid['payNo'],{},app_token=guest)[1])
    assert m.ok(http('POST','/app/shop/orders/%s/refund'%paid['orderNo'],{'reason':'回归普通退款'},app_token=guest)[1])
    reject='/kangaroo/stayops/shoporder/%s/refund-reject'%paid['orderNo']
    check('F08 valid stay service refund can be rejected',200,http('PUT',reject,{'reason':'服务仍可用'},token=CTX['admin'])[1]['code'])
    check('F08 valid reject restores unused with real code','1',scalar("select status='unused' and verify_code is not null from kg_shop_order where order_no='%s'"%paid['orderNo']))
    assert m.ok(http('POST','/app/stay/orders/%s/refund'%no,{'reason':'取消住宿'},app_token=guest)[1])
    b=http('PUT','/kangaroo/stayops/stayorder/%s/refund-approve'%no,{},token=CTX['admin'])[1]
    check('F08 parent refund completes',200,b['code'])
    check('F08 pending child business order closes','closed',scalar("select status from kg_shop_order where order_no='%s'"%child['orderNo']))
    for action in ['prepay','mock-confirm']:
        check('F08 cancelled stay child cannot '+action,409,http('POST','/app/pay/%s/%s'%(child['payNo'],action),{},app_token=guest)[1]['code'])
    check('F08 invalid child never receives a redemption code','1',scalar("select verify_code is null from kg_shop_order where order_no='%s'"%child['orderNo']))
    check('F08 parent cancellation preserves paid child refund obligation','refund_pending',scalar("select status from kg_shop_order where order_no='%s'"%paid['orderNo']))
    check('F08 cannot reject forced refund with invalid parent',409,http('PUT',reject,{'reason':'不允许恢复'},token=CTX['admin'])[1]['code'])
    check('F08 cancelled parent cannot create new child',409,http('POST','/app/shop/orders',{'serviceId':sid,'stayOrderNo':no,'qty':1,'useDate':m.day(8),'timeSlot':'10:00-11:00','idemKey':'linked-invalid-'+m.RUN},app_token=guest)[1]['code'])
    check('F08 forced refund can be completed',200,http('PUT','/kangaroo/stayops/shoporder/%s/refund-approve'%paid['orderNo'],{},token=CTX['admin'])[1]['code'])
    check('F08 business and payment refund agree',('refunded','refunded'),(scalar("select status from kg_shop_order where order_no='%s'"%paid['orderNo']),scalar("select status from kg_pay_order where pay_no='%s'"%paid['payNo'])))
    CTX['closedChildPay']=child['payNo']


def interview_window():
    from interview_clock_fixture import advance_interview_fixture
    tok=CTX['u']['payee2'][0]
    job=m.new_job(CTX['A'],'面试时间-'+m.RUN)
    aid=m.apply(job,tok,'面试同学','interview-window')
    assert m.ok(http('PUT','/kangaroo/application/audit',{'appId':aid,'pass':True},token=CTX['admin'])[1])
    m.new_slot(CTX['A'])
    slots=http('GET','/app/application/%d/slots'%aid,app_token=tok)[1]['data']
    assert m.ok(http('POST','/app/application/%d/book-interview'%aid,{'slotId':slots[0]['slotId']},app_token=tok)[1])
    b=http('POST','/app/application/%d/finish-interview'%aid,{},app_token=tok)[1]
    check('F09 cannot finish future interview',409,b['code'])
    check('F09 rejection leaves appointment booked','interview_booked',scalar('select status from kg_application where app_id=%d'%aid))
    if b['code']==200: # Preserve before-fix failure evidence; restore only this fixture for positive case.
        sql_exec("update kg_application set status='interview_booked' where app_id=%d"%aid)
    advance_interview_fixture(aid)
    b=http('POST','/app/application/%d/finish-interview'%aid,{},app_token=tok)[1]
    check('F09 elapsed appointment can be finished',200,b['code'])
    check('F09 finish result persists','interview_done',scalar('select status from kg_application where app_id=%d'%aid))


def identity_concurrency():
    from test_ext_user import make_id
    users=[m.app_login('identity-%d'%i) for i in range(6)]
    idno=make_id()
    result=m.parallel(lambda u:http('POST','/app/me/real-name',{'name':'测试同学','idNo':idno},app_token=u[0])[1],users,6)
    check('F10 concurrent identity verification admits exactly one owner',1,sum(m.ok(x) for x in result))
    check('F10 identity losers receive conflict',5,sum(x.get('code')==409 for x in result))
    ids=','.join(str(u[1]) for u in users)
    check('F10 only one persisted verified identity','1',scalar('select count(*) from kg_app_user where user_id in (%s) and real_name_verified_time is not null'%ids))
    winner=users[next(i for i,x in enumerate(result) if m.ok(x))]
    check('F10 verified user cannot replace identity',409,http('POST','/app/me/real-name',{'name':'测试同学','idNo':make_id()},app_token=winner[0])[1]['code'])


def moderation():
    A,stay,u=CTX['A'],CTX['stay'],CTX['u']
    # Seed a locally owned, approved Vlog; the reported comment and both moderation actions use real APIs.
    sql_exec("insert into kg_stay_vlog(stay_id,store_id,title,platform,link,authorized,status,create_time) values(%d,%d,'复核视频-%s','douyin','https://www.douyin.com/','1','online',now())"%(stay,A,m.RUN))
    vid=int(scalar("select vlog_id from kg_stay_vlog where store_id=%d and title='复核视频-%s'"%(A,m.RUN)))
    b=http('POST','/app/stay/vlogs/%d/comments'%vid,{'content':'平台处置回归-'+m.RUN},app_token=u['guest'][0])[1]
    assert m.ok(b),b
    cid=b['data']['commentId']
    assert m.ok(http('POST','/app/stay/vlogs/comments/%d/report'%cid,{},app_token=u['outsider'][0])[1])
    rid=int(scalar("select report_id from kg_report where target_type='vlog_comment' and target_id=%d"%cid))
    assert m.ok(http('PUT','/kangaroo/core/report/%d/uphold'%rid,{'reason':'复核隐藏'},token=CTX['admin'])[1])
    for action,body in [('hide',{'hidden':False}),('hide',{'hidden':True}),('dismiss',{})]:
        check('F11 staff cannot override platform '+action,403,http('POST','/app/staff/stay/vlogs/comments/%d/%s'%(cid,action),body,app_token=u['mgr'][0])[1]['code'])
    check('F11 comment and upheld audit remain intact',('hidden','upheld'),(scalar('select status from kg_stay_vlog_comment where comment_id=%d'%cid),scalar('select status from kg_report where report_id=%d'%rid)))
    check('F11 public comment list excludes moderated content',False,cid in [x['commentId'] for x in http('GET','/app/stay/vlogs/%d/comments'%vid)[1]['data']])
    CTX['moderationReport']=rid
    result=http('GET','/kangaroo/core/report/%d/context'%rid,token=CTX['admin'])[1]
    check('F11 admin can read reported original text',True,result['code']==200 and '平台处置回归-'+m.RUN in result['data']['reviewContext']['text'])
    check('F11 app account cannot read privileged report context',401,http('GET','/kangaroo/core/report/%d/context'%rid,app_token=u['guest'][0])[1]['code'])


def selected_activities():
    tok,uid=CTX['u']['payee2'][0:2]
    maker=CTX['u']['outsider'][0]
    for role in ['payee2','outsider']:
        token,user=CTX['u'][role]
        b=http('POST','/app/auth/phone-verify',{'code':'mock-phone-18%09d'%user},app_token=token)[1]
        assert m.ok(b),b
    city=scalar('select city from kg_stay where stay_id=%d'%CTX['stay'])
    assert m.ok(http('POST','/app/community/consent',{'doc':'activity'},app_token=maker)[1])
    ids=[]
    for i in range(2):
        title='附选恢复-%s-%d'%(m.RUN,i)
        b=http('POST','/app/activities',{'title':title,'startTime':m.day(12)+' 15:00','hours':2,'place':'院内大厅','capacity':10,'city':city,'category':'其他','intro':'回归附选活动'},app_token=maker)[1]
        assert m.ok(b),b
        ids.append(int(scalar("select activity_id from kg_activity where title='%s'"%title)))
    body={'roomTypeId':CTX['rt'],'checkIn':m.day(12),'checkOut':m.day(14),'guests':1,'guestName':'附选回归','guestPhone':'13800138000',
          'agreementVersion':m.AGREEMENT,'idemKey':'selection-'+m.RUN,'selectedActivityIds':ids}
    before=(scalar('select count(*) from kg_stay_order where app_user_id=%d'%uid),scalar('select count(*) from kg_pay_order where app_user_id=%d'%uid),
            scalar('select coalesce(sum(occupied),0) from kg_stay_calendar where room_type_id=%d'%CTX['rt']))
    rejected=http('POST','/app/stay/orders',dict(body,idemKey='selection-invalid-'+m.RUN,selectedActivityIds=[999999999]),app_token=tok)[1]
    check('F12 invalid selection rejects entire stay creation',409,rejected['code'])
    check('F12 invalid selection leaves no order/payment/inventory effect',before,(scalar('select count(*) from kg_stay_order where app_user_id=%d'%uid),scalar('select count(*) from kg_pay_order where app_user_id=%d'%uid),
            scalar('select coalesce(sum(occupied),0) from kg_stay_calendar where room_type_id=%d'%CTX['rt'])))
    b=http('POST','/app/stay/orders',body,app_token=tok)[1]
    assert m.ok(b),b
    o=b['data']; no=o['orderNo']; path='/app/stay/orders/'+no
    def detail(): return http('GET',path,app_token=tok)[1]['data']
    def enroll(a,t=tok): return http('POST',path+'/activities/'+str(a),{},app_token=t)[1]
    check('F12 order refresh keeps both selected activities',ids,[a['activityId'] for a in detail()['selectedActivities']])
    check('F12 unpaid order cannot enroll selections',409,enroll(ids[0])['code'])
    check('F12 other user cannot enroll selected activities',403,enroll(ids[0],CTX['u']['guest'][0])['code'])
    assert m.ok(http('POST','/app/pay/%s/mock-confirm'%o['payNo'],{},app_token=tok)[1])
    check('F12 no implicit agreement consent',423,enroll(ids[0])['code'])
    assert m.ok(http('POST','/app/community/consent',{'doc':'activity'},app_token=tok)[1])
    sql_exec('update kg_activity set fee_fen=100 where activity_id=%d'%ids[1])
    check('F12 first selected free activity enrolled',200,enroll(ids[0])['code'])
    check('F12 price change never charges activity through stay order',409,enroll(ids[1])['code'])
    check('F12 partial failure survives re-entry',['completed','pending'],[a['status'] for a in detail()['selectedActivities']])
    sql_exec("update kg_activity set fee_fen=0,start_time='%s 15:00' where activity_id=%d"%(m.day(16),ids[1]))
    check('F12 moved activity outside stay interval rejected',409,enroll(ids[1])['code'])
    sql_exec("update kg_activity set start_time='%s 15:00' where activity_id=%d"%(m.day(12),ids[1]))
    before=(scalar('select signed from kg_activity where activity_id=%d'%ids[1]),scalar('select count(*) from kg_message where recipient_id=%d'%uid))
    trigger='oct_selection_'+m.RUN.replace('-','')
    try:
        sql_exec("create trigger %s before update on kg_stay_activity_selection for each row set NEW.update_time=IF(NEW.app_user_id=%d and NEW.status='completed',cast('SELECTION_TEST_INVALID_DATE' as datetime),NEW.update_time)"%(trigger,uid))
        # The existing global handler maps SQL data-integrity violations to 422; assert rollback separately below.
        check('F12 injected selection write failure is not reported successful',422,enroll(ids[1])['code'])
        check('F12 signup and notification transaction roll back',('0',before),(scalar('select count(*) from kg_activity_signup where activity_id=%d and app_user_id=%d'%(ids[1],uid)),(scalar('select signed from kg_activity where activity_id=%d'%ids[1]),scalar('select count(*) from kg_message where recipient_id=%d'%uid))))
        check('F12 failed completion stays retryable','pending',scalar('select status from kg_stay_activity_selection where activity_id=%d and app_user_id=%d'%(ids[1],uid)))
    finally:
        sql_exec('drop trigger if exists '+trigger)
    result=m.parallel(lambda _:enroll(ids[1]),range(4),4)
    check('F12 concurrent retry has one persisted signup','1',scalar('select count(*) from kg_activity_signup where activity_id=%d and app_user_id=%d'%(ids[1],uid)))
    check('F12 concurrent retry returns consistent success',[200]*4,[b['code'] for b in result])
    signup=int(scalar('select signup_id from kg_activity_signup where activity_id=%d and app_user_id=%d'%(ids[0],uid)))
    sql_exec("update kg_activity_signup set status='cancelled' where signup_id=%d"%signup)
    check('F12 later stay retry does not undo cancelled activity signup',200,enroll(ids[0])['code'])
    check('F12 cancelled activity remains cancelled','cancelled',scalar('select status from kg_activity_signup where signup_id=%d'%signup))
    # Keep one pending choice for the H5 re-entry-and-click regression; this is isolated test setup.
    sql_exec("delete from kg_activity_signup where activity_id=%d and app_user_id=%d; update kg_activity set signed=greatest(signed-1,0) where activity_id=%d; update kg_stay_activity_selection set status='pending',signup_id=null where activity_id=%d and app_user_id=%d"%(ids[1],uid,ids[1],ids[1],uid))
    CTX['selectionOrder']=no;CTX['selectionActivity']=ids[1]


def wallet_debt():
    tok,uid=m.app_login('debt-boundary','欠款注销回归')
    check('F13 empty account has no deactivation blocker',True,http('GET','/app/me/deactivation',app_token=tok)[1]['data']['canDeactivate'])
    sql_exec("insert into kg_wallet_entry(app_user_id,entry_type,source_type,biz_no,amount_fen,available_date) values(%d,'adjust','adjust','debt-%s',-125,curdate())"%(uid,m.RUN))
    result=http('GET','/app/me/deactivation',app_token=tok)[1]['data']
    check('F13 negative wallet blocks deactivation with exact cents',(False,True),(result['canDeactivate'],any('1.25' in x and '欠款' in x for x in result['blockers'])))
    check('F13 HTTP deactivation cannot bypass wallet debt',409,http('POST','/app/me/deactivate',{'confirm':'确认注销'},app_token=tok)[1]['code'])
    check('F13 denied deactivation preserves account and ledger',('0','-125'),(scalar('select del_flag from kg_app_user where user_id=%d'%uid),scalar('select sum(amount_fen) from kg_wallet_entry where app_user_id=%d'%uid)))


def overdue_settlement():
    worker=CTX['u']['worker2'][0];mgr=CTX['u']['mgr'][0]
    body={'title':'逾期结算-'+m.RUN,'taskType':'内容写作','deliverables':'测试文案','criteria':'原创','description':'验收夹具','commissionFen':1000,'quota':2,'deadline':m.day(3)+' 12:00'}
    b=http('POST','/app/staff/online/tasks?storeId='+str(CTX['A']),body,app_token=mgr)[1];assert m.ok(b),b
    jid=b['data']['jobId'] if isinstance(b['data'],dict) else b['data']
    assert m.ok(http('PUT','/kangaroo/job/changeStatus',{'jobId':jid,'status':'published'},token=CTX['admin'])[1])
    aid=m.apply(jid,worker,'逾期接单人','overdue')
    assert m.ok(http('POST','/app/staff/applications/%s/audit'%aid,{'pass':True},app_token=mgr)[1])
    assert m.ok(http('POST','/app/online/assignments/%s/submit'%aid,{'link':'https://example.com/acceptance'},app_token=worker)[1])
    assert m.ok(http('POST','/app/staff/online/assignments/%s/accept'%aid,{'comment':'通过'},app_token=mgr)[1])
    path='/app/online/assignments/'+str(aid)
    check('F15 within seven days has no overdue dispute capability',False,http('GET',path,app_token=worker)[1]['data']['settlement']['canDispute'])
    check('F15 early dispute is rejected',409,http('POST',path+'/confirm',{'received':False,'reason':'提前申诉'},app_token=worker)[1]['code'])
    sql_exec('update kg_online_settlement set create_time=date_sub(now(),interval 8 day) where app_id=%d'%aid)
    check('F15 overdue unpaid settlement exposes dispute capability',True,http('GET',path,app_token=worker)[1]['data']['settlement']['canDispute'])
    check('F15 pending payout cannot claim received',409,http('POST',path+'/confirm',{'received':True},app_token=worker)[1]['code'])
    CTX['overdueAssignment']=aid


def main():
    assert urlparse(BASE).hostname in ('localhost','127.0.0.1')
    expected_db = os.environ.get('KG_DB', '')
    assert expected_db.startswith(('dkxy_acceptance_', 'kg_acceptance_', 'acceptance_')), 'Explicit isolated KG_DB required'
    assert scalar('select database()') == expected_db, 'Actual database must match isolated KG_DB'
    assert http('GET','/app/auth/ping')[1]['data']['wxMock'] is True
    m.m0_setup(admin_login())
    guard('F01 inventory',inventory)
    guard('F02 contract evidence',contract)
    guard('F03 draft persistence',draft)
    guard('F04/F05 team privacy and talent recovery',team_talent)
    guard('F07 review pagination and filters',reviews)
    guard('F08 cancelled parent blocks pending child payment',linked_service)
    guard('F09 interview time guard',interview_window)
    guard('F10 identity uniqueness',identity_concurrency)
    guard('F11 moderation ownership',moderation)
    guard('F12 selected activities persist and resume',selected_activities)
    guard('F13 wallet debt deactivation',wallet_debt)
    guard('F15 overdue settlement capability',overdue_settlement)
    if CTX.get('closedChildPay'):
        import time
        deadline=time.monotonic()+65
        while time.monotonic()<deadline and scalar("select status from kg_pay_order where pay_no='%s'"%CTX['closedChildPay'])=='pending':
            time.sleep(2)
        check('F08 scheduled recovery closes orphan pending payment','closed',scalar("select status from kg_pay_order where pay_no='%s'"%CTX['closedChildPay']))
    save_browser_fixture()
    print('通过 %d / 共 %d'%(len(PASSED),len(PASSED)+len(FAILED)))
    return 1 if FAILED else 0

if __name__=='__main__':
    raise SystemExit(main())
