package com.ruoyi;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 启动程序
 * 
 * @author ruoyi
 */
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class RuoYiApplication
{
    /** 默认业务时区。部署到别的时区时用 -Dkangaroo.timezone 或环境变量 KANGAROO_TIMEZONE 覆盖。 */
    static final String DEFAULT_TIME_ZONE = "Asia/Shanghai";

    /**
     * 在任何 Spring 组件初始化之前钉死 JVM 默认时区。
     *
     * 放在代码里而不是只靠启动脚本传参：脚本会被漏掉（rebuild-and-run.sh 就漏过），
     * 而连接池、Jackson、new Date() 都依赖 JVM 默认时区，不一致会写出错时的业务时间。
     *
     * 覆盖用的属性名是 kangaroo.timezone，**不能用 user.timezone**：
     * JVM 启动时总会把 user.timezone 设成操作系统时区，它永远非空，
     * 拿它当"用户是否指定过"的判据，下面的默认值就永远生效不了，
     * JVM 会静默退回本机时区。本机是 UTC-8，那就是 16 小时的偏差，而且不报错。
     *
     * TimeZone.getTimeZone(String) 对认不出来的 ID 会**静默返回 GMT**，
     * 所以这里先校验一次，拼错时直接启动失败，而不是悄悄偏 8 小时。
     */
    static void pinDefaultTimeZone()
    {
        String id = System.getProperty("kangaroo.timezone");
        if (id == null || id.trim().isEmpty())
        {
            id = System.getenv("KANGAROO_TIMEZONE");
        }
        if (id == null || id.trim().isEmpty())
        {
            id = DEFAULT_TIME_ZONE;
        }
        id = id.trim();
        if (!"GMT".equals(id) && "GMT".equals(TimeZone.getTimeZone(id).getID()))
        {
            throw new IllegalStateException(
                    "无法识别的时区 ID: " + id + "，请检查 -Dkangaroo.timezone。"
                    + "若确实要用 GMT 请显式写 -Dkangaroo.timezone=GMT。");
        }
        TimeZone.setDefault(TimeZone.getTimeZone(id));
        System.setProperty("user.timezone", id);
    }

    public static void main(String[] args)
    {
        pinDefaultTimeZone();

        // System.setProperty("spring.devtools.restart.enabled", "false");
        SpringApplication.run(RuoYiApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  若依启动成功   ლ(´ڡ`ლ)ﾞ  \n" +
                " .-------.       ____     __        \n" +
                " |  _ _   \\      \\   \\   /  /    \n" +
                " | ( ' )  |       \\  _. /  '       \n" +
                " |(_ o _) /        _( )_ .'         \n" +
                " | (_,_).' __  ___(_ o _)'          \n" +
                " |  |\\ \\  |  ||   |(_,_)'         \n" +
                " |  | \\ `'   /|   `-'  /           \n" +
                " |  |  \\    /  \\      /           \n" +
                " ''-'   `'-'    `-..-'              ");
    }
}
