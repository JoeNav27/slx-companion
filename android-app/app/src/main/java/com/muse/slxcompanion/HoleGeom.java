package com.muse.slxcompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hole preview geometry from OpenStreetMap, fetched through the
 * Overpass API — free, keyless, no account; the same ODbL data
 * golfclubxy is built on (golfclubxy itself serves only tee/green
 * points, no shapes).
 *
 * For each course we fetch every golf-tagged way/relation in the
 * course's bounding box once, cache the raw JSON on the phone, and
 * assign each feature (green, bunker, tee, water, fairway, cartpath)
 * to the hole whose mapped hole-line (golf=hole) it sits nearest.
 * Hole-lines are matched to the course's holes by endpoint distance
 * (tee/green), NOT by their OSM ref text — refs can be anything
 * ("Creek 4", "Stone House 4" at 27-hole facilities), which a plain
 * number parse would miss entirely. The result is a per-hole shape
 * the HoleView draws as a visual diagram — tee at the bottom, green
 * at the top. Purely a picture: nothing here feeds distances or the
 * device display.
 */
public class HoleGeom {

    public static class Shape {
        public final List<double[]> line = new ArrayList<>(); // lat,lon spine, tee -> green
        public final List<List<double[]>> greens = new ArrayList<>();
        public final List<List<double[]>> bunkers = new ArrayList<>();
        public final List<List<double[]>> tees = new ArrayList<>();
        public final List<List<double[]>> waters = new ArrayList<>();
        public final List<List<double[]>> fairways = new ArrayList<>();
        public final List<List<double[]>> paths = new ArrayList<>();

        public boolean hasArt() {
            return !line.isEmpty() || !greens.isEmpty() || !fairways.isEmpty();
        }
    }

    public final Map<Integer, Shape> byHole = new HashMap<>();

    private Shape shapeFor(int ref) {
        Shape s = byHole.get(ref);
        if (s == null) {
            s = new Shape();
            byHole.put(ref, s);
        }
        return s;
    }

    // ------------------------------------------------------------- fetch

    public static String fetch(double south, double west, double north, double east) throws Exception {
        try {
            return fetchFrom("https://overpass-api.de/api/interpreter", south, west, north, east);
        } catch (Exception primaryFailure) {
            // Public mirror, same API — the main instance is often loaded.
            return fetchFrom("https://overpass.kumi.systems/api/interpreter", south, west, north, east);
        }
    }

    private static String fetchFrom(String url, double south, double west, double north, double east) throws Exception {
        String ql = "[out:json][timeout:60];\n(\n"
                + "  way[\"golf\"](" + south + "," + west + "," + north + "," + east + ");\n"
                + "  relation[\"golf\"](" + south + "," + west + "," + north + "," + east + ");\n"
                + ");\nout geom;\n";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setRequestProperty("User-Agent", "SLX-Companion/0.15");
        c.setRequestProperty("Accept", "application/json");
        c.setDoOutput(true);
        byte[] body = ("data=" + URLEncoder.encode(ql, "UTF-8")).getBytes("UTF-8");
        OutputStream os = c.getOutputStream();
        os.write(body);
        os.close();
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("Overpass HTTP " + code);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }

    // ------------------------------------------------------------- parse

    private static class Feature {
        String kind;
        List<double[]> pts;
        boolean closed;
    }

    public static HoleGeom parse(String json, CourseApi.Course course) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray els = root.getJSONArray("elements");
        HoleGeom out = new HoleGeom();
        List<Feature> feats = new ArrayList<>();
        List<List<double[]>> holeLines = new ArrayList<>();

        for (int i = 0; i < els.length(); i++) {
            JSONObject e = els.getJSONObject(i);
            JSONObject tags = e.optJSONObject("tags");
            if (tags == null) continue;
            String golf = tags.optString("golf", "");
            if (golf.isEmpty()) continue;
            String type = e.optString("type", "");
            if ("way".equals(type)) {
                List<double[]> pts = readGeom(e.optJSONArray("geometry"));
                if (pts.size() < 2) continue;
                if ("hole".equals(golf)) {
                    holeLines.add(pts);
                } else {
                    Feature f = new Feature();
                    f.kind = golf;
                    f.pts = pts;
                    f.closed = pts.size() > 2 && samePoint(pts.get(0), pts.get(pts.size() - 1));
                    feats.add(f);
                }
            } else if ("relation".equals(type)) {
                // Multipolygons (most fairways/greens in well-mapped areas):
                // take the outer rings.
                JSONArray members = e.optJSONArray("members");
                if (members == null) continue;
                for (int m = 0; m < members.length(); m++) {
                    JSONObject mem = members.getJSONObject(m);
                    if (!"outer".equals(mem.optString("role", "outer"))) continue;
                    List<double[]> pts = readGeom(mem.optJSONArray("geometry"));
                    if (pts.size() < 3) continue;
                    Feature f = new Feature();
                    f.kind = golf;
                    f.pts = pts;
                    f.closed = true;
                    feats.add(f);
                }
            }
        }

        // Match hole-lines to the course's holes by endpoint distance:
        // the line whose ends sit closest to the hole's tee and green
        // is that hole's line (whichever way round it was drawn).
        for (CourseApi.Hole h : course.holes) {
            if (h.teeLat == 0 && h.teeLon == 0) continue;
            double[] tee = {h.teeLat, h.teeLon};
            double[] grn = {h.greenLat, h.greenLon};
            List<double[]> best = null;
            boolean reversed = false;
            double bestScore = Double.MAX_VALUE;
            for (List<double[]> line : holeLines) {
                double s1 = distMeters(tee, line.get(0)) + distMeters(grn, line.get(line.size() - 1));
                double s2 = distMeters(tee, line.get(line.size() - 1)) + distMeters(grn, line.get(0));
                if (s1 < bestScore) { bestScore = s1; best = line; reversed = false; }
                if (s2 < bestScore) { bestScore = s2; best = line; reversed = true; }
            }
            if (best == null || bestScore > 250) continue;
            Shape s = out.shapeFor(h.number);
            if (reversed) {
                for (int i = best.size() - 1; i >= 0; i--) s.line.add(best.get(i));
            } else {
                s.line.addAll(best);
            }
        }

        // Assign each feature to the hole line it hugs.
        for (Feature f : feats) {
            double[] c = centroid(f.pts);
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (Map.Entry<Integer, Shape> en : out.byHole.entrySet()) {
                if (en.getValue().line.isEmpty()) continue;
                double d = distToPolylineMeters(c, en.getValue().line);
                if (d < bestD) {
                    bestD = d;
                    best = en.getKey();
                }
            }
            if (best < 0 || bestD > thresholdMeters(f.kind)) continue;
            Shape s = out.byHole.get(best);
            switch (f.kind) {
                case "green": s.greens.add(f.pts); break;
                case "bunker": s.bunkers.add(f.pts); break;
                case "tee": s.tees.add(f.pts); break;
                case "water_hazard": s.waters.add(f.pts); break;
                case "fairway": if (f.closed) s.fairways.add(f.pts); break;
                case "cartpath": s.paths.add(f.pts); break;
                default: break;
            }
        }
        return out;
    }

    private static double thresholdMeters(String kind) {
        switch (kind) {
            case "green": return 70;
            case "tee": return 70;
            case "bunker": return 100;
            case "fairway": return 90;
            case "water_hazard": return 140;
            case "cartpath": return 140;
            default: return 80;
        }
    }

    private static List<double[]> readGeom(JSONArray g) {
        List<double[]> pts = new ArrayList<>();
        if (g == null) return pts;
        for (int i = 0; i < g.length(); i++) {
            JSONObject p = g.optJSONObject(i);
            if (p == null) continue;
            pts.add(new double[]{p.optDouble("lat"), p.optDouble("lon")});
        }
        return pts;
    }

    private static double distMeters(double[] a, double[] b) {
        double kx = 111320.0 * Math.cos(Math.toRadians(a[0]));
        double dx = (a[1] - b[1]) * kx;
        double dy = (a[0] - b[0]) * 110540.0;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static boolean samePoint(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9;
    }

    private static double[] centroid(List<double[]> pts) {
        double lat = 0, lon = 0;
        for (double[] p : pts) {
            lat += p[0];
            lon += p[1];
        }
        return new double[]{lat / pts.size(), lon / pts.size()};
    }

    /**
     * Distance in meters from a point to a lat/lon polygon: 0 when
     * the point is inside, else the distance to the nearest edge.
     * Used for the GPS screen's hazard distances (water / bunker).
     */
    public static double distToPolygonMeters(double lat, double lon, List<double[]> poly) {
        if (poly == null || poly.size() < 3) return Double.MAX_VALUE;
        if (pointInPolygon(lat, lon, poly)) return 0;
        List<double[]> ring = new ArrayList<>(poly);
        if (!samePoint(ring.get(0), ring.get(ring.size() - 1))) ring.add(ring.get(0));
        return distToPolylineMeters(new double[]{lat, lon}, ring);
    }

    private static boolean pointInPolygon(double lat, double lon, List<double[]> poly) {
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            double xi = poly.get(i)[1], yi = poly.get(i)[0];
            double xj = poly.get(j)[1], yj = poly.get(j)[0];
            if ((yi > lat) != (yj > lat)
                    && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Distance in meters from a point to a lat/lon polyline (local flat-earth). */
    private static double distToPolylineMeters(double[] pt, List<double[]> line) {
        double kx = 111320.0 * Math.cos(Math.toRadians(pt[0]));
        double ky = 110540.0;
        double px = pt[1] * kx, py = pt[0] * ky;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line.size(); i++) {
            double ax = line.get(i)[1] * kx, ay = line.get(i)[0] * ky;
            double bx = line.get(i + 1)[1] * kx, by = line.get(i + 1)[0] * ky;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
            double ex = ax + t * dx - px, ey = ay + t * dy - py;
            double d = Math.sqrt(ex * ex + ey * ey);
            if (d < best) best = d;
        }
        return best;
    }
}
