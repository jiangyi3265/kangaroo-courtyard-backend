package com.ruoyi.kangaroo.controller;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.community.KgChatFiles;
import com.ruoyi.kangaroo.ext.community.KgChatService;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialFiles;
import com.ruoyi.kangaroo.ext.staff.KgOnlineMaterialService;
import com.ruoyi.kangaroo.security.AppRateLimiter;

/**
 * 私有文件的签名链接下载：只认本服务签发、未过期的签名，不看登录态（小程序 image 组件与后台新窗口都带不了令牌）。
 */
@RestController
public class KgPrivateFileController
{
    @Autowired
    private KgPrivateFiles files;

    @Autowired
    private KgOnlineMaterialService materials;

    @Autowired
    private KgOnlineMaterialFiles materialFiles;

    @Autowired
    private AppRateLimiter limiter;

    @Autowired
    private KgChatService chats;

    @Autowired
    private KgChatFiles chatFiles;

    @Anonymous
    @GetMapping("/kangaroo/file/private")
    public void download(@RequestParam("k") String key, @RequestParam("e") long exp, @RequestParam("s") String sig,
            HttpServletResponse response) throws IOException
    {
        // 同一个 IP 每小时最多 600 次（后台审核页、员工端一屏会加载多张证照，比素材下载放宽）：签名链接外泄时不至于被无限刷流量
        try
        {
            limiter.checkByIp("file-private", 600, 3600, "访问太频繁了，请稍后再试");
        }
        catch (ServiceException e)
        {
            response.sendError(429, "访问太频繁了，请稍后再试");
            return;
        }
        File f = files.verify(key, exp, sig) ? files.resolve(key) : null;
        if (f == null)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "链接无效或已过期");
            return;
        }
        String name = f.getName();
        response.setContentType(name.endsWith(".pdf") ? "application/pdf" : (name.endsWith(".png") ? "image/png" : "image/jpeg"));
        response.setHeader("Cache-Control", "private, max-age=300");
        // 不让浏览器按内容猜类型：只按上面给的图片 / PDF 类型处理
        response.setHeader("X-Content-Type-Options", "nosniff");
        // 在页面里直接显示；文件名只用磁盘上的 uuid 名（resolve 已按「32 位十六进制.jpg/png/pdf」核对过），不带用户上传时的原名
        String safe = name.matches("[0-9a-f]{32}\\.(jpg|png|pdf)") ? name : "file";
        response.setHeader("Content-Disposition", "inline; filename=\"" + safe + "\"");
        response.setContentLengthLong(f.length());
        Files.copy(f.toPath(), response.getOutputStream());
    }

    /**
     * 线上任务素材附件（V41）的下载链接：接单人或门店在小程序里「复制下载链接」，3 天内用浏览器打开就能下载。
     * 签名不对、过期、附件已被删除都回 403；同一个 IP 每小时最多 120 次，链接外泄时不至于被无限刷流量。
     */
    @Anonymous
    @GetMapping("/kangaroo/file/material")
    public void material(@RequestParam("f") long fileId, @RequestParam("e") long exp, @RequestParam("s") String sig,
            HttpServletResponse response) throws IOException
    {
        try
        {
            limiter.checkByIp("file-material", 120, 3600, "下载太频繁了，请稍后再试");
        }
        catch (ServiceException e)
        {
            response.sendError(429, "下载太频繁了，请稍后再试");
            return;
        }
        Map<String, Object> row = materials.signedFile(fileId, exp, sig);
        if (row == null || materialFiles.resolve(String.valueOf(row.get("fileKey"))) == null)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "链接无效或已过期");
            return;
        }
        materialFiles.serve(row, response);
    }

    /**
     * 私信文件（V46）的下载链接：会话里的人「复制下载链接」，3 天内用浏览器打开就能下载。
     * 链接认人：签名里带着复制链接的人，下载时按这个人重新核对（还在会话里、没被拉黑、账号正常、文件还在），不满足回 403；
     * 签名不对、过期也回 403。同一个 IP 每小时最多 120 次。
     */
    @Anonymous
    @GetMapping("/kangaroo/file/chat")
    public void chat(@RequestParam("m") long msgId, @RequestParam("u") long uid, @RequestParam("e") long exp, @RequestParam("s") String sig,
            HttpServletResponse response) throws IOException
    {
        try
        {
            limiter.checkByIp("file-chat", 120, 3600, "下载太频繁了，请稍后再试");
        }
        catch (ServiceException e)
        {
            response.sendError(429, "下载太频繁了，请稍后再试");
            return;
        }
        Map<String, Object> row;
        try
        {
            row = chats.signedFile(msgId, uid, exp, sig);
        }
        catch (ServiceException e)
        {
            row = null;
        }
        if (row == null || chatFiles.resolve(String.valueOf(row.get("fileKey"))) == null)
        {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "链接无效或已过期");
            return;
        }
        chatFiles.serve(row, response);
    }
}
