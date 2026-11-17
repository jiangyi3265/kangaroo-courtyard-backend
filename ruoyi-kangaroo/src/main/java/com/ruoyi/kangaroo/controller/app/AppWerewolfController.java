package com.ruoyi.kangaroo.controller.app;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.werewolf.WerewolfEngine;
import com.ruoyi.kangaroo.ext.werewolf.WerewolfService;
import com.ruoyi.kangaroo.security.AppAuth;
import com.ruoyi.kangaroo.security.AppRateLimiter;
import com.ruoyi.kangaroo.security.AppUserContext;
import com.ruoyi.kangaroo.security.WxMiniAppClient;

@RestController
@RequestMapping("/app/werewolf")
public class AppWerewolfController
{
    @Autowired private WerewolfService games;
    @Autowired private AppRateLimiter limiter;
    @Autowired private WxMiniAppClient wx;
    @Autowired private javax.servlet.http.HttpServletRequest request;

    private void requireCompatible(Map<String, Object> room)
    {
        if (room != null && ((room.get("playerCount") instanceof Number && ((Number) room.get("playerCount")).intValue() > 6)
                || "face_to_face".equals(room.get("playMode")))) {
            if (request == null || !"2".equals(request.getParameter("rulesVersion")))
                throw new ServiceException("这个房间使用新版玩法，请更新小程序后重新进入", 426);
        }
    }

    @AppAuth
    @PostMapping("/rooms")
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        limiter.check("werewolf:create", String.valueOf(AppUserContext.getUserId()), 20, 3600, "开房次数较多，请稍后再试");
        return AjaxResult.success(games.create(AppUserContext.require(), WerewolfEngine.string(body, "requestId")));
    }

    @AppAuth
    @GetMapping("/mine")
    @SuppressWarnings("unchecked")
    public AjaxResult mine() {
        Map<String, Object> mine = games.mine(AppUserContext.getUserId());
        requireCompatible((Map<String, Object>) mine.get("room"));
        return AjaxResult.success(mine);
    }

    @AppAuth
    @GetMapping("/history")
    public AjaxResult history() { return AjaxResult.success(games.history(AppUserContext.getUserId())); }

    @AppAuth(required = false)
    @GetMapping("/rooms/{code}")
    public AjaxResult room(@PathVariable("code") String code)
    {
        limiter.checkByIp("werewolf:read", 1200, 60, "房间读取过于频繁，请稍后再试");
        Map<String, Object> room = games.get(code, AppUserContext.currentUserId());
        requireCompatible(room);
        return AjaxResult.success(room);
    }

    @AppAuth
    @PostMapping("/rooms/{code}/{action}")
    public AjaxResult action(@PathVariable("code") String code, @PathVariable("action") String action, @RequestBody Map<String, Object> body)
    {
        if (!Arrays.asList("join", "seat", "ready", "settings", "leave", "kick", "start", "aim", "act", "endSpeech", "prepareSkip", "cancelSkip", "extendSpeech", "message", "replay").contains(action))
            throw new ServiceException("操作不存在", 404);
        limiter.check("werewolf:action", String.valueOf(AppUserContext.getUserId()), 120, 60, "操作较快，请稍后再试");
        if (!"leave".equals(action)) requireCompatible(games.get(code, AppUserContext.getUserId()));
        if ("settings".equals(action)) requireCompatible(body);
        return AjaxResult.success(games.command(code, AppUserContext.require(), action, body));
    }

    @AppAuth
    @PostMapping("/rooms/{code}/rtc")
    public ResponseEntity<AjaxResult> rtc(@PathVariable("code") String code, @RequestBody Map<String, Object> body)
    {
        // Keep a safe response for older clients; real-time credentials are no longer issued.
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(AjaxResult.success(WerewolfEngine.map("available", false, "canListen", false,
                        "canPublish", false, "message", "已改为录音语音消息，请更新小程序后使用按住录音、点击播放")));
    }

    @AppAuth
    @GetMapping("/rooms/{code}/results/{gameId}")
    public AjaxResult result(@PathVariable("code") String code, @PathVariable("gameId") String gameId)
    {
        Map<String, Object> result = games.result(code, gameId, AppUserContext.getUserId());
        requireCompatible(result);
        return AjaxResult.success(result);
    }

    @AppAuth
    @GetMapping("/rooms/{code}/invite-code")
    public AjaxResult inviteCode(@PathVariable("code") String code, @RequestParam(name = "env", required = false) String env)
    {
        Map<String, Object> room = games.get(code, AppUserContext.getUserId());
        if (room.get("me") == null) throw new ServiceException("请先加入房间", 403);
        return AjaxResult.success(wx.werewolfCode(code, env));
    }

    @AppAuth
    @PostMapping("/rooms/{code}/voice")
    public AjaxResult voice(@PathVariable("code") String code, @RequestParam("file") MultipartFile file,
            @RequestParam("requestId") String requestId, @RequestParam("phaseToken") String phaseToken, @RequestParam("duration") int duration) throws java.io.IOException
    {
        limiter.check("werewolf:voice", String.valueOf(AppUserContext.getUserId()), 20, 60, "语音发送较多，请稍后再试");
        if (file.getSize() > 700_000) throw new ServiceException("语音文件不能超过700KB", 400);
        byte[] bytes = file.getBytes();
        String mime = audioMime(bytes);
        return AjaxResult.success(games.audio(code, AppUserContext.require(), WerewolfEngine.map(
                "requestId", requestId, "phaseToken", phaseToken, "duration", duration), bytes, mime));
    }

    @AppAuth
    @GetMapping("/rooms/{code}/voice/{id}")
    public ResponseEntity<byte[]> voiceData(@PathVariable("code") String code, @PathVariable("id") String id)
    {
        String value = games.audioData(code, id, AppUserContext.getUserId());
        int at = value.indexOf(';');
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").contentType(MediaType.parseMediaType(value.substring(0, at)))
                .body(Base64.getDecoder().decode(value.substring(at + 1)));
    }

    private String audioMime(byte[] b)
    {
        if (b.length >= 12)
        {
            if ((b[0] == 'I' && b[1] == 'D' && b[2] == '3') || ((b[0] & 255) == 255 && (b[1] & 224) == 224)) return "audio/mpeg";
            if (b[0] == 0x1a && b[1] == 0x45 && b[2] == (byte) 0xdf && b[3] == (byte) 0xa3) return "audio/webm";
            if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "audio/mp4";
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A') return "audio/wav";
        }
        throw new ServiceException("录音格式不支持，请重新录制", 400);
    }
}
