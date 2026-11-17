package com.ruoyi.kangaroo.security;

/** 微信 code2session 的返回结果 */
public class WxSession
{
    private String openid;

    private String unionid;

    /** 仅服务端持有，绝不下发给客户端 */
    private String sessionKey;

    /** 是否来自本地联调模式 */
    private boolean mock;

    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public String getUnionid() { return unionid; }
    public void setUnionid(String unionid) { this.unionid = unionid; }
    public String getSessionKey() { return sessionKey; }
    public void setSessionKey(String sessionKey) { this.sessionKey = sessionKey; }
    public boolean isMock() { return mock; }
    public void setMock(boolean mock) { this.mock = mock; }
}
