package com.muse.slxcompanion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * golfclubxy course data — free, keyless, OpenStreetMap-based.
 *
 *   GET https://golfclubxy.willconia.com/courses?q=<name>&limit=10
 *   GET https://golfclubxy.willconia.com/courses/<osm_id>
 *
 * A course is downloaded once and cached on the phone (raw JSON in the
 * app's private files); after that the GPS screen works fully offline.
 * Green coordinates are centers of the mapped green polygon — the
 * right reference for yardage, not a pin position. ODbL attribution:
 * (c) OpenStreetMap contributors.
 */
public class CourseApi {

    private static final String BASE = "https://golfclubxy.willconia.com";

    public static class CourseRef {
        public long id;
        public String name;
        public String tier;
        public String status;
        public int holes;
    }

    public static class Hole {
        public int number;
        public int par;
        public int handicap;
        public double teeLat, teeLon, greenLat, greenLon;
        public double yards; // API hole yardage (tee -> green)
    }

    public static class Course {
        public long id;
        public String name;
        public String tier;
        public final List<Hole> holes = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "SLX-Companion/0.2");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }

    public static List<CourseRef> search(String query) throws Exception {
        String json = httpGet(BASE + "/courses?q=" + URLEncoder.encode(query, "UTF-8") + "&limit=10");
        JSONObject root = new JSONObject(json);
        JSONArray results = root.getJSONArray("results");
        List<CourseRef> out = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            CourseRef ref = new CourseRef();
            ref.id = o.getLong("osm_id");
            ref.name = o.optString("name", "(unnamed)");
            ref.tier = o.optString("tier", "?");
            ref.status = o.optString("status", "");
            ref.holes = o.optInt("n_holes", 0);
            out.add(ref);
        }
        return out;
    }

    /** Downloads the full course and returns the raw JSON (for caching). */
    public static String fetchCourseJson(long id) throws Exception {
        return httpGet(BASE + "/courses/" + id);
    }

    public static Course parseCourse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        Course course = new Course();
        course.id = root.getLong("osm_id");
        course.name = root.optString("name", "(unnamed)");
        course.tier = root.optString("tier", "?");
        JSONArray warns = root.optJSONArray("warnings");
        if (warns != null) {
            for (int i = 0; i < warns.length(); i++) course.warnings.add(warns.getString(i));
        }
        JSONArray holes = root.getJSONArray("holes");
        for (int i = 0; i < holes.length(); i++) {
            JSONObject o = holes.getJSONObject(i);
            JSONObject green = o.optJSONObject("green");
            if (green == null) continue; // no green mapped — unusable for distances
            Hole h = new Hole();
            h.number = o.optInt("number", course.holes.size() + 1);
            h.par = o.optInt("par", 0);
            h.handicap = o.optInt("handicap", 0);
            h.greenLat = green.getDouble("lat");
            h.greenLon = green.getDouble("lon");
            JSONObject tee = o.optJSONObject("tee");
            if (tee != null) {
                h.teeLat = tee.getDouble("lat");
                h.teeLon = tee.getDouble("lon");
            }
            h.yards = o.optDouble("yards", 0);
            course.holes.add(h);
        }
        if (course.holes.isEmpty()) throw new Exception("Course has no mapped greens");
        return course;
    }
}
