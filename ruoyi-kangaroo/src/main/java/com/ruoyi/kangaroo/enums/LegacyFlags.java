package com.ruoyi.kangaroo.enums;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存量标志位的极性例外清单（WP01；R3 S23）。
 *
 * 全项目新列的标志位统一 '0' 是 / '1' 否（00 §1.0.1、裁决 R21，与 kg_store.safe_cert、
 * kg_job.insurance_flag、kg_job.remote_flag 一致）。下面这些存量列早于这条约定，
 * 取值与它相反、或者根本不是"是/否"两值。它们不改 —— 改了要迁移存量数据、改全部读写点 ——
 * 但新代码读写它们时一律用这里的常量，不要按新约定想当然地写 '0' / '1'：
 * 把 read_flag='0' 当成"已读"，未读红点就整片消失，而编译器和接口都不会报错。
 *
 * 扫描器（小程序 scripts/check-fake-success.test.mjs 的极性检查）应把 {@link #EXCEPTIONS} 里的列
 * 当作例外，而不是按"0 是"去判；接入扫描器由改动该扫描器的工作包负责（WP04）。
 */
public final class LegacyFlags
{
    /** kg_message.read_flag：'0' 未读 / '1' 已读 —— 与"'0' 是"相反（'0' 的意思是"否，还没读"） */
    public static final String MESSAGE_UNREAD = "0";

    /** kg_message.read_flag：'1' 已读 */
    public static final String MESSAGE_READ = "1";

    /** 全部 kg_ 业务表的 del_flag（若依口径）：'0' 存在 —— 不是 0/1 两值 */
    public static final String DEL_EXISTS = "0";

    /** 全部 kg_ 业务表的 del_flag（若依口径）：'2' 已删除（没有 '1'） */
    public static final String DEL_DELETED = "2";

    /** 列 → 口径说明。新增例外只能是存量列；新列一律按 '0' 是 / '1' 否，不进这张表。 */
    public static final Map<String, String> EXCEPTIONS;

    static
    {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("kg_message.read_flag", "'0' 未读 / '1' 已读（与 '0' 是 / '1' 否 相反）");
        m.put("*.del_flag", "'0' 存在 / '2' 删除（若依口径，不是 0/1 两值）");
        EXCEPTIONS = Collections.unmodifiableMap(m);
    }

    private LegacyFlags()
    {
    }
}
