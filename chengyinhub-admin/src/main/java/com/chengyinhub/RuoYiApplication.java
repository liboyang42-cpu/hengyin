package com.chengyinhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * 启动程序
 * 
 * @author ruoyi
 */
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class RuoYiApplication
{
    public static void main(String[] args)
    {
        // System.setProperty("spring.devtools.restart.enabled", "false");
        SpringApplication.run(RuoYiApplication.class, args);
        // F-L4-07:Quartz cron、@Scheduled、LocalDate.now() 与周榜边界都按 JVM 默认时区,启动自检必须留痕。
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        org.slf4j.Logger startup = org.slf4j.LoggerFactory.getLogger(RuoYiApplication.class);
        if ("Asia/Shanghai".equals(zone.getId())) {
            startup.info("JVM default time zone={}", zone.getId());
        } else {
            startup.warn("JVM default time zone={} is not Asia/Shanghai; scheduled jobs and weekly boundaries will shift", zone.getId());
        }
        System.out.println("(♥◠‿◠)ﾉﾞ  城瘾API启动成功   ლ(´ڡ`ლ)ﾞ  \n" +
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
