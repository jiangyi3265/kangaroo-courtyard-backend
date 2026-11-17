package com.ruoyi.kangaroo.service;

import java.util.List;
import com.ruoyi.kangaroo.domain.KgInterviewSlot;

/**
 * 面试时段 Service
 */
public interface IKgInterviewSlotService
{
    KgInterviewSlot selectKgInterviewSlotBySlotId(Long slotId);

    /** 存在性校验：不存在或已删除时抛业务异常 */
    KgInterviewSlot requireSlot(Long slotId);

    List<KgInterviewSlot> selectKgInterviewSlotList(KgInterviewSlot kgInterviewSlot);

    /** 小程序可选时段（未开始、未被占用） */
    List<KgInterviewSlot> selectOpenSlots(Long storeId, Long jobId);

    int insertKgInterviewSlot(KgInterviewSlot kgInterviewSlot);

    int updateKgInterviewSlot(KgInterviewSlot kgInterviewSlot);

    int deleteKgInterviewSlotBySlotIds(Long[] slotIds);
}
