package com.ruoyi.kangaroo.ext.user;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Offline administrative containment. Missing/ambiguous locations never become a nearby city. */
final class CityBoundaryIndex
{
    private static final class Holder { static final CityBoundaryIndex INSTANCE = new CityBoundaryIndex(); }
    static CityBoundaryIndex instance() { return Holder.INSTANCE; }
    private final List<Area> areas = new ArrayList<>();

    private CityBoundaryIndex()
    {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream resource = getClass().getResourceAsStream("/geo/china-boundaries.json.gz"))
        {
            if (resource == null) throw new IllegalStateException("Missing city boundary data");
            try (JsonParser parser = mapper.getFactory().createParser(new GZIPInputStream(resource)))
            {
                if (parser.nextToken() != JsonToken.START_ARRAY) throw new IllegalStateException("Invalid city boundary data");
                while (parser.nextToken() == JsonToken.START_OBJECT)
                {
                    JsonNode node = mapper.readTree(parser);
                    Area area = new Area();
                    area.code = node.get("code").asText();
                    area.district = "district".equals(node.get("level").asText());
                    for (int i = 0; i < 4; i++) area.box[i] = node.get("bbox").get(i).asDouble();
                    JsonNode polys = node.get("polygons");
                    area.polygons = new double[polys.size()][][];
                    for (int p = 0; p < polys.size(); p++)
                    {
                        JsonNode rings = polys.get(p);
                        area.polygons[p] = new double[rings.size()][];
                        for (int r = 0; r < rings.size(); r++)
                        {
                            JsonNode points = rings.get(r);
                            double[] ring = new double[points.size() * 2];
                            for (int i = 0; i < points.size(); i++)
                            {
                                ring[2*i] = points.get(i).get(0).asDouble();
                                ring[2*i+1] = points.get(i).get(1).asDouble();
                            }
                            area.polygons[p][r] = ring;
                        }
                    }
                    areas.add(area);
                }
            }
        }
        catch (Exception e) { throw new IllegalStateException("Cannot load city boundary data", e); }
    }

    String locate(double lat, double lng, double accuracy)
    {
        Area district = null, city = null;
        boolean districtOverlap = false, cityOverlap = false;
        for (Area area : areas)
        {
            if (!area.contains(lng, lat)) continue;
            if (area.district) { districtOverlap |= district != null; district = area; }
            else { cityOverlap |= city != null; city = area; }
        }
        // Coarse boundary simplification and device accuracy must not imply an exact city.
        Area found = district != null ? district : city;
        return (district != null ? districtOverlap : cityOverlap) || found == null
                || found.nearBoundary(lng, lat, Math.max(150, accuracy)) ? null : found.code;
    }

    static final class Area
    {
        String code;
        boolean district;
        final double[] box = new double[4];
        double[][][] polygons;
        boolean contains(double x, double y)
        {
            if (x < box[0] || y < box[1] || x > box[2] || y > box[3]) return false;
            for (double[][] polygon : polygons)
            {
                if (polygon.length == 0 || !insideRing(polygon[0], x, y)) continue;
                boolean hole = false;
                for (int r = 1; r < polygon.length; r++) if (insideRing(polygon[r], x, y)) hole = true;
                if (!hole) return true;
            }
            return false;
        }
        boolean nearBoundary(double x, double y, double meters)
        {
            double sx = 111320 * Math.cos(Math.toRadians(y)), sy = 111320;
            for (double[][] polygon : polygons) for (double[] ring : polygon)
                for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2)
                {
                    double ax=(ring[j]-x)*sx, ay=(ring[j+1]-y)*sy;
                    double bx=(ring[i]-x)*sx, by=(ring[i+1]-y)*sy;
                    double dx=bx-ax, dy=by-ay, len=dx*dx+dy*dy;
                    double t=len==0 ? 0 : Math.max(0,Math.min(1,-(ax*dx+ay*dy)/len));
                    double px=ax+t*dx, py=ay+t*dy;
                    if (px*px+py*py <= meters*meters) return true;
                }
            return false;
        }
    }

    static boolean insideRing(double[] ring, double x, double y)
    {
        boolean inside = false;
        for (int i=0,j=ring.length-2; i<ring.length; j=i,i+=2)
            if ((ring[i+1]>y)!=(ring[j+1]>y)
                    && x < (ring[j]-ring[i])*(y-ring[i+1])/(ring[j+1]-ring[i+1])+ring[i]) inside=!inside;
        return inside;
    }

    // WGS84 -> GCJ02 compatibility for older app builds, adapted from wandergis/coordtransform (MIT).
    // New app builds request GCJ02 directly from the platform.
    static double[] gcj(double lat, double lng)
    {
        if (lng<72.004 || lng>137.8347 || lat<0.8293 || lat>55.8271) return new double[]{lat,lng};
        double x=lng-105, y=lat-35, pi=Math.PI;
        double a=-100+2*x+3*y+0.2*y*y+0.1*x*y+0.2*Math.sqrt(Math.abs(x));
        a+=(20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3;
        a+=(20*Math.sin(y*pi)+40*Math.sin(y*pi/3))*2/3;
        a+=(160*Math.sin(y*pi/12)+320*Math.sin(y*pi/30))*2/3;
        double b=300+x+2*y+0.1*x*x+0.1*x*y+0.1*Math.sqrt(Math.abs(x));
        b+=(20*Math.sin(6*x*pi)+20*Math.sin(2*x*pi))*2/3;
        b+=(20*Math.sin(x*pi)+40*Math.sin(x*pi/3))*2/3;
        b+=(150*Math.sin(x*pi/12)+300*Math.sin(x*pi/30))*2/3;
        double rad=lat*pi/180, sin=Math.sin(rad), magic=1-0.00669342162296594323*sin*sin, sqrt=Math.sqrt(magic);
        return new double[]{lat+a*180/((6378245*(1-0.00669342162296594323))/(magic*sqrt)*pi),
                lng+b*180/(6378245/sqrt*Math.cos(rad)*pi)};
    }
}
