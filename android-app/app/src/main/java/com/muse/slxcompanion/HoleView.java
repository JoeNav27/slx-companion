package com.muse.slxcompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one hole as an illustrated course-guide picture on a white
 * background: the real mapped fairway polygons striped, green with
 * a fringe ring and flag, rimmed bunkers, water, cartpath — tee at
 * the bottom, green at the top. No rough layer: the user asked for
 * it gone entirely so the hole floats on the white page (2026-10-23,
 * after a brief corridor version). The shapes are the real mapped
 * ones from HoleGeom. Purely a picture, no GPS input.
 */
public class HoleView extends View {

    private static final int BG = 0xFFFFFFFF;
    private static final int FAIRWAY = 0xFF79B34A;
    private static final int GREEN = 0xFFA8D45E;
    private static final int FRINGE = 0xFF4E8A3C;
    private static final int TEE = 0xFF9CCC65;
    private static final int BUNKER = 0xFFF2E2AC;
    private static final int BUNKER_EDGE = 0xFFC9A86A;
    private static final int WATER = 0xFF7FB2D9;
    private static final int WATER_EDGE = 0xFF5B8DB8;
    private static final int PATH_CASING = 0xFFBDBDBD;
    private static final int PATH_SURFACE = 0xFFFFFFFF;
    private static final int FLAG_RED = 0xFFD32F2F;
    private static final int POLE = 0xFF37474F;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private HoleGeom.Shape shape;

    // Projection of the latest draw, kept so taps can be mapped
    // back to lat/lon (tap-to-measure) and the measure marker can
    // be drawn at its true spot.
    private boolean projOk;
    private double prLat0, prLon0, prCos, prSin, prScale, prCx, prCy, prMidX, prMidY;

    private boolean measureOn;
    private double measureLat, measureLon, playerLat, playerLon;
    private boolean playerOn;
    private String measureText = "";

    public HoleView(Context context) {
        super(context);
    }

    public void setShape(HoleGeom.Shape s) {
        shape = s;
        invalidate();
    }

    public void setMeasure(double lat, double lon, String text,
                           double pLat, double pLon, boolean hasPlayer) {
        measureOn = true;
        measureLat = lat;
        measureLon = lon;
        measureText = text;
        playerLat = pLat;
        playerLon = pLon;
        playerOn = hasPlayer;
        invalidate();
    }

    public void clearMeasure() {
        if (measureOn) {
            measureOn = false;
            invalidate();
        }
    }

    /** Screen point -> lat/lon through the latest draw's projection. */
    public double[] unproject(float x, float y) {
        if (!projOk) return null;
        double rx = (x - prCx) / prScale + prMidX;
        double ry = (prCy - y) / prScale + prMidY;
        double mx = rx * prCos + ry * prSin;
        double my = -rx * prSin + ry * prCos;
        double lat = prLat0 + my / 110540.0;
        double lon = prLon0 + mx / (111320.0 * Math.cos(Math.toRadians(prLat0)));
        return new double[]{lat, lon};
    }

    /** lat/lon -> screen point through the latest draw's projection. */
    public float[] projectOf(double lat, double lon) {
        if (!projOk) return null;
        double[] m = toMeters(new double[]{lat, lon}, prLat0, prLon0);
        double rx = m[0] * prCos - m[1] * prSin;
        double ry = m[0] * prSin + m[1] * prCos;
        return new float[]{(float) (prCx + (rx - prMidX) * prScale),
                (float) (prCy - (ry - prMidY) * prScale)};
    }

    /** True when a tap lands on the measure marker (tapping it clears). */
    public boolean hitMeasure(float x, float y) {
        if (!measureOn) return false;
        float[] p = projectOf(measureLat, measureLon);
        return p != null && Math.hypot(x - p[0], y - p[1]) < 56;
    }

    // ------------------------------------------------------------ geometry

    private List<double[]> allPoints(HoleGeom.Shape s) {
        List<double[]> pts = new ArrayList<>(s.line);
        for (List<double[]> p : s.fairways) pts.addAll(p);
        for (List<double[]> p : s.greens) pts.addAll(p);
        for (List<double[]> p : s.bunkers) pts.addAll(p);
        for (List<double[]> p : s.tees) pts.addAll(p);
        for (List<double[]> p : s.waters) pts.addAll(p);
        for (List<double[]> p : s.paths) pts.addAll(p);
        return pts;
    }

    private static double[] toMeters(double[] latLon, double lat0, double lon0) {
        double x = (latLon[1] - lon0) * 111320.0 * Math.cos(Math.toRadians(lat0));
        double y = (latLon[0] - lat0) * 110540.0;
        return new double[]{x, y};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BG);
        canvas.drawRect(0, 0, w, h, paint);

        HoleGeom.Shape s = shape;
        if (s == null || s.line.size() < 2) {
            projOk = false;
            drawCredit(canvas, w, h);
            return;
        }

        // Project to meters around the tee, rotate tee -> green to "up".
        double lat0 = s.line.get(0)[0];
        double lon0 = s.line.get(0)[1];
        double[] startM = toMeters(s.line.get(0), lat0, lon0);
        double[] endM = toMeters(s.line.get(s.line.size() - 1), lat0, lon0);
        double dx = endM[0] - startM[0];
        double dy = endM[1] - startM[1];
        final double bearing = Math.atan2(dx, dy);
        final double cosB = Math.cos(bearing);
        final double sinB = Math.sin(bearing);

        List<double[]> frame = allPoints(s);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] ll : frame) {
            double[] m = toMeters(ll, lat0, lon0);
            double rx = m[0] * cosB - m[1] * sinB;
            double ry = m[0] * sinB + m[1] * cosB;
            if (rx < minX) minX = rx;
            if (rx > maxX) maxX = rx;
            if (ry < minY) minY = ry;
            if (ry > maxY) maxY = ry;
        }
        double bw = Math.max(10, maxX - minX);
        double bh = Math.max(10, maxY - minY);
        float pad = 44f;
        final double scale = Math.min((w - 2 * pad) / bw, (h - 2 * pad) / bh);
        final double cx = w / 2.0;
        final double cy = h / 2.0;
        final double midX = (minX + maxX) / 2.0;
        final double midY = (minY + maxY) / 2.0;

        projOk = true;
        prLat0 = lat0;
        prLon0 = lon0;
        prCos = cosB;
        prSin = sinB;
        prScale = scale;
        prCx = cx;
        prCy = cy;
        prMidX = midX;
        prMidY = midY;

        java.util.function.Function<double[], float[]> proj = ll -> {
            double[] m = toMeters(ll, lat0, lon0);
            double rx = m[0] * cosB - m[1] * sinB;
            double ry = m[0] * sinB + m[1] * cosB;
            return new float[]{(float) (cx + (rx - midX) * scale), (float) (cy - (ry - midY) * scale)};
        };
        // ---- real fairway shapes, then mowing stripes ----
        // The fairway is the actual mapped fairway polygon — exact
        // shapes, not a straight center strip (user, 2026-10-23).
        // The center strip survives only as the fallback for holes
        // where OSM has no fairway mapped (the free-data limit the
        // user accepts). No rough layer at all — also the user's call.
        List<Path> fairwayShapes = new ArrayList<>();
        if (!s.fairways.isEmpty()) {
            paint.setColor(FAIRWAY);
            for (List<double[]> poly : s.fairways) {
                Path p = pathOf(poly, proj);
                canvas.drawPath(p, paint);
                fairwayShapes.add(p);
            }
        } else {
            Path ribbon = new Path();
            for (int i = 0; i < s.line.size(); i++) {
                float[] p = proj.apply(s.line.get(i));
                if (i == 0) ribbon.moveTo(p[0], p[1]);
                else ribbon.lineTo(p[0], p[1]);
            }
            Paint ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ribbonPaint.setStyle(Paint.Style.STROKE);
            ribbonPaint.setColor(FAIRWAY);
            ribbonPaint.setStrokeWidth((float) Math.max(8, 24 * scale));
            ribbonPaint.setStrokeCap(Paint.Cap.ROUND);
            ribbonPaint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(ribbon, ribbonPaint);
            Path ribbonFill = new Path();
            ribbonPaint.getFillPath(ribbon, ribbonFill);
            fairwayShapes.add(ribbonFill);
        }
        drawStripes(canvas, fairwayShapes, w, h, scale);

        // ---- cartpath: gray casing, white surface ----
        for (List<double[]> pathPts : s.paths) {
            Path pth = new Path();
            for (int i = 0; i < pathPts.size(); i++) {
                float[] p = proj.apply(pathPts.get(i));
                if (i == 0) pth.moveTo(p[0], p[1]);
                else pth.lineTo(p[0], p[1]);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(PATH_CASING);
            paint.setStrokeWidth((float) Math.max(3, 4.5 * scale));
            canvas.drawPath(pth, paint);
            paint.setColor(PATH_SURFACE);
            paint.setStrokeWidth((float) Math.max(2, 3 * scale));
            canvas.drawPath(pth, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        // ---- water ----
        for (List<double[]> poly : s.waters) {
            paint.setColor(WATER);
            canvas.drawPath(pathOf(poly, proj), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(WATER_EDGE);
            paint.setStrokeWidth(2f);
            canvas.drawPath(pathOf(poly, proj), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // ---- bunkers: pale sand with a rim ----
        for (List<double[]> poly : s.bunkers) {
            paint.setColor(BUNKER);
            canvas.drawPath(pathOf(poly, proj), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(BUNKER_EDGE);
            paint.setStrokeWidth((float) Math.max(2, 1.4 * scale));
            canvas.drawPath(pathOf(poly, proj), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        // ---- green fringe rings, then greens and tees, then stripes ----
        for (List<double[]> poly : s.greens) {
            paint.setColor(FRINGE);
            canvas.drawPath(pathOf(scaleAboutCentroid(poly, 1.4), proj), paint);
        }
        List<Path> shortShapes = new ArrayList<>();
        paint.setColor(GREEN);
        for (List<double[]> poly : s.greens) {
            Path p = pathOf(poly, proj);
            canvas.drawPath(p, paint);
            shortShapes.add(p);
        }
        paint.setColor(TEE);
        for (List<double[]> poly : s.tees) {
            Path p = pathOf(poly, proj);
            canvas.drawPath(p, paint);
            shortShapes.add(p);
        }
        if (!shortShapes.isEmpty()) drawStripes(canvas, shortShapes, w, h, scale);

        // ---- tee dot + flag ----
        float[] teePt = proj.apply(s.line.get(0));
        paint.setColor(0xFFFFFFFF);
        canvas.drawCircle(teePt[0], teePt[1], 7f, paint);

        double[] flagLl;
        if (!s.greens.isEmpty()) {
            double la = 0, lo = 0;
            List<double[]> g = s.greens.get(0);
            for (double[] p : g) {
                la += p[0];
                lo += p[1];
            }
            flagLl = new double[]{la / g.size(), lo / g.size()};
        } else {
            flagLl = s.line.get(s.line.size() - 1);
        }
        float[] fp = proj.apply(flagLl);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(POLE);
        paint.setStrokeWidth(3f);
        canvas.drawLine(fp[0], fp[1], fp[0], fp[1] - 30f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(FLAG_RED);
        Path flag = new Path();
        flag.moveTo(fp[0], fp[1] - 30f);
        flag.lineTo(fp[0] + 18f, fp[1] - 23f);
        flag.lineTo(fp[0], fp[1] - 16f);
        flag.close();
        canvas.drawPath(flag, paint);

        // ---- tap-to-measure marker, with the player line ----
        if (measureOn) {
            float[] mp = proj.apply(new double[]{measureLat, measureLon});
            if (playerOn) {
                float[] pp = proj.apply(new double[]{playerLat, playerLon});
                if (pp[0] >= -20 && pp[0] <= w + 20 && pp[1] >= -20 && pp[1] <= h + 20) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(0xFF37474F);
                    paint.setStrokeWidth(2.5f);
                    canvas.drawLine(pp[0], pp[1], mp[0], mp[1], paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0xFF1565C0);
                    canvas.drawCircle(pp[0], pp[1], 9f, paint);
                    paint.setColor(0xFFFFFFFF);
                    canvas.drawCircle(pp[0], pp[1], 4f, paint);
                }
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFD32F2F);
            canvas.drawCircle(mp[0], mp[1], 12f, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(mp[0], mp[1], 5.5f, paint);
            paint.setTextSize(31f);
            paint.setTextAlign(Paint.Align.CENTER);
            float tw = paint.measureText(measureText);
            float lx = Math.max(tw / 2 + 14, Math.min(w - tw / 2 - 14, mp[0]));
            float ly = Math.max(44f, mp[1] - 32f);
            paint.setColor(0xE037474F);
            canvas.drawRoundRect(lx - tw / 2 - 13, ly - 36, lx + tw / 2 + 13, ly + 11, 13f, 13f, paint);
            paint.setColor(0xFFFFFFFF);
            canvas.drawText(measureText, lx, ly, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        drawCredit(canvas, w, h);
    }


    /**
     * Alternating translucent diagonal bands, clipped to each shape
     * in turn with the same rotation and phase, so the striping runs
     * continuously across every shape as one surface.
     */
    private void drawStripes(Canvas canvas, List<Path> shapes, int w, int h, double scale) {
        float band = (float) Math.max(6, 13 * scale);
        paint.setStyle(Paint.Style.FILL);
        for (Path p : shapes) {
            if (p.isEmpty()) continue;
            canvas.save();
            canvas.clipPath(p);
            canvas.rotate(-20, w / 2f, h / 2f);
            for (float x = -h; x < w + h; x += 2 * band) {
                paint.setColor(0x14FFFFFF);
                canvas.drawRect(x, -h, x + band, 2f * h, paint);
                paint.setColor(0x0D000000);
                canvas.drawRect(x + band, -h, x + 2 * band, 2f * h, paint);
            }
            canvas.restore();
        }
    }

    private Path pathOf(List<double[]> poly, java.util.function.Function<double[], float[]> proj) {
        Path path = new Path();
        for (int i = 0; i < poly.size(); i++) {
            float[] p = proj.apply(poly.get(i));
            if (i == 0) path.moveTo(p[0], p[1]);
            else path.lineTo(p[0], p[1]);
        }
        path.close();
        return path;
    }

    private List<double[]> scaleAboutCentroid(List<double[]> poly, double k) {
        double la = 0, lo = 0;
        for (double[] p : poly) {
            la += p[0];
            lo += p[1];
        }
        la /= poly.size();
        lo /= poly.size();
        List<double[]> out = new ArrayList<>();
        for (double[] p : poly) out.add(new double[]{la + (p[0] - la) * k, lo + (p[1] - lo) * k});
        return out;
    }

    private void drawCredit(Canvas canvas, int w, int h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xA0595959);
        paint.setTextSize(26f);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("© OpenStreetMap contributors", w - 16f, h - 14f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }
}
