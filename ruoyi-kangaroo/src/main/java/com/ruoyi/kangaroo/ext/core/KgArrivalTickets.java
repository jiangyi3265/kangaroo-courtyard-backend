package com.ruoyi.kangaroo.ext.core;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.AppUserContext;

/** Arrival tickets are private, application-owned files, never public profile uploads. */
@Service
public class KgArrivalTickets
{
    private static final long MAX = 20L * 1024 * 1024;
    private static final String PREFIX = "/private/arrival/";
    @Autowired private KgJdbc db;

    private Map<String, Object> application(Long appId)
    {
        Map<String,Object> result = db.one("select a.app_user_id,a.store_id,a.status,j.job_type from kg_application a join kg_job j on j.job_id=a.job_id "
                + "where a.app_id=:a and a.del_flag='0' and exists(select 1 from kg_app_user u where u.user_id=a.app_user_id and u.del_flag='0')", p("a", appId));
        if (result == null) throw new ServiceException("票据不存在", 404);
        return result;
    }

    private void owner(Map<String, Object> a, Long userId)
    {
        if (!userId.equals(KgJdbc.asLong(a.get("appUserId")))) throw new ServiceException("报名不存在", 404);
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public String save(Long userId, Long appId, MultipartFile file) throws Exception
    {
        com.ruoyi.kangaroo.util.AccountState.requireActive(db, userId);
        Map<String, Object> a = application(appId);
        owner(a, userId);
        if ("online".equals(a.get("jobType")) || !Arrays.asList("store_confirmed", "contract_signed", "onboarded").contains(a.get("status")))
            throw new ServiceException("门店确认录用后才能上传到店票据", 409);
        if (file == null || file.isEmpty() || file.getSize() > MAX) throw new ServiceException("请选择不超过20MB的票据图片", 422);
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        if (!Arrays.asList("jpg", "jpeg", "png", "gif", "webp").contains(ext)) throw new ServiceException("只支持票据图片", 422);
        byte[] magic = new byte[12];
        try (InputStream in = file.getInputStream()) { if (in.read(magic) < 12) throw new ServiceException("票据图片内容不合法", 422); }
        boolean valid = ("jpg".equals(ext) || "jpeg".equals(ext)) ? (magic[0] == (byte)0xff && magic[1] == (byte)0xd8 && magic[2] == (byte)0xff)
                : "png".equals(ext) ? Arrays.equals(Arrays.copyOf(magic, 8), new byte[]{(byte)137,80,78,71,13,10,26,10})
                : "gif".equals(ext) ? (new String(magic, 0, 6, java.nio.charset.StandardCharsets.US_ASCII).matches("GIF8[79]a"))
                : (new String(magic, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(magic, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"));
        if (!valid) throw new ServiceException("票据图片内容与格式不符", 422);
        String ref = PREFIX + appId + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        File target = privateFile(appId, ref);
        Files.createDirectories(target.toPath().getParent());
        file.transferTo(target);
        return ref;
    }

    File privateFile(Long appId, String ref) throws Exception
    {
        if (appId == null || appId <= 0 || ref == null || !ref.matches("/private/arrival/" + appId + "/[0-9a-f]{32}\\.(jpg|jpeg|png|gif|webp)"))
            throw new ServiceException("票据不存在", 404);
        File profile = new File(RuoYiConfig.getProfile()).getAbsoluteFile();
        File base = new File(profile.getParentFile(), "kg-private").getCanonicalFile();
        File result = new File(base, ref.substring("/private/".length())).getCanonicalFile();
        if (!result.toPath().startsWith(base.toPath())) throw new ServiceException("票据不存在", 404);
        return result;
    }

    private String stored(Long appId)
    {
        Map<String,Object> row = db.one("select tickets from kg_app_arrival where app_id=:a", p("a", appId));
        return row == null || row.get("tickets") == null ? "" : KgJdbc.asStr(row.get("tickets"));
    }

    private boolean contains(String refs, String ref)
    {
        return refs != null && ref != null && ("," + refs + ",").contains("," + ref + ",");
    }

    /** Permanently protect every old public reference before account erasure removes the binding. */
    public void protectBeforeRemoval(Long appId)
    {
        protectReferences(stored(appId));
    }

    private void protectReferences(String previous)
    {
        for (String old : previous.split(",(?=/(?:profile/|private/arrival/))"))
            if (publicUpload(old) || publicUpload(legacyIdentity(old))) registerLegacy(old);
    }

    public void validateForSave(Long userId, Long appId, List<String> refs)
    {
        owner(application(appId), userId);
        String previous = stored(appId);
        // Protect old files permanently before a save can remove their last DB reference.
        // Old upload names may contain a comma. Split only at complete resource
        // prefixes, and protect every old public upload, independent of whether
        // its name can safely be accepted by the authenticated image reader.
        protectReferences(previous);
        for (String ref : refs)
        {
            try {
                if (ref.startsWith(PREFIX)) { if (!privateFile(appId, ref).isFile()) throw new ServiceException("票据不存在", 422); }
                else if (!legacy(ref) || !contains(previous, ref)) throw new ServiceException("请使用私有票据上传入口重新上传图片", 422);
            } catch (ServiceException e) { throw e; }
            catch (Exception e) { throw new ServiceException("无法读取票据，请重新上传", 422); }
        }
    }

    static boolean legacy(String ref)
    {
        // Historical CommonController uploads kept original names, including
        // Chinese text, spaces and percent signs. Privacy depends on a bound
        // reference, not an ASCII filename. Keep separators/control characters
        // out; # and ? inside a historical filename are literal characters,
        // always query-encoded by the private reader. No URL suffix is allowed.
        return ref != null && ref.matches("/profile/upload/(?:[0-9]{4}/[0-9]{2}/[0-9]{2}/)?[^/\\\\\\p{Cntrl},]+\\.(?i:jpg|jpeg|png|gif|webp)");
    }

    private static boolean publicUpload(String ref)
    {
        return ref != null && ref.startsWith("/profile/upload/");
    }

    /** Resolve the actual file identity, including Windows case/8.3/dot aliases.
     * Do not lowercase: different case names can be different files on Linux.
     * Missing historical files retain their raw key so old markers still work.
     */
    private String legacyIdentity(String ref)
    {
        if (ref == null || !ref.startsWith("/profile/")) return ref;
        try {
            File base = new File(RuoYiConfig.getProfile()).getCanonicalFile();
            File file = new File(base, ref.substring("/profile/".length()));
            if (!file.isFile()) return ref;
            // File canonicalization removes Win32 trailing characters before
            // converting to Path; toRealPath resolves existing case/short names.
            java.nio.file.Path root = base.toPath().toRealPath();
            java.nio.file.Path actual = file.getCanonicalFile().toPath().toRealPath();
            if (!actual.startsWith(root)) throw new ServiceException("票据不存在", 404);
            return "/profile/" + root.relativize(actual).toString().replace(File.separatorChar, '/');
        } catch (ServiceException e) { throw e; }
        catch (Exception e) { throw new ServiceException("无法保护票据文件，请联系平台处理", 503); }
    }

    public File read(Long userId, Long appId, String ref) throws Exception
    {
        Map<String,Object> a = application(appId);
        boolean own = userId.equals(KgJdbc.asLong(a.get("appUserId")));
        if (!own && (!AppUserContext.require().hasStoreAccess(KgJdbc.asLong(a.get("storeId"))) || !contains(stored(appId), ref)))
            throw new ServiceException("票据不存在", 404);
        File f;
        if (ref != null && ref.startsWith(PREFIX)) f = privateFile(appId, ref);
        else {
            if (!legacy(ref) || !contains(stored(appId), ref)) throw new ServiceException("票据不存在", 404);
            File base = new File(RuoYiConfig.getProfile()).getCanonicalFile();
            f = new File(base, ref.substring("/profile/".length())).getCanonicalFile();
            if (!f.toPath().startsWith(base.toPath())) throw new ServiceException("票据不存在", 404);
        }
        if (!f.isFile()) throw new ServiceException("票据不存在", 404);
        return f;
    }

    /** Admin caller must separately enforce kangaroo:arrival:query. Only bound files are readable. */
    public File readAdmin(Long appId, String ref) throws Exception
    {
        application(appId);
        if (!contains(stored(appId), ref)) throw new ServiceException("票据不存在", 404);
        File f;
        if (ref != null && ref.startsWith(PREFIX)) f = privateFile(appId, ref);
        else {
            if (!legacy(ref)) throw new ServiceException("票据不存在", 404);
            File base = new File(RuoYiConfig.getProfile()).getCanonicalFile();
            f = new File(base, ref.substring("/profile/".length())).getCanonicalFile();
            if (!f.toPath().startsWith(base.toPath())) throw new ServiceException("票据不存在", 404);
        }
        if (!f.isFile()) throw new ServiceException("票据不存在", 404);
        return f;
    }

    /** Called before static resources. Legacy DB references may only use the authenticated reader. */
    public boolean isLegacyPrivate(String path)
    {
        // Privacy must not depend on the reader's filename/format whitelist:
        // all historically bound uploads are private, including awkward names.
        String identity = legacyIdentity(path);
        if (!publicUpload(path) && !publicUpload(identity)) return false;
        // Raw keys remain readable for markers written by the previous version.
        if (legacyMarker(path).isFile() || legacyMarker(identity).isFile()) return true;
        String sql = "select count(*) from kg_app_arrival where locate(concat(',',:f,','),concat(',',tickets,','))>0";
        if (db.count(sql, p("f", path)) == 0
                && (identity.equals(path) || db.count(sql, p("f", identity)) == 0)) return false;
        registerLegacy(path);
        return true;
    }

    private File legacyMarker(String path)
    {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (byte b : h) hex.append(String.format("%02x", b));
            File profile = new File(RuoYiConfig.getProfile()).getAbsoluteFile();
            return new File(new File(profile.getParentFile(), "kg-private/arrival-legacy-block"), hex.toString());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void registerLegacy(String path)
    {
        String identity = legacyIdentity(path);
        writeLegacyMarker(path);
        if (!identity.equals(path)) writeLegacyMarker(identity);
    }

    private void writeLegacyMarker(String path)
    {
        try {
            File marker = legacyMarker(path);
            Files.createDirectories(marker.toPath().getParent());
            if (!marker.isFile()) marker.createNewFile();
            // createNewFile returns false for an existing directory too. Only
            // a real marker file proves protection survived the reference save.
            if (!marker.isFile()) throw new IllegalStateException("Privacy marker is not a file");
        } catch (Exception e) { throw new ServiceException("无法保护票据文件，请联系平台处理", 503); }
    }
}
