package com.ruoyi.kangaroo.ext.core;

import java.util.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** New application reviews use four dimensions; old five-value arrays retain their original meaning. */
public final class KgJobReviewDimensions
{
    public static final String[] LABELS = { "位置", "氛围", "工作", "吃住" };
    private KgJobReviewDimensions() {}

    public static boolean legacy(Object raw) { return parts(raw).length == 5; }
    private static String[] parts(Object raw)
    {
        return raw == null ? new String[0] : raw.toString().replaceAll("[\\[\\]\\s]", "").split(",");
    }
    public static int[] scores(Object raw)
    {
        String[] parts = parts(raw);
        int[] out = new int[4];
        if (parts.length != 4 && parts.length != 5) return out;
        int[] indices = parts.length == 5 ? new int[] { 4, 2, 1, 0 } : new int[] { 0, 1, 2, 3 };
        for (int i = 0; i < 4; i++)
        {
            try { int n = Integer.parseInt(parts[indices[i]]); if (n >= 1 && n <= 5) out[i] = n; }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }
    public static List<Map<String, Object>> aggregate(List<Map<String, Object>> rows)
    {
        double[] sum = new double[4]; int[] count = new int[4];
        for (Map<String, Object> row : rows)
        {
            int[] scores = scores(row.get("dims"));
            for (int i = 0; i < 4; i++) if (scores[i] > 0) { sum[i] += scores[i]; count[i]++; }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) out.add(p("label", LABELS[i], "count", count[i], "avg", count[i] == 0 ? null : Math.round(sum[i] / count[i] * 10) / 10.0));
        return out;
    }
}
