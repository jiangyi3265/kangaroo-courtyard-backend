package com.ruoyi.kangaroo.controller;
import java.io.IOException;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.kangaroo.ext.governance.KgMediationFiles;
@RestController public class KgMediationFileController {
    @Autowired private KgMediationFiles files;
    @Anonymous @GetMapping("/kangaroo/file/mediation")
    public void read(@RequestParam("f")Long id,@RequestParam("u")Long uid,@RequestParam("a")String actor,@RequestParam("e")long exp,@RequestParam("s")String signature,@RequestHeader(value="Range",required=false)String range,HttpServletResponse response)throws IOException{files.serve(id,uid,actor,exp,signature,range,response);}
}
