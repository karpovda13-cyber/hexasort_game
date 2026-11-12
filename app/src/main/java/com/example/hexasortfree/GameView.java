package com.example.hexasortfree;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.util.*;

public class GameView extends View {
    static final int CAP = 24, CLEAR = 10, WHITE = 0xFFFFFFFF;
    static final int[] PAL = {0xFFE74C3C, 0xFF3498DB, 0xFF2ECC71, 0xFFF1C40F, 0xFF9B59B6, 0xFFE67E22, 0xFF1ABC9C};
    static final float SQ3 = 1.7320508f;
    static final float[] HC = new float[6], HS = new float[6];
    static {
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(-90 + 60 * k);
            HC[k] = (float) Math.cos(a);
            HS[k] = (float) Math.sin(a);
        }
    }
    static class Snap { ArrayList<ArrayList<Integer>> b, t; int score, cleared; }

    final Random rnd = new Random();
    final Handler h = new Handler(Looper.getMainLooper());
    final SharedPreferences prefs;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final ArrayDeque<Snap> snaps = new ArrayDeque<>();
    Shader bg;
    float W, H, s, cx, cy, dx, dy, downX, downY;
    boolean menu = true, endless, busy, hammer, moved;
    int level = 1, savedLevel, best, rad, nCells, goal, lvColors = 3;
    int score, cleared, overlay, selected = -1, drag = -1, guard;
    int clearCell = -1, clearLeft, movSrc, movDst, movLeft;
    int[] cq, cr;
    int[][] nb;
    ArrayList<ArrayList<Integer>> board = new ArrayList<>(), tray = new ArrayList<>();
    final Runnable stepR = new Runnable() { public void run() { step(); } };

    public GameView(Context c) {
        super(c);
        prefs = c.getSharedPreferences("hexa", Context.MODE_PRIVATE);
        savedLevel = prefs.getInt("level", 1);
        best = prefs.getInt("best", 0);
        buildGrid(2);
    }

    // ---------- game logic ----------
    void buildGrid(int r0) {
        rad = r0;
        ArrayList<int[]> cs = new ArrayList<>();
        for (int r = -rad; r <= rad; r++)
            for (int q = -rad; q <= rad; q++)
                if (Math.abs(q + r) <= rad) cs.add(new int[]{q, r});
        nCells = cs.size();
        cq = new int[nCells]; cr = new int[nCells]; nb = new int[nCells][];
        for (int i = 0; i < nCells; i++) { cq[i] = cs.get(i)[0]; cr[i] = cs.get(i)[1]; }
        int[][] D = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, -1}, {-1, 1}};
        for (int i = 0; i < nCells; i++) {
            ArrayList<Integer> l = new ArrayList<>();
            for (int[] d : D)
                for (int j = 0; j < nCells; j++)
                    if (cq[j] == cq[i] + d[0] && cr[j] == cr[i] + d[1]) l.add(j);
            nb[i] = new int[l.size()];
            for (int k = 0; k < l.size(); k++) nb[i][k] = l.get(k);
        }
    }

    void start(boolean endl, int lv) {
        h.removeCallbacksAndMessages(null);
        endless = endl; level = lv; menu = false; score = 0; cleared = 0; overlay = 0;
        selected = -1; drag = -1; hammer = false; busy = false; clearCell = -1; movLeft = 0;
        snaps.clear();
        buildGrid(endl || lv > 3 ? 3 : 2);
        board = new ArrayList<>();
        for (int i = 0; i < nCells; i++) board.add(new ArrayList<Integer>());
        goal = Math.min(15, 3 + lv);
        lvColors = Math.min(3 + (lv - 1) / 3, 6);
        int pre = rad == 2 ? 5 : 9;
        for (int k = 0; k < pre; k++) {
            int c;
            do c = rnd.nextInt(nCells); while (!board.get(c).isEmpty());
            board.set(c, gen(2));
        }
        fillTray();
        invalidate();
    }

    int colors() { return endless ? Math.min(3 + cleared / 4, 7) : lvColors; }

    ArrayList<Integer> gen(int maxSeg) {
        ArrayList<Integer> l = new ArrayList<>();
        int segs = 1 + rnd.nextInt(maxSeg), nc = colors(), last = -1;
        for (int i = 0; i < segs; i++) {
            int c;
            do c = rnd.nextInt(nc); while (c == last);
            last = c;
            int len = 1 + rnd.nextInt(3);
            for (int j = 0; j < len; j++) l.add(c);
        }
        return l;
    }

    void fillTray() {
        tray = new ArrayList<>();
        for (int i = 0; i < 3; i++) tray.add(gen(3));
    }

    static ArrayList<ArrayList<Integer>> deep(ArrayList<ArrayList<Integer>> a) {
        ArrayList<ArrayList<Integer>> r = new ArrayList<>();
        for (ArrayList<Integer> l : a) r.add(new ArrayList<>(l));
        return r;
    }

    void save() {
        Snap sn = new Snap();
        sn.b = deep(board); sn.t = deep(tray); sn.score = score; sn.cleared = cleared;
        snaps.addLast(sn);
        if (snaps.size() > 80) snaps.removeFirst();
    }

    void undo() {
        Snap sn = snaps.pollLast();
        if (sn == null) return;
        board = sn.b; tray = sn.t; score = sn.score; cleared = sn.cleared;
        overlay = 0; selected = -1;
    }

    void place(int cell, int t) {
        save();
        ArrayList<Integer> st = tray.get(t);
        board.get(cell).addAll(st);
        score += st.size() * 10;
        tray.set(t, new ArrayList<Integer>());
        boolean all = true;
        for (ArrayList<Integer> l : tray) if (!l.isEmpty()) all = false;
        if (all) fillTray();
        selected = -1; busy = true; guard = 0;
        h.postDelayed(stepR, 150);
        invalidate();
    }

    static int run(ArrayList<Integer> l) {
        if (l.isEmpty()) return 0;
        int c = l.get(l.size() - 1), k = 0;
        for (int i = l.size() - 1; i >= 0 && l.get(i) == c; i--) k++;
        return k;
    }

    void step() {
        if (clearCell >= 0) {
            ArrayList<Integer> l = board.get(clearCell);
            if (!l.isEmpty()) l.remove(l.size() - 1);
            if (--clearLeft <= 0) { clearCell = -1; cleared++; score += 100; }
            invalidate();
            h.postDelayed(stepR, clearCell >= 0 ? 35 : 120);
            return;
        }
        if (movLeft > 0) {
            ArrayList<Integer> a = board.get(movSrc), b = board.get(movDst);
            b.add(a.remove(a.size() - 1));
            movLeft--;
            invalidate();
            h.postDelayed(stepR, movLeft > 0 ? 45 : 130);
            return;
        }
        if (guard++ > 3000) { finish(); return; }
        for (int a = 0; a < nCells; a++) {
            ArrayList<Integer> A = board.get(a);
            if (A.isEmpty()) continue;
            for (int b : nb[a]) {
                if (b < a) continue;
                ArrayList<Integer> B = board.get(b);
                if (B.isEmpty() || !A.get(A.size() - 1).equals(B.get(B.size() - 1))) continue;
                int ra = run(A), rb = run(B), dst;
                if (ra != rb) dst = ra > rb ? a : b; else dst = A.size() >= B.size() ? a : b;
                int src = dst == a ? b : a;
                int room = CAP - board.get(dst).size();
                if (room <= 0) continue;
                movSrc = src; movDst = dst; movLeft = Math.min(run(board.get(src)), room);
                h.postDelayed(stepR, 60);
                return;
            }
        }
        for (int i = 0; i < nCells; i++)
            if (run(board.get(i)) >= CLEAR) { clearCell = i; clearLeft = run(board.get(i)); h.postDelayed(stepR, 60); return; }
        finish();
    }

    void finish() {
        busy = false;
        if (endless && score > best) { best = score; prefs.edit().putInt("best", best).apply(); }
        if (!endless && cleared >= goal) {
            overlay = 1;
            if (level + 1 > savedLevel) { savedLevel = level + 1; prefs.edit().putInt("level", savedLevel).apply(); }
        } else {
            boolean full = true;
            for (ArrayList<Integer> l : board) if (l.isEmpty()) full = false;
            if (full) overlay = 2;
        }
        invalidate();
    }

    void goMenu() {
        h.removeCallbacksAndMessages(null);
        busy = false; menu = true; invalidate();
    }

    boolean handleBack() {
        if (!menu) { goMenu(); return true; }
        return false;
    }

    // ---------- layout ----------
    @Override protected void onSizeChanged(int w, int hh, int ow, int oh) {
        bg = new LinearGradient(0, 0, 0, hh, 0xFF1B2540, 0xFF0E1424, Shader.TileMode.CLAMP);
    }

    void layout() {
        W = getWidth(); H = getHeight();
        float top = H * 0.15f, bottom = H * 0.70f;
        s = Math.min(W * 0.96f / (SQ3 * (2 * rad + 1)), (bottom - top) / (3f * rad + 3.5f));
        cx = W / 2f;
        cy = top + s * (1.5f * rad + 2.3f);
    }

    float cellX(int i) { return cx + s * SQ3 * (cq[i] + cr[i] / 2f); }
    float cellY(int i) { return cy + s * 1.5f * cr[i]; }
    float trayX(int i) { return W * (1f / 6f + i / 3f); }
    float trayY() { return H * 0.82f; }
    RectF rc(float l, float t, float r, float b) { return new RectF(l * W, t * H, r * W, b * H); }
    RectF backR() { return rc(.03f, .03f, .24f, .075f); }
    RectF[] bar() {
        RectF[] r = new RectF[3];
        for (int i = 0; i < 3; i++) r[i] = rc(.04f + i * .30f, .90f, .32f + i * .30f, .96f);
        return r;
    }
    RectF[] ov() {
        int n = overlay == 1 ? 2 : 3;
        RectF[] r = new RectF[n];
        for (int i = 0; i < n; i++) r[i] = rc(.15f, .42f + i * .085f, .85f, .49f + i * .085f);
        return r;
    }
    RectF[] menuR() { return new RectF[]{rc(.15f, .55f, .85f, .63f), rc(.15f, .66f, .85f, .74f), rc(.25f, .77f, .75f, .83f)}; }

    int target(float px, float py) {
        int b = -1; float bd = s * s;
        for (int i = 0; i < nCells; i++) {
            if (!board.get(i).isEmpty()) continue;
            float d = (cellX(i) - px) * (cellX(i) - px) + (cellY(i) - py) * (cellY(i) - py);
            if (d < bd) { bd = d; b = i; }
        }
        return b;
    }

    int nearest(float px, float py) {
        int b = -1; float bd = 1.44f * s * s;
        for (int i = 0; i < nCells; i++) {
            int m = board.get(i).size();
            float yy = cellY(i) - (m == 0 ? 0 : Math.min(m * 0.2f * s, 2.8f * s) / 2f);
            float d = (cellX(i) - px) * (cellX(i) - px) + (yy - py) * (yy - py);
            if (d < bd) { bd = d; b = i; }
        }
        return b;
    }

    // ---------- input ----------
    @Override public boolean onTouchEvent(MotionEvent e) {
        layout();
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; moved = false;
                if (!menu && overlay == 0 && !busy && !hammer)
                    for (int i = 0; i < 3; i++)
                        if (!tray.get(i).isEmpty() && Math.hypot(x - trayX(i), y - (trayY() - s * 0.8f)) < s * 1.7f) {
                            drag = i; dx = x; dy = y;
                        }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) > s * 0.3f) moved = true;
                if (drag >= 0) { dx = x; dy = y; invalidate(); }
                return true;
            case MotionEvent.ACTION_UP:
                up(x, y); drag = -1; invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                drag = -1; invalidate();
                return true;
        }
        return true;
    }

    void up(float x, float y) {
        if (menu) {
            RectF[] m = menuR();
            if (m[0].contains(x, y)) start(false, savedLevel);
            else if (m[1].contains(x, y)) start(true, 1);
            else if (m[2].contains(x, y)) start(false, 1);
            return;
        }
        if (backR().contains(x, y)) { goMenu(); return; }
        if (busy) return;
        if (overlay != 0) {
            RectF[] o = ov();
            for (int i = 0; i < o.length; i++) {
                if (!o[i].contains(x, y)) continue;
                if (overlay == 1) { if (i == 0) start(false, level + 1); else goMenu(); }
                else if (i == 0) undo();
                else if (i == 1) { hammer = true; overlay = 0; }
                else start(endless, level);
                return;
            }
            return;
        }
        if (drag >= 0) {
            if (moved) { int c = target(x, y - s * 2.2f); if (c >= 0) place(c, drag); }
            else selected = selected == drag ? -1 : drag;
            return;
        }
        RectF[] b = bar();
        if (b[0].contains(x, y)) undo();
        else if (b[1].contains(x, y)) { hammer = !hammer; selected = -1; }
        else if (b[2].contains(x, y)) { save(); for (int i = 0; i < 3; i++) tray.set(i, gen(3)); selected = -1; }
        else {
            int c = nearest(x, y);
            if (c < 0) return;
            if (hammer && !board.get(c).isEmpty()) { save(); board.get(c).clear(); hammer = false; }
            else if (!hammer && selected >= 0 && board.get(c).isEmpty()) place(c, selected);
        }
    }

    // ---------- drawing ----------
    void hex(Canvas c, float x, float y, float r) {
        path.reset();
        for (int k = 0; k < 6; k++) {
            float px = x + r * HC[k], py = y + r * HS[k];
            if (k == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        c.drawPath(path, p);
    }

    static int shade(int col, float f) {
        return Color.rgb((int) (Color.red(col) * f), (int) (Color.green(col) * f), (int) (Color.blue(col) * f));
    }

    void stack(Canvas c, float x, float y, ArrayList<Integer> l) {
        int m = l.size();
        float th = Math.min(0.2f * s, 2.8f * s / Math.max(m, 1)), r = s * 0.92f;
        for (int i = 0; i < m; i++) {
            int col = PAL[l.get(i)];
            float yy = y - i * th;
            p.setStyle(Paint.Style.FILL);
            p.setColor(shade(col, 0.6f)); hex(c, x, yy + th, r);
            p.setColor(col); hex(c, x, yy, r);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.03f);
            p.setColor(shade(col, 0.8f)); hex(c, x, yy, r);
        }
        p.setStyle(Paint.Style.FILL);
    }

    void text(Canvas c, String t, float x, float y, float size, int col) {
        p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true); p.setTextSize(size); p.setColor(col);
        c.drawText(t, x, y, p);
    }

    void btn(Canvas c, RectF r, String t, int col) {
        p.setStyle(Paint.Style.FILL); p.setColor(col);
        c.drawRoundRect(r, r.height() / 3, r.height() / 3, p);
        text(c, t, r.centerX(), r.centerY() + r.height() * 0.15f, r.height() * 0.4f, WHITE);
    }

    @Override protected void onDraw(Canvas c) {
        layout();
        p.setShader(bg); p.setStyle(Paint.Style.FILL);
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        if (menu) drawMenu(c); else drawGame(c);
    }

    void drawMenu(Canvas c) {
        text(c, "HEXA SORT", W / 2, H * 0.2f, W * 0.13f, WHITE);
        text(c, "Сортировка шестиугольников", W / 2, H * 0.25f, W * 0.04f, 0xFF9FB0D0);
        s = W * 0.085f;
        int[][] demo = {{0, 0, 0, 1, 1}, {2, 2, 2, 0, 0, 0}, {1, 1, 3, 3, 3}};
        for (int i = 0; i < 3; i++) {
            ArrayList<Integer> l = new ArrayList<>();
            for (int v : demo[i]) l.add(v);
            stack(c, W * (0.22f + i * 0.28f), H * 0.42f, l);
        }
        RectF[] m = menuR();
        btn(c, m[0], "Уровни (ур. " + savedLevel + ")", 0xFF2E86DE);
        btn(c, m[1], "Бесконечный (рекорд " + best + ")", 0xFF10AC84);
        btn(c, m[2], "С 1-го уровня", 0xFF576574);
    }

    void drawGame(Canvas c) {
        btn(c, backR(), "Меню", 0xFF2C3A5C);
        if (!endless) {
            text(c, "Уровень " + level, W * 0.62f, H * 0.062f, W * 0.05f, WHITE);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF26324F);
            c.drawRoundRect(rc(.28f, .085f, .96f, .10f), 12, 12, p);
            p.setColor(0xFF2ECC71);
            c.drawRoundRect(rc(.28f, .085f, .28f + .68f * Math.min(1f, (float) cleared / goal), .10f), 12, 12, p);
            text(c, "Наборов: " + cleared + "/" + goal, W * 0.62f, H * 0.125f, W * 0.038f, 0xFF9FB0D0);
        } else {
            text(c, "Очки: " + score, W * 0.62f, H * 0.062f, W * 0.05f, WHITE);
            text(c, "Рекорд: " + best, W * 0.62f, H * 0.10f, W * 0.038f, 0xFF9FB0D0);
        }
        int hover = drag >= 0 ? target(dx, dy - s * 2.2f) : -1;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < nCells; i++)
            if (board.get(i).isEmpty()) { p.setColor(0xFF26324F); hex(c, cellX(i), cellY(i), s * 0.92f); }
        if (hover >= 0) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.08f); p.setColor(WHITE);
            hex(c, cellX(hover), cellY(hover), s * 0.92f);
        }
        for (int i = 0; i < nCells; i++)
            if (!board.get(i).isEmpty()) stack(c, cellX(i), cellY(i), board.get(i));
        if (hammer) text(c, "Выберите стопку для удаления", W / 2, H * 0.735f, W * 0.04f, 0xFFF1C40F);
        for (int i = 0; i < 3; i++) {
            if (tray.get(i).isEmpty() || i == drag) continue;
            if (i == selected) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.1f); p.setColor(WHITE);
                c.drawCircle(trayX(i), trayY() - s * 0.6f, s * 1.5f, p);
            }
            stack(c, trayX(i), trayY(), tray.get(i));
        }
        if (drag >= 0) stack(c, dx, dy - s * 2.2f, tray.get(drag));
        RectF[] b = bar();
        btn(c, b[0], "Отмена", 0xFF2E86DE);
        btn(c, b[1], hammer ? "Отмена" : "Молоток", hammer ? 0xFFC0392B : 0xFFE67E22);
        btn(c, b[2], "Новые", 0xFF10AC84);
        if (overlay != 0) {
            p.setStyle(Paint.Style.FILL); p.setColor(0xCC000000);
            c.drawRect(0, 0, W, H, p);
            p.setColor(0xFF1F2A44);
            c.drawRoundRect(rc(.08f, .28f, .92f, .74f), 40, 40, p);
            text(c, overlay == 1 ? "Уровень пройден!" : "Нет места на поле", W / 2, H * 0.36f, W * 0.065f, WHITE);
            String[] nm = overlay == 1 ? new String[]{"Дальше", "Меню"} : new String[]{"Отменить ход", "Молоток", "Заново"};
            RectF[] o = ov();
            for (int i = 0; i < o.length; i++) btn(c, o[i], nm[i], 0xFF2E86DE);
        }
    }
}
