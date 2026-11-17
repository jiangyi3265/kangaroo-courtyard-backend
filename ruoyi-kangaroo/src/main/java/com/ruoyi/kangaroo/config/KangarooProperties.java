package com.ruoyi.kangaroo.config;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.utils.StringUtils;

/**
 * 袋鼠小院业务配置。
 *
 * 微信 AppID / AppSecret 不写在代码和配置文件里，只从环境变量读取：
 *   KANGAROO_WX_APPID / KANGAROO_WX_SECRET
 * 未配置时微信登录接口会明确返回"未配置"错误，而不是静默降级成假登录。
 */
@Component
@ConfigurationProperties(prefix = "kangaroo")
public class KangarooProperties
{
    private static final Logger log = LoggerFactory.getLogger(KangarooProperties.class);

    @Autowired
    private Environment environment;

    /** 微信小程序 AppID，来自环境变量 KANGAROO_WX_APPID */
    @Value("${KANGAROO_WX_APPID:}")
    private String wxAppId;

    /** 微信小程序 AppSecret，来自环境变量 KANGAROO_WX_SECRET */
    @Value("${KANGAROO_WX_SECRET:}")
    private String wxAppSecret;

    /** Official code target; never silently switch a production code to trial. */
    @Value("${kangaroo.wx.code-env:release}")
    private String wxCodeEnv = "release";

    /**
     * 本地联调开关：为 true 时 code2session 不调用微信服务器，
     * 而是把 code 直接当作 openid 使用，用于在没有微信凭证的环境里跑通端到端链路。
     *
     * 默认 false。生产环境必须保持 false —— 开启后任何人都能用任意 code 冒充任意账号。
     * 启动时有两道判据，任一不满足就让应用启动失败：
     *   1. active profile 是 prod / production；
     *   2. 数据源不是本机（localhost / 127.0.0.1）。
     * 第 2 条才是本项目里真正生效的那道 —— 见 check() 里的说明。
     */
    @Value("${kangaroo.wx.mock-enabled:false}")
    private boolean wxMockEnabled;

    /** 商家确认录用的时限（小时），页面文案为 24 小时 */
    @Value("${kangaroo.confirm-deadline-hours:24}")
    private int confirmDeadlineHours;

    /**
     * 在线支付渠道：off（缺省，不开通）/ mock（本地联调，确认即到账）/ wechat（真实微信支付，需商户号与证书）。
     * mock 只允许与微信登录联调模式同时开启：check() 里校验，那个模式本身只允许连本机库。
     */
    @Value("${kangaroo.pay.mode:off}")
    private String payMode;

    /**
     * 社区内容审核方式：manual（默认）= 帖子、评论、活动、行程全部先进人工审核队列；
     * mock = 本地敏感词过了就直接发布，模拟"已接入内容安全接口"，只允许在本地联调模式下开启；
     * auto（2026-10-16 内测反馈 2.0，生产可用）= 评论、不带图的旅居日常 / 经验分享，本地检查通过、微信内容安全接口
     * （msgSecCheck v2）也判通过，就直接发布；接口出错、超时、判有风险一律照旧进人工审核。带图的动态、找搭子、活动、行程仍然人工审核。
     * 本地联调模式下 auto 不连微信，用 WxMsgSecCheck 的模拟判定。
     */
    @Value("${kangaroo.content.review-mode:manual}")
    private String contentReviewMode;

    /** auto 模式下调用微信内容安全接口的最长等待（毫秒），超时按「转人工」处理 */
    @Value("${kangaroo.content.sec-check-timeout-ms:2000}")
    private long contentSecCheckTimeoutMs = 2000L;

    /**
     * 短信验证码通道：none（缺省，不开通，手机号只能走微信手机号快捷授权核验）/ mock（本地联调，验证码固定在返回里给出）。
     * mock 同样只允许与微信登录联调模式同时开启。
     */
    @Value("${kangaroo.sms.mode:none}")
    private String smsMode;

    /**
     * 实名核验通道：none（缺省，不开通，页面如实显示未开通）/ mock（本地联调，只校验证件号格式与校验位，不连任何外部核验）。
     */
    @Value("${kangaroo.realname.mode:none}")
    private String realNameMode;

    /**
     * 微信订阅消息通道：none 不发（默认）/ mock 本地只记录不外发 / wx 真实调用微信接口。
     * wx 需要小程序 appid/secret 与公众平台申请到的模板ID（后台「订阅消息模板」里配）。
     */
    @Value("${kangaroo.subscribe.mode:none}")
    private String subscribeMode;

    @Value("${kangaroo.subscribe.mini-program-state:formal}")
    private String subscribeMiniProgramState;

    public String getSubscribeMiniProgramState(){return subscribeMiniProgramState==null?"formal":subscribeMiniProgramState;}

    /**
     * 当前数据源是不是指向本机。
     *
     * 只看主库地址里的主机名。取不到地址时返回 false —— 判不出来就当成"不是本机"，
     * 宁可让开了 mock 的实例起不来，也不要在一个不知道连着哪里的库上放开身份伪造。
     */
    private boolean pointsAtLocalDatabase()
    {
        String url = environment.getProperty("spring.datasource.druid.master.url");
        if (url == null || url.trim().isEmpty())
        {
            url = environment.getProperty("spring.datasource.url");
        }
        return isLocalJdbcUrl(url);
    }

    /**
     * JDBC 连接串是不是指向本机。
     *
     * 抽成静态方法是为了能被单元测试直接断言 —— 这道守卫替换掉的那一道
     * （只在 prod profile 下抛）之所以形同虚设，正是因为从来没有测试碰过它。
     *
     * 取不到地址、解析不出主机名，一律返回 false：判不出来就当成"不是本机"，
     * 宁可让开了 mock 的实例起不来，也不要在一个不知道连着哪里的库上放开身份伪造。
     */
    static boolean isLocalJdbcUrl(String url)
    {
        if (url == null || url.trim().isEmpty())
        {
            return false;
        }
        String lower = url.trim().toLowerCase();
        int at = lower.indexOf("//");
        if (at < 0)
        {
            return false;
        }
        String rest = lower.substring(at + 2);
        // 连接串里可能带 user:pass@host 形式的凭证，主机名是最后一个 @ 之后的部分
        int credential = rest.lastIndexOf('@');
        if (credential >= 0)
        {
            rest = rest.substring(credential + 1);
        }
        String host;
        if (rest.startsWith("["))
        {
            // 方括号形式的 IPv6 地址：主机名里本来就带冒号，不能按第一个冒号切。
            // 单元测试就是在这里抓到上一版实现把 [::1] 切成了 "["。
            int close = rest.indexOf(']');
            host = close > 0 ? rest.substring(0, close + 1) : "";
        }
        else
        {
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++)
            {
                char c = rest.charAt(i);
                if (c == ':' || c == '/' || c == '?' || c == ',')
                {
                    end = i;
                    break;
                }
            }
            host = rest.substring(0, end);
        }
        if (host.isEmpty())
        {
            return false;
        }
        return "localhost".equals(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
    }

    @PostConstruct
    public void check()
    {
        if (!"release".equals(wxCodeEnv) && !"trial".equals(wxCodeEnv) && !"develop".equals(wxCodeEnv))
        {
            throw new IllegalStateException("kangaroo.wx.code-env 只能是 release / trial / develop");
        }
        if (!"off".equals(payMode) && !"mock".equals(payMode) && !"wechat".equals(payMode))
        {
            throw new IllegalStateException("kangaroo.pay.mode 只能是 off / mock / wechat，当前：" + payMode);
        }
        if (!"manual".equals(contentReviewMode) && !"mock".equals(contentReviewMode) && !"auto".equals(contentReviewMode))
        {
            throw new IllegalStateException("kangaroo.content.review-mode 只能是 manual / mock / auto，当前：" + contentReviewMode);
        }
        if ("auto".equals(contentReviewMode) && !wxMockEnabled && !isWxConfigured())
        {
            // 不拦启动：没有微信凭证时机审调不通，所有内容照旧进人工审核，和 manual 一样，不会放出没审过的内容
            log.warn("kangaroo.content.review-mode=auto 但没有配置 KANGAROO_WX_APPID / KANGAROO_WX_SECRET：微信内容安全检查调不通，评论和动态会照旧全部进人工审核");
        }
        if ("mock".equals(contentReviewMode) && !wxMockEnabled)
        {
            throw new IllegalStateException("kangaroo.content.review-mode=mock 只允许在本地联调模式（kangaroo.wx.mock-enabled=true）下开启");
        }
        if (!"none".equals(smsMode) && !"mock".equals(smsMode))
        {
            throw new IllegalStateException("kangaroo.sms.mode 只能是 none / mock，当前：" + smsMode);
        }
        if (!"none".equals(realNameMode) && !"mock".equals(realNameMode))
        {
            throw new IllegalStateException("kangaroo.realname.mode 只能是 none / mock，当前：" + realNameMode);
        }
        if ("mock".equals(smsMode) && !wxMockEnabled)
        {
            throw new IllegalStateException("kangaroo.sms.mode=mock 只允许在本地联调模式（kangaroo.wx.mock-enabled=true）下开启");
        }
        if ("mock".equals(realNameMode) && !wxMockEnabled)
        {
            throw new IllegalStateException("kangaroo.realname.mode=mock 只允许在本地联调模式（kangaroo.wx.mock-enabled=true）下开启");
        }
        if (!"none".equals(subscribeMode) && !"mock".equals(subscribeMode) && !"wx".equals(subscribeMode))
        {
            throw new IllegalStateException("kangaroo.subscribe.mode 只能是 none / mock / wx，当前：" + subscribeMode);
        }
        if(!java.util.Arrays.asList("formal","trial","developer").contains(getSubscribeMiniProgramState()))throw new IllegalStateException("订阅消息跳转版本只能是 formal / trial / developer");
        if ("mock".equals(subscribeMode) && !wxMockEnabled)
        {
            throw new IllegalStateException("kangaroo.subscribe.mode=mock 只允许在本地联调模式（kangaroo.wx.mock-enabled=true）下开启");
        }
        if ("wx".equals(subscribeMode) && !isWxConfigured())
        {
            // 真发订阅消息要调微信接口，没有 appid/secret 就是发不出去，早点报错好过上线后静默丢消息
            throw new IllegalStateException("kangaroo.subscribe.mode=wx 需要先配置 KANGAROO_WX_APPID / KANGAROO_WX_SECRET");
        }
        if ("mock".equals(payMode) && !wxMockEnabled)
        {
            // 联调支付"点一下就到账"，只能出现在本地联调环境；借用微信登录联调模式那道本机库防线
            throw new IllegalStateException("kangaroo.pay.mode=mock 只允许在本地联调模式（kangaroo.wx.mock-enabled=true）下开启");
        }
        if (wxMockEnabled)
        {
            String[] profiles = environment.getActiveProfiles();
            for (String p : profiles)
            {
                if ("prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p))
                {
                    throw new IllegalStateException(
                            "kangaroo.wx.mock-enabled=true 不允许在 " + p + " profile 下启用：这会让任意 code 冒充任意账号");
                }
            }

            // 上面那条按 profile 判的防线，在本项目里**一次也不会触发** ——
            // 若依靠 `druid` 这个 profile 加载数据源，active profile 永远是 druid，
            // prod / production 从来不会出现。只留它等于没有防线。
            //
            // 所以再加一条失败关闭的判据：开了 mock 就必须连着本机数据库。
            // 这条判据和"这是不是生产"强相关，而且难以误配 ——
            // 可以忘记设 profile，但不会不小心把生产库地址写成 localhost。
            if (!pointsAtLocalDatabase())
            {
                throw new IllegalStateException(
                        "kangaroo.wx.mock-enabled=true 只允许在连接本机数据库时启用："
                        + "当前数据源不是 localhost/127.0.0.1，拒绝启动。"
                        + "开启后任何人都能用任意 code 冒充任意账号。");
            }
            log.warn("==========================================================");
            log.warn("  微信登录处于本地联调模式（kangaroo.wx.mock-enabled=true）");
            log.warn("  code 将被直接当作 openid，不会校验微信身份。");
            log.warn("  仅限本地端到端验证使用，上线前必须关闭。");
            log.warn("==========================================================");
        }
        else if (StringUtils.isEmpty(wxAppId) || StringUtils.isEmpty(wxAppSecret))
        {
            log.warn("未配置 KANGAROO_WX_APPID / KANGAROO_WX_SECRET 环境变量，微信登录接口将返回未配置错误");
        }
    }

    public boolean isWxConfigured()
    {
        return StringUtils.isNotEmpty(wxAppId) && StringUtils.isNotEmpty(wxAppSecret);
    }

    public String getWxAppId() { return wxAppId; }
    public String getWxCodeEnv() { return wxCodeEnv; }
    public void setWxCodeEnv(String value) { wxCodeEnv = value; }
    public void setWxAppId(String wxAppId) { this.wxAppId = wxAppId; }
    public String getWxAppSecret() { return wxAppSecret; }
    public void setWxAppSecret(String wxAppSecret) { this.wxAppSecret = wxAppSecret; }
    public boolean isWxMockEnabled() { return wxMockEnabled; }
    public void setWxMockEnabled(boolean wxMockEnabled) { this.wxMockEnabled = wxMockEnabled; }
    public int getConfirmDeadlineHours() { return confirmDeadlineHours; }
    public String getPayMode() { return payMode; }
    public String getContentReviewMode() { return contentReviewMode; }
    public void setContentReviewMode(String value) { contentReviewMode = value; }
    public long getContentSecCheckTimeoutMs() { return contentSecCheckTimeoutMs; }
    public void setContentSecCheckTimeoutMs(long value) { contentSecCheckTimeoutMs = value; }

    public String getSmsMode()
    {
        return smsMode;
    }

    public String getSubscribeMode()
    {
        return subscribeMode;
    }

    public String getRealNameMode()
    {
        return realNameMode;
    }
    public void setPayMode(String payMode) { this.payMode = payMode; }
    public void setConfirmDeadlineHours(int confirmDeadlineHours) { this.confirmDeadlineHours = confirmDeadlineHours; }
}
