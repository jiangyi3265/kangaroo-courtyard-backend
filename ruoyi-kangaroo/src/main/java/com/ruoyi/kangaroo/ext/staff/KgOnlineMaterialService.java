package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.community.KgCommunityGuard;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.AppUserContext;

/**
 * 线上任务「发素材」：门店接单后，把拍摄 / 写作要用的文字、图片和文件发给这位接单人（kg_online_material，可以分多次补发）。
 * 文件（V41 kg_online_material_file：压缩包和 PDF、Word、Excel、PPT）先单独上传（{@link #upload}），发送时随这次素材一起发出。
 *
 * 素材只是信息展示：不改报名、成果提交和结算的状态，接单人没收到素材也照样可以提交成果。
 * 交付页的步骤条（报名 → 已接单 → 素材已发 → 已交付 → 验收通过 → 结算）由 {@link #flow} 按现有状态加素材发送时间推出来。
 */
@Service
public class KgOnlineMaterialService
{
    /** 每次最多发多少张图（微信 chooseMedia 一次最多能选 20 张；20 个平台图片地址约 1300 字符，images 列 2000 字符装得下） */
    public static final int MAX_IMAGES = 20;

    /** 每次的文字上限（约 20 页 A4，平时写不满；content 是 TEXT，1 万字全是 4 字节字符也只有 4 万字节） */
    public static final int MAX_CONTENT = 10000;

    /** 同一个接单累计的文字上限：交付页把整个接单一次性下发，微信单次 setData 不能超过 1MB */
    public static final int MAX_CONTENT_TOTAL = 100000;

    /** kg_online_material.images 列宽 */
    public static final int MAX_IMAGES_LEN = 2000;

    /** 同一个接单最多发多少次（防止误操作刷屏；正常一两次就够） */
    public static final int MAX_PER_ASSIGNMENT = 50;

    /** 每次最多带几个文件 */
    public static final int MAX_FILES = 5;

    /** 同一个接单「已上传、还没发出」的文件最多几个（关掉弹窗没发的 24 小时后自动清理） */
    public static final int MAX_PENDING_FILES = 20;

    /** 同一个接单的文件累计上限 */
    public static final long MAX_BYTES_PER_ASSIGNMENT = 300L * 1024 * 1024;

    /** 每家门店每天上传的文件上限 */
    public static final long MAX_BYTES_PER_STORE_DAY = 1024L * 1024 * 1024;

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgCommunityGuard guard;

    @Autowired(required = false)
    private KgOnlineMaterialFiles files;

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s;
    }

    /**
     * 能不能给这位接单人发素材（上传文件和发送共用）：本店的线上任务接单、接单仍在进行、不是自己的接单、接单账号可用、结算还没确认完成。
     * 返回接单行（appId、appUserId、jobId、title…）。调用前控制器已按「线上任务·编辑」把过关。
     */
    Map<String, Object> assertCanSend(StaffSupport.Ctx c, Long appId)
    {
        Map<String, Object> a = db.one("select a.app_id, a.app_user_id, a.job_id, a.status, a.del_flag, j.store_id job_store, j.title, j.job_type "
                + "from kg_application a join kg_job j on j.job_id = a.job_id where a.app_id = :id", p("id", appId));
        if (a == null || !"0".equals(str(a.get("delFlag"))) || !"online".equals(str(a.get("jobType"))))
        {
            throw new ServiceException("接单记录不存在", 404);
        }
        if (!c.storeId.equals(KgJdbc.asLong(a.get("jobStore"))))
        {
            throw new ServiceException("接单记录不属于当前门店", 404);
        }
        Long uid = KgJdbc.asLong(a.get("appUserId"));
        if (c.uid().equals(uid))
        {
            throw new ServiceException("不能给自己的接单发素材，请由本店其他员工处理", 403);
        }
        String status = str(a.get("status"));
        if (!KgOnlineTaskService.WORKING.contains(status))
        {
            throw new ServiceException("submitted".equals(status) ? "报名还没通过，通过（接单）后才能发素材" : "报名已撤回或结束，不能再发素材", 409);
        }
        Map<String, Object> user = db.one("select status, del_flag from kg_app_user where user_id = :u", p("u", uid));
        if (user == null || !"0".equals(str(user.get("status"))) || !"0".equals(str(user.get("delFlag"))))
        {
            throw new ServiceException("接单账号已注销或不可用，不能再发素材", 409);
        }
        Map<String, Object> settle = db.one("select status from kg_online_settlement where app_id = :id", p("id", appId));
        if (settle != null && "confirmed".equals(str(settle.get("status"))))
        {
            throw new ServiceException("这单已经结算完成，不能再发素材", 409);
        }
        return a;
    }

    /** 请求体里的 files：[fileId…]，去重；不是正整数的报 422 */
    static List<Long> fileIds(Object raw)
    {
        List<Long> out = new ArrayList<Long>();
        if (raw == null)
        {
            return out;
        }
        if (!(raw instanceof List))
        {
            throw new ServiceException("文件参数不正确，请重新添加文件", 422);
        }
        for (Object o : (List<?>) raw)
        {
            String s = o instanceof Number ? String.valueOf(((Number) o).longValue()) : str(o).trim();
            if (!s.matches("[1-9][0-9]{0,17}"))
            {
                throw new ServiceException("文件参数不正确，请重新添加文件", 422);
            }
            Long id = Long.valueOf(s);
            if (!out.contains(id))
            {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 发素材。调用前控制器已按「线上任务·编辑」把过关（与验收同一档权限）。
     * 只有接单之后、结算确认之前可以发；不能给自己的接单发。
     * body = {content: 文字（1 万字内）, images: [平台图片地址，最多 20 张], files: [先上传好的附件 ID，最多 5 个]}，至少有一样。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> send(StaffSupport.Ctx c, Long appId, Map<String, Object> body)
    {
        String content = str(body == null ? null : body.get("content")).trim();
        List<String> images = new ArrayList<String>();
        Object rawImages = body == null ? null : body.get("images");
        if (rawImages instanceof List)
        {
            for (Object o : (List<?>) rawImages)
            {
                String url = str(o).trim();
                if (url.isEmpty())
                {
                    continue;
                }
                if (!KgJdbc.isPlatformImage(url))
                {
                    throw new ServiceException("素材图片只能用本平台上传的图片", 422);
                }
                if (!images.contains(url))
                {
                    images.add(url);
                }
            }
        }
        List<Long> fileIds = fileIds(body == null ? null : body.get("files"));
        if (content.isEmpty() && images.isEmpty() && fileIds.isEmpty())
        {
            throw new ServiceException("请填写素材说明，或添加图片、文件", 422);
        }
        if (content.length() > MAX_CONTENT)
        {
            throw new ServiceException("素材说明太长了（超过 1 万字），请把长文放进文档作为附件发送", 422);
        }
        if (images.size() > MAX_IMAGES)
        {
            throw new ServiceException("一次最多发 " + MAX_IMAGES + " 张图片", 422);
        }
        if (fileIds.size() > MAX_FILES)
        {
            throw new ServiceException("一次最多发 " + MAX_FILES + " 个文件", 422);
        }
        String joined = String.join(",", images);
        if (joined.length() > MAX_IMAGES_LEN)
        {
            throw new ServiceException("图片太多了，请分两次发送", 422);
        }
        Map<String, Object> a = assertCanSend(c, appId);
        Long uid = KgJdbc.asLong(a.get("appUserId"));
        if (db.count("select count(*) from kg_online_material where app_id = :id and del_flag = '0'", p("id", appId)) >= MAX_PER_ASSIGNMENT)
        {
            throw new ServiceException("这位接单人已收到 " + MAX_PER_ASSIGNMENT + " 次素材，请合并后再发，或私信沟通", 409);
        }
        if (!content.isEmpty() && db.count("select coalesce(sum(char_length(content)), 0) from kg_online_material where app_id = :id and del_flag = '0'",
                p("id", appId)) + content.length() > MAX_CONTENT_TOTAL)
        {
            throw new ServiceException("发给这位接单人的文字已经很多了（累计超过 10 万字），请把长文放进文档作为附件发送", 409);
        }
        // 违禁词直接拦下（与成果说明同一套词库）；联系方式之类只提示复核的不拦，素材里常有品牌链接和说明
        guard.check(content);
        // 附件逐个锁行核对：属于这个接单和本店、还没发出、没被清理。和每小时的清理互斥，不会发出一个刚被删掉的文件
        for (Long fileId : fileIds)
        {
            Map<String, Object> f = db.one("select file_id, app_id, store_id, material_id, del_flag from kg_online_material_file where file_id = :f for update",
                    p("f", fileId));
            if (f == null || !appId.equals(KgJdbc.asLong(f.get("appId"))) || !c.storeId.equals(KgJdbc.asLong(f.get("storeId")))
                    || !"0".equals(str(f.get("delFlag"))))
            {
                throw new ServiceException("有文件已失效（上传后 24 小时没发出会自动清理），请移除后重新添加", 404);
            }
            if (f.get("materialId") != null)
            {
                throw new ServiceException("有文件已经发过了，请刷新后再试", 409);
            }
        }
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("app_id", appId);
        row.put("job_id", a.get("jobId"));
        row.put("store_id", c.storeId);
        row.put("content", content);
        row.put("images", joined);
        row.put("sent_by_id", c.uid());
        row.put("sent_by", StaffSupport.cut(c.name(), 64));
        row.put("sent_time", new Date());
        row.put("del_flag", "0");
        long id = db.insert("kg_online_material", row);
        for (Long fileId : fileIds)
        {
            if (db.update("update kg_online_material_file set material_id = :m where file_id = :f and material_id is null and del_flag = '0'",
                    p("m", id, "f", fileId)) != 1)
            {
                throw new ServiceException("有文件已经发过或已失效，请刷新后再试", 409);
            }
        }
        String title = str(a.get("title"));
        support.notifyUser(uid, "apply", "门店给你发了任务素材", "「" + title + "」：" + notice(content, images.size(), fileIds.size()), "application", appId,
                "/pages/jobs/deliver?appId=" + appId);
        support.op(c, "online_material", "发送任务素材", "general", StaffSupport.cut(title, 100) + "（" + (content.isEmpty() ? "" : "文字 + ") + images.size() + " 张图"
                + (fileIds.isEmpty() ? "" : " + " + fileIds.size() + " 个文件") + "）", true, null);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("materialId", id);
        m.put("message", "素材已发给接单人");
        return m;
    }

    /**
     * 素材通知正文（任务名之后那段）：有文字放前 60 字，再说附了几张图、几个文件；有文件时提醒到交付页下载。
     * 文件种类不再套一层括号：有文字时写「（附 5 个文件：压缩包/文档）」，没文字时写「门店发来 5 个文件（压缩包/文档）」
     */
    static String notice(String content, int images, int fileCount)
    {
        List<String> parts = new ArrayList<String>();
        if (images > 0)
        {
            parts.add(images + " 张素材图片");
        }
        if (fileCount > 0)
        {
            parts.add(fileCount + " 个文件");
        }
        String preview;
        if (content.isEmpty())
        {
            preview = "门店发来 " + String.join("和 ", parts) + (fileCount > 0 ? "（压缩包/文档）" : "");
        }
        else
        {
            String flat = content.replaceAll("\\s+", " ");
            preview = StaffSupport.cut(flat, 60) + (flat.length() > 60 ? "…" : "")
                    + (parts.isEmpty() ? "" : "（附 " + String.join("、", parts) + (fileCount > 0 ? "：压缩包/文档" : "") + "）");
        }
        return preview + (fileCount > 0 ? "。请到任务交付页查看和下载，按要求完成后提交成果" : "。点开查看，按要求完成后提交成果");
    }

    // ---------------------------------------------------------------- 附件（V41）

    /**
     * 上传一个附件（8MB 以内整个传；更大的见 {@link #partBegin}；先上传、发送时随素材一起发出）。和发送同样的前置检查；再看配额：没发出的不超过 MAX_PENDING_FILES 个、
     * 这个接单累计不超过 300MB、本店当天不超过 1GB（当天上传过又清掉的也算）。返回 {fileId, name, size, sizeLabel, ext, kind, openable}。
     * 并发：先 select … for update 锁住这条接单（kg_application），同一个接单的两次上传排队做配额检查，
     * 之前两个请求同时查到「还差 1 个」都放行，一起超出 MAX_PENDING_FILES / 300MB。
     * 文件先写盘再插行：事务回滚（插行失败，或外层事务后面出错）时由 afterCompletion 删掉已写的文件，不留孤儿文件。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upload(StaffSupport.Ctx c, Long appId, MultipartFile file, String displayName)
    {
        assertCanSend(c, appId);
        KgOnlineMaterialFiles.Checked ok = files.check(file, displayName);
        quota(c, appId, ok.size);
        return insertFile(c, appId, ok, files.write(appId, file, ok.ext));
    }

    /**
     * 配额（整个上传、分段上传开始和传完时都查）：先锁住这条接单排队，再看没发出的个数、这个接单累计、本店当天累计。
     * 分段上传开始时按申报的大小先查一遍，免得传了几十 MB 才被拒；传完再按实际大小查一遍
     */
    void quota(StaffSupport.Ctx c, Long appId, long size)
    {
        db.one("select app_id from kg_application where app_id = :id for update", p("id", appId));
        long pending = db.count("select count(*) from kg_online_material_file where app_id = :id and material_id is null and del_flag = '0'", p("id", appId));
        if (pending >= MAX_PENDING_FILES)
        {
            throw new ServiceException("还有 " + pending + " 个文件没发出去，请先发送或移除，再添加新文件", 409);
        }
        if (db.count("select coalesce(sum(file_size), 0) from kg_online_material_file where app_id = :id and del_flag = '0'", p("id", appId)) + size
                > MAX_BYTES_PER_ASSIGNMENT)
        {
            throw new ServiceException("发给这位接单人的文件累计已超过 300MB，更多内容请把网盘链接写在文字里", 409);
        }
        if (db.count("select coalesce(sum(file_size), 0) from kg_online_material_file where store_id = :s and create_time >= curdate()", p("s", c.storeId)) + size
                > MAX_BYTES_PER_STORE_DAY)
        {
            throw new ServiceException("本店今天上传的文件已超过 1GB，请明天再发，或把网盘链接写在文字里", 429);
        }
    }

    /** 文件已经放进 omat/（key）之后插一行；事务回滚或插行失败都删掉这个文件 */
    private Map<String, Object> insertFile(StaffSupport.Ctx c, Long appId, KgOnlineMaterialFiles.Checked ok, String key)
    {
        deleteOnRollback(key);
        try
        {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("app_id", appId);
            row.put("store_id", c.storeId);
            row.put("file_key", key);
            row.put("file_name", ok.name);
            row.put("file_ext", ok.ext);
            row.put("file_size", ok.size);
            row.put("uploaded_by", c.uid());
            row.put("create_time", new Date());
            row.put("del_flag", "0");
            long id = db.insert("kg_online_material_file", row);
            return KgOnlineMaterialFiles.view(p("fileId", id, "fileName", ok.name, "fileExt", ok.ext, "fileSize", ok.size));
        }
        catch (RuntimeException e)
        {
            files.delete(key);
            throw e;
        }
    }

    /**
     * 大文件分段上传第一步（2026-10-16，超过 8MB 的文件；单个最多 100MB）：和整个上传一样的前置检查，
     * 再按申报的大小先查配额。返回 {uploadId, partBytes, parts}，之后按顺序一段一段传（KgOnlineMaterialFiles.partAppend），最后 {@link #partFinish}
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> partBegin(StaffSupport.Ctx c, Long appId, String name, Object size)
    {
        assertCanSend(c, appId);
        String s = str(size).trim();
        if (!s.matches("[1-9][0-9]{0,17}"))
        {
            throw new ServiceException("文件大小不对，请重新选择", 422);
        }
        long bytes = Long.parseLong(s);
        if (bytes > KgOnlineMaterialFiles.MAX_BYTES)
        {
            throw new ServiceException(KgOnlineMaterialFiles.TOO_BIG, 422);
        }
        quota(c, appId, bytes);
        return files.partBegin(appId, c.uid(), name, bytes);
    }

    /** 分段上传传完：核对总大小和文件头，再查一遍配额，挪进 omat/ 插一行。返回和整个上传一样的 {fileId, name, size, sizeLabel, ext, kind, openable} */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> partFinish(StaffSupport.Ctx c, Long appId, String uploadId)
    {
        assertCanSend(c, appId);
        KgOnlineMaterialFiles.Assembled a = files.partFinish(uploadId, appId, c.uid());
        try
        {
            quota(c, appId, a.checked.size);
        }
        catch (RuntimeException e)
        {
            if (!a.file.delete())
            {
                a.file.deleteOnExit();
            }
            throw e;
        }
        return insertFile(c, appId, a.checked, files.adopt(appId, a.file, a.checked.ext));
    }

    /** 事务回滚后删掉已经写到磁盘上的附件（没有事务时由调用处的 catch 负责） */
    private void deleteOnRollback(final String key)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
        {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            @Override
            public void afterCompletion(int status)
            {
                if (status == STATUS_ROLLED_BACK)
                {
                    files.delete(key);
                }
            }
        });
    }

    /** 移除一个还没发出的附件（记 del_flag）；返回文件键，事务提交后由调用方删磁盘文件。已经发出的不能移除 */
    @Transactional(rollbackFor = Exception.class)
    public String removePending(StaffSupport.Ctx c, Long appId, Long fileId)
    {
        Map<String, Object> f = db.one("select file_id, app_id, store_id, material_id, del_flag, file_key from kg_online_material_file where file_id = :f for update",
                p("f", fileId));
        if (f == null || !appId.equals(KgJdbc.asLong(f.get("appId"))) || !c.storeId.equals(KgJdbc.asLong(f.get("storeId"))) || !"0".equals(str(f.get("delFlag"))))
        {
            throw new ServiceException("文件不存在或已被清理", 404);
        }
        if (f.get("materialId") != null)
        {
            throw new ServiceException("这个文件已经发给接单人了，不能移除", 409);
        }
        db.update("update kg_online_material_file set del_flag = '2' where file_id = :f and material_id is null and del_flag = '0'", p("f", fileId));
        return str(f.get("fileKey"));
    }

    private static final String FILE_COLS = "select file_id, app_id, store_id, material_id, file_key, file_name, file_ext, file_size from kg_online_material_file ";

    /** 门店下载：本店这个接单的附件（没发出的也能下载核对）。调用前控制器已核对本店「线上任务·查看」 */
    public Map<String, Object> storeFile(StaffSupport.Ctx c, Long appId, Long fileId)
    {
        Map<String, Object> f = db.one(FILE_COLS + "where file_id = :f and app_id = :a and del_flag = '0'", p("f", fileId, "a", appId));
        if (f == null || !c.storeId.equals(KgJdbc.asLong(f.get("storeId"))))
        {
            throw new ServiceException("文件不存在或已被清理", 404);
        }
        return f;
    }

    /** 接单人下载：只能下自己接单里已经发出的附件；接单撤回或结束后仍可下载（素材仍保留） */
    public Map<String, Object> workerFile(Long appId, Long fileId)
    {
        AppLoginUser me = AppUserContext.require();
        Map<String, Object> a = db.one("select a.app_user_id from kg_application a join kg_job j on j.job_id = a.job_id "
                + "where a.app_id = :id and a.del_flag = '0' and j.job_type = 'online'", p("id", appId));
        if (a == null || !me.getUserId().equals(KgJdbc.asLong(a.get("appUserId"))))
        {
            throw new ServiceException("文件不存在", 404);
        }
        Map<String, Object> f = db.one(FILE_COLS + "where file_id = :f and app_id = :a and material_id is not null and del_flag = '0'", p("f", fileId, "a", appId));
        if (f == null)
        {
            throw new ServiceException("文件不存在或已被清理", 404);
        }
        return f;
    }

    /** 签名下载链接（3 天内有效，拿到链接的人都能下载），只发给已经发出的附件：{url（相对后端根）, expireAt, hours} */
    public Map<String, Object> link(Map<String, Object> f)
    {
        if (f.get("materialId") == null)
        {
            throw new ServiceException("文件还没发给接单人，发出后才能复制下载链接", 409);
        }
        long exp = System.currentTimeMillis() / 1000 + KgOnlineMaterialFiles.LINK_TTL_SECONDS;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("url", files.sign(KgJdbc.asLong(f.get("fileId")), exp));
        m.put("expireAt", StaffSupport.fmt(new Date(exp * 1000), "yyyy-MM-dd HH:mm"));
        m.put("hours", KgOnlineMaterialFiles.LINK_TTL_SECONDS / 3600);
        return m;
    }

    /** 匿名签名链接：签名对、没过期，而且这个附件还在（删了链接就失效）；不满足返回 null */
    public Map<String, Object> signedFile(long fileId, long exp, String sig)
    {
        if (!files.verify(fileId, exp, sig))
        {
            return null;
        }
        return db.one(FILE_COLS + "where file_id = :f and material_id is not null and del_flag = '0'", p("f", fileId));
    }

    /** kg_online_material 的行 → 页面数据，按发送先后。员工端显示是谁发的，接单人只看到门店名 */
    public static List<Map<String, Object>> rows(List<Map<String, Object>> raw, boolean staffView, String storeName)
    {
        return rows(raw, staffView, storeName, null);
    }

    /**
     * 同上，再把随每次素材发出的附件（kg_online_material_file 已发出的行，按 material_id 归到对应那次素材）挂到 files 上：
     * [{fileId, name, size, sizeLabel, ext, kind, openable}]。没有附件时是空列表（老页面不认这一项也不受影响）
     */
    public static List<Map<String, Object>> rows(List<Map<String, Object>> raw, boolean staffView, String storeName, List<Map<String, Object>> fileRows)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (raw == null)
        {
            return out;
        }
        Map<Long, List<Map<String, Object>>> byMaterial = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (Map<String, Object> f : fileRows == null ? new ArrayList<Map<String, Object>>() : fileRows)
        {
            Long mid = KgJdbc.asLong(f.get("materialId"));
            if (mid == null || KgJdbc.asLong(f.get("fileId")) == null)
            {
                continue;
            }
            List<Map<String, Object>> list = byMaterial.get(mid);
            if (list == null)
            {
                list = new ArrayList<Map<String, Object>>();
                byMaterial.put(mid, list);
            }
            list.add(KgOnlineMaterialFiles.view(f));
        }
        for (Map<String, Object> r : raw)
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("materialId", r.get("materialId"));
            m.put("content", str(r.get("content")));
            List<Map<String, Object>> attached = byMaterial.get(KgJdbc.asLong(r.get("materialId")));
            m.put("files", attached == null ? new ArrayList<Map<String, Object>>() : attached);
            List<String> images = new ArrayList<String>();
            for (String x : str(r.get("images")).split(","))
            {
                if (!x.trim().isEmpty())
                {
                    images.add(x.trim());
                }
            }
            m.put("images", images);
            m.put("sentBy", staffView ? (str(r.get("sentBy")).isEmpty() ? "门店员工" : r.get("sentBy")) : (storeName == null || storeName.isEmpty() ? "门店" : storeName));
            m.put("sentTime", r.get("sentTime"));
            m.put("time", str(r.get("sentTime")).length() >= 16 ? str(r.get("sentTime")).substring(0, 16) : str(r.get("sentTime")));
            out.add(m);
        }
        return out;
    }

    /** 'yyyy-MM-dd HH:mm[:ss]' 或 'MM-dd HH:mm' → 'MM-dd'；认不出返回空串 */
    static String md(Object v)
    {
        String s = str(v).trim();
        if (s.matches("^\\d{4}-\\d{2}-\\d{2}.*"))
        {
            return s.substring(5, 10);
        }
        return s.matches("^\\d{2}-\\d{2}.*") ? s.substring(0, 5) : "";
    }

    static final List<String> ENDED = Arrays.asList("withdrawn", "terminated", "review_rejected", "user_rejected");

    /** 'yyyy-MM-dd HH:mm[:ss]' 或 'MM-dd HH:mm' → 'MM-dd HH:mm'（精确到分钟）；认不出返回空串 */
    static String minute(Object v)
    {
        String s = str(v).trim().replace('T', ' ');
        if (s.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}.*"))
        {
            return s.substring(5, 16);
        }
        return s.matches("^\\d{2}-\\d{2} \\d{2}:\\d{2}.*") ? s.substring(0, 11) : "";
    }

    /**
     * 这条报名有没有被门店接过单（审核通过过），只看记录、不看现在的状态。
     * view() 传进来的接单时间取自报名日志里第一次「审核通过」的时间；查不到这条记录时，它退回成报名时间。
     * 所以接单时间和报名时间不在同一分钟，就说明审核通过过。按状态机（见 ApplicationStatus）：「已终止」只能从审核通过之后走到，算接过单；
     * 「审核未通过」只能从刚报名时走到，没接过单。极少数报名后一分钟内就被通过、之后又撤回的，分不出来，按没接单显示（和之前一样）。
     */
    static boolean passedBefore(String appStatus, Object submitTime, Object takenTime)
    {
        if ("terminated".equals(appStatus))
        {
            return true;
        }
        if ("review_rejected".equals(appStatus))
        {
            return false;
        }
        String taken = minute(takenTime);
        return !taken.isEmpty() && !taken.equals(minute(submitTime));
    }

    /**
     * 交付页 / 进度页的步骤条：报名 → 已接单 → 素材已发 → 已交付 → 验收通过 → 结算（待打款 / 已登记打款 / 已到账）。
     * 全部由现有状态推出来（报名状态、素材发送时间、成果轮次、结算单），不新增任何状态。
     *
     * 返回 {steps: [{key, label, time, state}], current, ended, tip}：
     *   current 与 k-steps 组件同一口径 —— 走到的最后一步（1–6），结算完成为 7（全部打勾），报名撤回 / 终止为 0；
     *   state 是给交付页自己画步骤条用的：done 完成 / current 当前 / todo 未到 / skipped 跳过（没发素材就交付了）/ off 流程已结束。
     *
     * @param takenTime   接单时间（view() 已格式化成 MM-dd HH:mm；没有审核通过的记录时 view() 退回成报名时间）
     * @param materials   rows() 的结果，按发送先后
     * @param subs        成果轮次，按轮次倒序（第 0 个是最新一轮）
     * @param settlement  结算单原始行，没有为 null
     */
    public static Map<String, Object> flow(String appStatus, Object submitTime, Object takenTime, List<Map<String, Object>> materials,
            List<Map<String, Object>> subs, Map<String, Object> settlement)
    {
        String app = appStatus == null ? "" : appStatus;
        String settle = settlement == null ? "" : str(settlement.get("status"));
        boolean hasSettle = !settle.isEmpty();
        boolean hasMaterial = materials != null && !materials.isEmpty();
        boolean delivered = subs != null && !subs.isEmpty();
        boolean working = KgOnlineTaskService.WORKING.contains(app);
        // 之前只看现在的状态：接过单、后来撤回或被终止的（还没发素材、没交付），「已接单」显示成没走到、也没有日期。
        // 现在报名已结束时再看审核通过的记录（passedBefore），接过单的「已接单」保持打勾并带上接单日期
        boolean taken = working || hasSettle || delivered || hasMaterial || (ENDED.contains(app) && passedBefore(app, submitTime, takenTime));
        // 没有结算单时，撤回 / 终止 / 未通过 就是流程结束了；有结算单的照常走结算（已有结算仍可按原流程处理）
        boolean ended = !hasSettle && ENDED.contains(app);
        boolean rejected = ended && !taken && "review_rejected".equals(app);
        String latest = delivered ? str(subs.get(0).get("status")) : "";
        int reached = hasSettle ? 5 : delivered ? 4 : hasMaterial ? 3 : taken ? 2 : 1;
        int current;
        if ("confirmed".equals(settle))
        {
            current = 7;
        }
        else if ("payout_registered".equals(settle) || "disputed".equals(settle))
        {
            current = 6;
        }
        else if (ended)
        {
            current = rejected ? 2 : 0;
        }
        else
        {
            current = reached;
        }
        String settleLabel = "pending_payout".equals(settle) ? "待打款" : "payout_registered".equals(settle) ? "已登记打款"
                : "confirmed".equals(settle) ? "已到账" : "disputed".equals(settle) ? "申诉中" : "结算";
        String settleTime = "confirmed".equals(settle) ? md(settlement.get("confirmedTime"))
                : "payout_registered".equals(settle) ? md(settlement.get("registeredTime")) : "";
        String[] keys = { "apply", "taken", "material", "delivered", "accepted", "settle" };
        String[] labels = { "报名", rejected ? "未通过" : "已接单", hasMaterial || reached < 3 ? "素材已发" : "未发素材",
                "returned".equals(latest) && !hasSettle ? "待修改" : "已交付", "验收通过", settleLabel };
        String[] times = { md(submitTime), taken ? md(takenTime) : "", hasMaterial ? md(materials.get(0).get("sentTime")) : "",
                delivered ? md(subs.get(subs.size() - 1).get("time")) : "", hasSettle ? md(settlement.get("createTime")) : "", settleTime };
        List<Map<String, Object>> steps = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < keys.length; i++)
        {
            int n = i + 1;
            String state;
            if (ended && !rejected)
            {
                state = n <= reached ? "done" : "off";
            }
            else if (rejected)
            {
                state = n == 1 ? "done" : n == 2 ? "current" : "off";
            }
            else
            {
                state = n < current ? "done" : n == current ? "current" : "todo";
            }
            if (n == 3 && !hasMaterial && reached > 3)
            {
                state = "skipped";
            }
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("key", keys[i]);
            s.put("label", labels[i]);
            s.put("time", times[i]);
            s.put("state", state);
            steps.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("steps", steps);
        out.put("current", current);
        out.put("ended", ended);
        out.put("tip", tip(app, ended, rejected, current, hasMaterial, latest, settle));
        return out;
    }

    static String tip(String app, boolean ended, boolean rejected, int current, boolean hasMaterial, String latest, String settle)
    {
        if (rejected)
        {
            return "这次报名没有通过，任务没有接成";
        }
        if (ended)
        {
            return "报名已结束，已提交的成果和收到的素材仍保留在这里";
        }
        if ("confirmed".equals(settle))
        {
            return "你已确认收款，这单任务完成啦";
        }
        if ("payout_registered".equals(settle))
        {
            return "门店已登记打款，核对到账后请点「确认收款」";
        }
        if ("disputed".equals(settle))
        {
            return "你已申诉没收到款，平台会介入核实";
        }
        if ("pending_payout".equals(settle))
        {
            return "验收通过了，等门店按约定方式打款";
        }
        if ("submitted".equals(app))
        {
            return "等门店审核你的报名，通过就算接单成功";
        }
        if ("submitted".equals(latest))
        {
            return "成果已提交，等门店验收";
        }
        if ("returned".equals(latest))
        {
            return "成果被退回修改，看看原因后重新提交";
        }
        return hasMaterial ? "门店素材已发，按要求完成后在下方提交成果" : "门店还没发素材，可以先私信门店沟通；不等素材也可以直接提交成果";
    }
}
