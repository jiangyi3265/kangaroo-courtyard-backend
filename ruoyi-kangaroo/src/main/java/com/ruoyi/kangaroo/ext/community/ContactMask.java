package com.ruoyi.kangaroo.ext.community;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别 / 遮挡文字里的联系方式（手机号、微信号、QQ 号）。私信遮挡、昵称和社区内容检查共用这一套规则。
 *
 * 2026-10-11 复核后重写：之前只认最常见的写法，全角数字、+86、数字中间加空格 / 点 / 零宽字符、「微信号是：」「QQ号是：」、
 * QQ 邮箱、「加V」都能绕过；又会把「1500 1800 200」这种价格、「微信 alipay」这种普通话误遮。现在先把文字归一
 * （全角转半角、去掉零宽字符），手机号忽略数字之间的分隔符再判断，并放过全是整十整百的数字串；微信号在关键词后面
 * 没有「号 / 是 / 为 / 冒号」时，要求号里带数字、下划线或横线，避免把普通英文词当成微信号。
 */
public final class ContactMask
{
    public static final String PHONE_MASK = "[手机号已隐藏]";
    public static final String WECHAT_MASK = "[微信号已隐藏]";
    public static final String QQ_MASK = "[QQ号已隐藏]";

    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");
    /** 关键词 +（可选）号 / 是 / 为 / 冒号 / 空白 + 微信号 */
    // 「wechat id: xxx」「微信 ID：xxx」：关键词后面的 id 也跳过（10-14 回归：简历经历里这样写能绕过遮挡）
    private static final Pattern WECHAT = Pattern.compile("(?i)(?:微信|威信|薇信|v信|wechat|weixin|vx|wx|加v)(?:\\s*id)?(号?)([号是为:：\\s]*)([a-z][-_a-z0-9]{5,19})");
    private static final Pattern QQ = Pattern.compile("(?i)(?:qq|扣扣|企鹅)(?:号|群|群号)?[号是为:：\\s]*[1-9]\\d{4,11}");
    private static final Pattern QQ_MAIL = Pattern.compile("(?i)[1-9]\\d{4,11}\\s*@\\s*qq\\.com");

    private ContactMask()
    {
    }

    /** 文字里有没有联系方式 */
    public static boolean contains(String text)
    {
        return text != null && !text.isEmpty() && !spans(normalize(text)).isEmpty();
    }

    /** 把联系方式换成「[手机号已隐藏]」之类的占位；没有就原样返回 */
    public static String mask(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }
        Normalized n = normalize(text);
        List<int[]> found = spans(n);
        if (found.isEmpty())
        {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int[] s : found)
        {
            int from = n.pos[s[0]];
            int to = n.pos[s[1] - 1] + 1;
            if (from < cursor)
            {
                continue;
            }
            out.append(text, cursor, from).append(s[2] == 0 ? PHONE_MASK : (s[2] == 1 ? WECHAT_MASK : QQ_MASK));
            cursor = to;
        }
        return out.append(text.substring(cursor)).toString();
    }

    // ---------------------------------------------------------------- 内部

    static final class Normalized
    {
        final String text;
        final int[] pos;

        Normalized(String text, int[] pos)
        {
            this.text = text;
            this.pos = pos;
        }
    }

    /** 全角数字 / 字母 / 加号 / 横线 / 斜杠 / 括号转半角，全角空格转空格，去掉零宽字符；pos[i] = 归一后第 i 个字符在原文里的位置 */
    static Normalized normalize(String s)
    {
        StringBuilder b = new StringBuilder(s.length());
        int[] pos = new int[s.length()];
        int k = 0;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '​' || c == '‌' || c == '‍' || c == '⁠' || c == '﻿')
            {
                continue;
            }
            if ((c >= '０' && c <= '９') || (c >= 'Ａ' && c <= 'Ｚ') || (c >= 'ａ' && c <= 'ｚ') || c == '＋' || c == '＠'
                    || c == '－' || c == '／' || c == '（' || c == '）')
            {
                c = (char) (c - 0xFEE0);
            }
            else if (c == '　')
            {
                c = ' ';
            }
            b.append(c);
            pos[k++] = i;
        }
        int[] p = new int[k];
        System.arraycopy(pos, 0, p, 0, k);
        return new Normalized(b.toString(), p);
    }

    /** 手机号数字之间允许的分隔：「138/1234/5678」「(138)1234-5678」这类写法之前能绕过遮挡（10-14 回归） */
    private static boolean separator(char c)
    {
        return c == ' ' || c == '-' || c == '.' || c == '·' || c == '—' || c == '_' || c == '\t' || c == '/' || c == '(' || c == ')';
    }

    /** 找出全部联系方式：{起, 止（不含）, 类型 0 手机 1 微信 2 QQ}，按起点排好、去掉重叠 */
    static List<int[]> spans(Normalized n)
    {
        List<int[]> out = new ArrayList<int[]>();
        String t = n.text;
        Matcher m = QQ_MAIL.matcher(t);
        while (m.find())
        {
            out.add(new int[] { m.start(), m.end(), 2 });
        }
        m = QQ.matcher(t);
        while (m.find())
        {
            out.add(new int[] { m.start(), m.end(), 2 });
        }
        m = WECHAT.matcher(t);
        while (m.find())
        {
            boolean lead = !m.group(1).isEmpty() || m.group(2).replaceAll("\\s", "").length() > 0;
            if (lead || m.group(3).matches(".*[0-9_-].*"))
            {
                out.add(new int[] { m.start(), m.end(), 1 });
            }
        }
        phones(t, out);
        out.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));
        List<int[]> merged = new ArrayList<int[]>();
        for (int[] s : out)
        {
            if (!merged.isEmpty() && s[0] < merged.get(merged.size() - 1)[1])
            {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], s[1]);
                continue;
            }
            merged.add(s);
        }
        return merged;
    }

    /**
     * 手机号：一串数字（中间可以有空格、横线、点、斜杠、括号等分隔）里，按「段」找出连起来正好是 11 位手机号（前面可带 86 / 0086）的连续几段；
     * 全是整十整百的几段数字（像价格）不算。一段里本身超过 11 位的长数字（订单号之类）不拆开看，免得误遮。
     * 10-14 回归：之前把相邻几段数字整串连起来判断，超过 11 位就整串放过，「+86 138.1234.5678 13812345678」
     * 「10/14 138-1234-5678」「138-1234-5678 2026年」里的号码都没遮；现在只遮凑成号码的那几段
     */
    private static void phones(String t, List<int[]> out)
    {
        int i = 0;
        while (i < t.length())
        {
            char c = t.charAt(i);
            boolean plus = c == '+' && i + 1 < t.length() && Character.isDigit(t.charAt(i + 1));
            if (!Character.isDigit(c) && !plus)
            {
                i++;
                continue;
            }
            int j = plus ? i + 1 : i;
            List<String> groups = new ArrayList<String>();
            List<int[]> bounds = new ArrayList<int[]>();
            StringBuilder group = new StringBuilder();
            int groupStart = j;
            int lastDigit = -1;
            while (j < t.length())
            {
                char d = t.charAt(j);
                if (d >= '0' && d <= '9')
                {
                    if (group.length() == 0)
                    {
                        groupStart = j;
                    }
                    group.append(d);
                    lastDigit = j;
                    j++;
                    continue;
                }
                if (separator(d))
                {
                    int k = j;
                    while (k < t.length() && separator(t.charAt(k)) && k - j < 3)
                    {
                        k++;
                    }
                    if (k < t.length() && t.charAt(k) >= '0' && t.charAt(k) <= '9' && group.length() > 0)
                    {
                        groups.add(group.toString());
                        bounds.add(new int[] { groupStart, lastDigit + 1 });
                        group.setLength(0);
                        j = k;
                        continue;
                    }
                }
                break;
            }
            if (group.length() > 0)
            {
                groups.add(group.toString());
                bounds.add(new int[] { groupStart, lastDigit + 1 });
            }
            // 从每一段开始往后连，最先凑成号码的那几段记下来，再从下一段接着找
            int a = 0;
            while (a < groups.size())
            {
                StringBuilder acc = new StringBuilder();
                int hit = -1;
                for (int b = a; b < groups.size() && acc.length() < 15; b++)
                {
                    acc.append(groups.get(b));
                    if (mobile(acc.toString(), groups.subList(a, b + 1)))
                    {
                        hit = b;
                        break;
                    }
                }
                if (hit < 0)
                {
                    a++;
                    continue;
                }
                out.add(new int[] { a == 0 && plus ? i : bounds.get(a)[0], bounds.get(hit)[1], 0 });
                a = hit + 1;
            }
            i = Math.max(lastDigit + 1, i + 1);
        }
    }

    /** 连起来的这几段去掉 86 / 0086 后正好是手机号，而且不像价格（两段以上、每段都以 0 结尾） */
    private static boolean mobile(String d, List<String> groups)
    {
        if (d.length() == 15 && d.startsWith("0086"))
        {
            d = d.substring(4);
        }
        else if (d.length() == 13 && d.startsWith("86"))
        {
            d = d.substring(2);
        }
        if (!MOBILE.matcher(d).matches())
        {
            return false;
        }
        boolean priceLike = groups.size() >= 2;
        for (String g : groups)
        {
            priceLike = priceLike && g.endsWith("0");
        }
        return !priceLike;
    }
}
