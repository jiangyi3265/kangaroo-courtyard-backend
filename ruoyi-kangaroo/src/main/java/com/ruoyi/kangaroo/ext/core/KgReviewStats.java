package com.ruoyi.kangaroo.ext.core;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 评价聚合回写。
 *
 * 住宿订单的评价落库时 target_type = 'room'、target_id = stay_id（见 KgUserHubService.writeReview），
 * 而 kg_stay.rating / review_count 的注释写着「评价模块回写」，但此前没有任何代码回写：
 * 列表永远是「暂无评价」。评价的新增、隐藏 / 恢复（后台通用编辑、举报成立）、删除都会改变聚合口径，
 * 这里统一按「显示中、未删除」的评价重算一遍，不做增量加减（增量在并发下会漂）。
 *
 * 重算放在**当前事务提交之后、单独的新事务里**做，而不是夹在写评价的事务中间：
 * ① UPDATE … SET x = (select … from kg_review …) 在可重复读下会对 kg_review 加共享 next-key 锁，
 *    两个人同时给同一门店 / 房源写评价时，各自持有刚插入、还没提交的评价行，又互相等对方的行，会死锁；
 * ② 重算要看到别人刚提交的评价，需要一个新的快照（写评价的事务里早先做过普通读，快照是旧的）。
 * 没有外层事务（后台通用编辑是自动提交的单条语句）时直接做。重算失败只记日志：评价本身已经保存，下一次评价变动会再算。
 */
@Component
public class KgReviewStats
{
    private static final Logger log = LoggerFactory.getLogger(KgReviewStats.class);

    /** 住宿评价在 kg_review 里的对象类型 */
    public static final String STAY_TARGET = "room";

    private final KgJdbc db;
    private final PlatformTransactionManager txManager;

    public KgReviewStats(KgJdbc db, PlatformTransactionManager txManager)
    {
        this.db = db;
        this.txManager = txManager;
    }

    public void refreshStay(Long stayId)
    {
        if (stayId == null)
        {
            return;
        }
        later(() -> doRefreshStay(stayId));
    }

    /** 门店评分 / 评价数：该门店名下所有显示中的评价（岗位、住宿、活动）合并计算 */
    public void refreshStore(Long storeId)
    {
        if (storeId == null)
        {
            return;
        }
        later(() -> doRefreshStore(storeId));
    }

    /** 评价被改动后调用：重算它所属的门店，以及（评的是住宿时）那家住宿 */
    public void refreshByReview(Long reviewId)
    {
        if (reviewId == null)
        {
            return;
        }
        later(() -> {
            Map<String, Object> r = db.one("select target_type, target_id, store_id from kg_review where review_id = :id", KgJdbc.p("id", reviewId));
            if (r == null)
            {
                return;
            }
            if (STAY_TARGET.equals(KgJdbc.asStr(r.get("targetType"))))
            {
                doRefreshStay(KgJdbc.asLong(r.get("targetId")));
            }
            doRefreshStore(KgJdbc.asLong(r.get("storeId")));
        });
    }

    private void doRefreshStay(Long stayId)
    {
        db.update("update kg_stay s set "
                + "s.review_count = (select count(*) from kg_review r where r.target_type = 'room' and r.target_id = s.stay_id and r.status = '0' and r.del_flag = '0'), "
                + "s.rating = (select round(avg(r.rating), 1) from kg_review r where r.target_type = 'room' and r.target_id = s.stay_id and r.status = '0' and r.del_flag = '0') "
                + "where s.stay_id = :s", KgJdbc.p("s", stayId));
    }

    private void doRefreshStore(Long storeId)
    {
        if (storeId == null)
        {
            return;
        }
        db.update("update kg_store st set "
                + "st.review_count = (select count(*) from kg_review r where r.store_id = st.store_id and r.status = '0' and r.del_flag = '0'), "
                + "st.rating = (select round(avg(r.rating), 2) from kg_review r where r.store_id = st.store_id and r.status = '0' and r.del_flag = '0') "
                + "where st.store_id = :s", KgJdbc.p("s", storeId));
    }

    private void later(Runnable r)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    inNewTx(r);
                }
            });
        }
        else
        {
            inNewTx(r);
        }
    }

    private void inNewTx(Runnable r)
    {
        try
        {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tt.execute(status -> {
                r.run();
                return null;
            });
        }
        catch (RuntimeException e)
        {
            log.warn("评价聚合重算失败（评价已保存，下次评价变动会再算）：{}", e.getMessage());
        }
    }
}
