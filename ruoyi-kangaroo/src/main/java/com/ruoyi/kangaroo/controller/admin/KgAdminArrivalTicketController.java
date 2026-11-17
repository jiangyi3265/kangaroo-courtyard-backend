package com.ruoyi.kangaroo.controller.admin;

import java.io.File;
import java.nio.file.Files;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.kangaroo.ext.core.KgArrivalTickets;

@RestController
@RequestMapping("/kangaroo/arrival/{appId}/ticket")
public class KgAdminArrivalTicketController {
    @Autowired private KgArrivalTickets files;
    @PreAuthorize("@ss.hasPermi('kangaroo:arrival:query')")
    @GetMapping
    public void download(@PathVariable Long appId, @RequestParam("file") String ref, HttpServletResponse response) throws Exception {
        File f=files.readAdmin(appId,ref);
        String ext=f.getName().substring(f.getName().lastIndexOf('.')+1);
        response.setContentType("image/"+(("jpg".equals(ext)||"jpeg".equals(ext))?"jpeg":ext));
        response.setHeader("Cache-Control","private, no-store");
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setContentLengthLong(f.length());
        Files.copy(f.toPath(),response.getOutputStream());
    }
}
