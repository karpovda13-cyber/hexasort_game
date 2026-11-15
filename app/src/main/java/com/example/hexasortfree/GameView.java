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
    static final int[] PAL = {0xFF56F0F8, 0xFF808BA7, 0xFF199CEC, 0xFFFE4682, 0xFF76E348, 0xFFFFE262, 0xFFFFBEE6, 0xFFD075FF};
    static final float SQ3 = 1.7320508f;
    static final float[] HC = new float[6], HS = new float[6];
    static {
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(-90 + 60 * k);
            HC[k] = (float) Math.cos(a);
            HS[k] = (float) Math.sin(a);
        }
    }
    static class Snap { ArrayList<ArrayList<Integer>> b, t; int score, cleared, removed, rounds; int[] lock, kind, hp; boolean[] rocket; int streak; }
    static class Rk { float x0, y0; int tg; long t0; }
    static class Pt { int cell, col; float vx, vy; long t0; }
    static class Fl { float x0, y0; int dst, col; long t0; }
    static final int FD = 260, FDR = 700, FW = 520;

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
    int[] cq, cr, lock, kind, hp;
    float rmax, rotA, cosA = 1, sinA, prevX, prevY;
    float[] ux, uy;
    boolean rotated, rocketsOn = true;
    boolean[] rocket;
    long[] rkT;
    int streak, turnStart;
    final float[] hc = new float[6], hs = new float[6];
    final ArrayList<Rk> rks = new ArrayList<>();
    final ArrayList<Integer> kills = new ArrayList<>();
    final ArrayList<Pt> pts = new ArrayList<>();
    int combo, comboTiles, burnsChain;
    final ArrayList<Fl> fls = new ArrayList<>();
    int rounds, removed;
    int[][] nb;
    ArrayList<ArrayList<Integer>> board = new ArrayList<>(), tray = new ArrayList<>();
    final Runnable stepR = new Runnable() { public void run() { step(); } };

    public GameView(Context c) {
        super(c);
        prefs = c.getSharedPreferences("hexa", Context.MODE_PRIVATE);
        savedLevel = prefs.getInt("level", 1);
        best = prefs.getInt("best", 0);
        buildGrid(new Random(1), 14);
        lock = new int[nCells]; kind = new int[nCells]; hp = new int[nCells];
        rocket = new boolean[nCells]; rkT = new long[nCells];
    }

    // ---------- game logic ----------
    void buildGrid(Random g, int target) {
        boolean[][] in = new boolean[6][6];
        int cnt = 1;
        in[2 + g.nextInt(2)][2 + g.nextInt(2)] = true;
        int[][] D4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int tr = 0; cnt < target && tr < 5000; tr++) {
            int r = g.nextInt(6), c = g.nextInt(6);
            if (!in[r][c]) continue;
            int[] d = D4[g.nextInt(4)];
            int nr = r + d[0], nc = c + d[1];
            if (nr < 0 || nr > 5 || nc < 0 || nc > 5 || in[nr][nc]) continue;
            in[nr][nc] = true; cnt++;
        }
        ArrayList<int[]> cs = new ArrayList<>();
        for (int r = 0; r < 6; r++)
            for (int c = 0; c < 6; c++)
                if (in[r][c]) cs.add(new int[]{c - (r - (r & 1)) / 2, r});
        nCells = cs.size();
        cq = new int[nCells]; cr = new int[nCells]; nb = new int[nCells][];
        ux = new float[nCells]; uy = new float[nCells];
        float x0 = 1e9f, x1 = -1e9f, y0 = 1e9f, y1 = -1e9f;
        for (int i = 0; i < nCells; i++) {
            cq[i] = cs.get(i)[0]; cr[i] = cs.get(i)[1];
            ux[i] = SQ3 * (cq[i] + cr[i] / 2f); uy[i] = 1.5f * cr[i];
            x0 = Math.min(x0, ux[i]); x1 = Math.max(x1, ux[i]); y0 = Math.min(y0, uy[i]); y1 = Math.max(y1, uy[i]);
        }
        rmax = 0;
        for (int i = 0; i < nCells; i++) {
            ux[i] -= (x0 + x1) / 2f; uy[i] -= (y0 + y1) / 2f;
            rmax = Math.max(rmax, (float) Math.hypot(ux[i], uy[i]));
        }
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

    boolean blocked(int i) { return lock[i] > 0 || ((kind[i] == 2 || kind[i] == 4) && hp[i] > 0); }
    boolean frozen(int i) { return kind[i] == 3 && hp[i] > 0; }
    boolean usable(int i) { return board.get(i).isEmpty() && !blocked(i); }

    ArrayList<Integer> genFor(int c, int maxSeg) {
        ArrayList<Integer> l = gen(maxSeg);
        for (int k = 0; k < 40; k++) {
            boolean bad = false;
            for (int x : nb[c]) {
                ArrayList<Integer> X = board.get(x);
                if (!X.isEmpty() && X.get(X.size() - 1).equals(l.get(l.size() - 1))) bad = true;
            }
            if (!bad) break;
            l = gen(maxSeg);
        }
        return l;
    }

    int locksLeft() {
        int k = 0;
        for (int i = 0; i < nCells; i++) if (lock[i] > 0 || (kind[i] >= 2 && hp[i] > 0)) k++;
        return k;
    }

    int pick() {
        for (int k = 0; k < 300; k++) {
            int c = rnd.nextInt(nCells);
            if (board.get(c).isEmpty() && kind[c] == 0 && lock[c] == 0) return c;
        }
        return -1;
    }

    void start(boolean endl, int lv) {
        h.removeCallbacksAndMessages(null);
        endless = endl; level = lv; menu = false; score = 0; cleared = 0; removed = 0; rounds = 0; overlay = 0;
        selected = -1; drag = -1; hammer = false; busy = false; clearCell = -1; movLeft = 0;
        snaps.clear(); fls.clear(); rks.clear(); kills.clear();
        Random g = endl ? rnd : new Random(lv * 7919L + 13);
        buildGrid(g, endl ? 28 : Math.min(32, 14 + lv));
        board = new ArrayList<>();
        for (int i = 0; i < nCells; i++) board.add(new ArrayList<Integer>());
        lock = new int[nCells]; kind = new int[nCells]; hp = new int[nCells];
        rocket = new boolean[nCells]; rkT = new long[nCells]; streak = 0; rks.clear(); kills.clear(); pts.clear(); combo = 0; comboTiles = 0; burnsChain = 0;
        goal = Math.min(400, 30 + 12 * lv);
        lvColors = Math.min(3 + (lv - 1) / 3, 6);
        if (!endl) {
            int[] avail = {3, 2, 4, 6};
            int[] cnt = new int[4];
            int mxc = Math.min(3, 1 + lv / 4);
            if (g.nextInt(5) > 0)
                for (int ty = 0; ty < 4; ty++) cnt[ty] = lv >= avail[ty] ? g.nextInt(mxc + 1) : 0;
            int used = 0;
            for (int ty = 0; ty < 4; ty++)
                for (int k = 0; k < cnt[ty] && used < nCells / 4; k++) {
                    int c = pick();
                    if (c < 0) break;
                    used++;
                    if (ty == 0) { lock[c] = 15 + g.nextInt(20) + 3 * lv; continue; }
                    kind[c] = ty + 1; hp[c] = 1 + g.nextInt(Math.min(3, 1 + lv / 4));
                    if (ty == 2) board.set(c, genFor(c, 1));
                }
        }
        for (int k = 0; k < nCells / 4; k++) {
            int c = pick();
            if (c < 0) break;
            board.set(c, genFor(c, 2));
        }
        int mxl = 0;
        for (int i = 0; i < nCells; i++) mxl = Math.max(mxl, lock[i]);
        goal = Math.max(goal, mxl);
        fillTray();
        rotA = 0;
        layout();
        invalidate();
    }

    int colors() { return Math.min((endless ? 3 : lvColors) + rounds / 15, 8); }

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
        sn.b = deep(board); sn.t = deep(tray); sn.score = score; sn.cleared = cleared; sn.removed = removed; sn.rounds = rounds; sn.lock = lock.clone(); sn.kind = kind.clone(); sn.hp = hp.clone(); sn.rocket = rocket.clone(); sn.streak = streak;
        snaps.addLast(sn);
        if (snaps.size() > 80) snaps.removeFirst();
    }

    void undo() {
        Snap sn = snaps.pollLast();
        if (sn == null) return;
        board = sn.b; tray = sn.t; score = sn.score; cleared = sn.cleared; removed = sn.removed; rounds = sn.rounds; lock = sn.lock; kind = sn.kind; hp = sn.hp; rocket = sn.rocket; streak = sn.streak; combo = 0; comboTiles = 0;
        overlay = 0; selected = -1;
    }

    void place(int cell, int t) {
        if (!busy) save();
        ArrayList<Integer> st = tray.get(t);
        board.get(cell).addAll(st);
        score += st.size() * 10;
        tray.set(t, new ArrayList<Integer>());
        boolean all = true;
        for (ArrayList<Integer> l : tray) if (!l.isEmpty()) all = false;
        if (all) { rounds++; fillTray(); }
        selected = -1; guard = 0;
        if (!busy) { turnStart = removed; burnsChain = 0; busy = true; h.postDelayed(stepR, 150); }
        invalidate();
    }

    static int run(ArrayList<Integer> l) {
        if (l.isEmpty()) return 0;
        int c = l.get(l.size() - 1), k = 0;
        for (int i = l.size() - 1; i >= 0 && l.get(i) == c; i--) k++;
        return k;
    }

    void launch(int cell, int... avoid) {
        rocket[cell] = false;
        ArrayList<Integer> cand = new ArrayList<>();
        for (int i = 0; i < nCells; i++) {
            if (i == cell || board.get(i).isEmpty() || frozen(i) || i == clearCell || kills.contains(i)) continue;
            boolean no = false;
            for (int a : avoid) if (a == i) no = true;
            for (Rk q : rks) if (q.tg == i) no = true;
            if (!no) cand.add(i);
        }
        if (cand.isEmpty()) return;
        Rk r = new Rk();
        int m = Math.max(1, board.get(cell).size());
        r.x0 = cellX(cell); r.y0 = cellY(cell) - (m - 1) * th(m) - s * 0.6f;
        r.tg = cand.get(rnd.nextInt(cand.size())); r.t0 = System.currentTimeMillis();
        rks.add(r);
    }

    void beginClear(int k, int n) {
        if (rocket[k]) launch(k, k);
        clearCell = k; clearLeft = n;
        h.postDelayed(stepR, 60);
    }

    void dropRocket() {
        ArrayList<Integer> cand = new ArrayList<>();
        for (int i = 0; i < nCells; i++) if (!board.get(i).isEmpty() && !frozen(i) && !rocket[i]) cand.add(i);
        if (cand.isEmpty()) return;
        int i = cand.get(rnd.nextInt(cand.size()));
        rocket[i] = true; rkT[i] = System.currentTimeMillis();
    }

    void land() {
        long now0 = System.currentTimeMillis();
        for (Iterator<Rk> it = rks.iterator(); it.hasNext(); ) {
            Rk r = it.next();
            if (now0 - r.t0 >= FDR) { kills.add(r.tg); it.remove(); }
        }
        long now = System.currentTimeMillis();
        for (Iterator<Fl> it = fls.iterator(); it.hasNext(); ) {
            Fl f = it.next();
            if (now - f.t0 >= FD) { board.get(f.dst).add(f.col); it.remove(); }
        }
    }

    float th(int m) { return Math.min(0.2f * s, 2.8f * s / Math.max(m, 1)); }

    boolean hits(int cc, int j) {
        if (hp[j] <= 0 || kind[j] < 2) return false;
        if (kind[j] == 4) {
            int dq = cq[j] - cq[cc], dr = cr[j] - cr[cc];
            return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2 <= 2;
        }
        for (int x : nb[cc]) if (x == j) return true;
        return false;
    }

    int hitValue(int cc) {
        int v = 0;
        for (int j = 0; j < nCells; j++) if (hits(cc, j)) v += hp[j] == 1 ? 3 : 1;
        return v;
    }

    void burnHit(int cc) {
        for (int j = 0; j < nCells; j++)
            if (hits(cc, j) && --hp[j] <= 0) { kind[j] = 0; boom(j); }
    }

    void boom(int cell) {
        long now0 = System.currentTimeMillis();
        for (int k = 0; k < 18; k++) {
            Pt q = new Pt();
            q.cell = cell; q.col = PAL[rnd.nextInt(PAL.length)]; q.t0 = now0;
            double a = rnd.nextDouble() * Math.PI * 2, sp = 1.2 + rnd.nextDouble() * 2.2;
            q.vx = (float) (Math.cos(a) * sp); q.vy = (float) (Math.sin(a) * sp) - 1.2f;
            pts.add(q);
        }
    }

    int top(int i) { ArrayList<Integer> l = board.get(i); return l.get(l.size() - 1); }

    int[] hubMove() {
        boolean[] seen = new boolean[nCells];
        for (int st = 0; st < nCells; st++) {
            if (seen[st] || board.get(st).isEmpty() || frozen(st)) continue;
            int col = top(st);
            ArrayList<Integer> comp = new ArrayList<>();
            comp.add(st); seen[st] = true;
            for (int k = 0; k < comp.size(); k++)
                for (int x : nb[comp.get(k)]) {
                    if (seen[x] || board.get(x).isEmpty() || frozen(x) || top(x) != col) continue;
                    seen[x] = true; comp.add(x);
                }
            if (comp.size() < 2) continue;
            int total = 0;
            for (int x : comp) total += run(board.get(x));
            if (total < CLEAR) continue;
            int hub = -1, hv = -1;
            for (int x : comp) {
                int v = hitValue(x) * 10000 + run(board.get(x)) * 100 + board.get(x).size();
                if (v > hv) { hv = v; hub = x; }
            }
            int[] dist = new int[nCells];
            Arrays.fill(dist, -1);
            dist[hub] = 0;
            ArrayList<Integer> q = new ArrayList<>();
            q.add(hub);
            for (int k = 0; k < q.size(); k++)
                for (int x : nb[q.get(k)])
                    if (dist[x] < 0 && comp.contains(x)) { dist[x] = dist[q.get(k)] + 1; q.add(x); }
            for (int d = q.size() - 1; d > 0; d--) {
                int m = q.get(d);
                for (int x : nb[m])
                    if (dist[x] == dist[m] - 1 && comp.contains(x) && CAP - board.get(x).size() > 0) return new int[]{m, x};
            }
        }
        return null;
    }

    void step() {
        land();
        if (clearCell >= 0) {
            ArrayList<Integer> l = board.get(clearCell);
            if (!l.isEmpty()) { l.remove(l.size() - 1); removed++; comboTiles++; for (int i = 0; i < nCells; i++) if (lock[i] > 0 && --lock[i] == 0) boom(i); }
            if (--clearLeft <= 0) { int cc = clearCell; clearCell = -1; cleared++; score += 100; combo++; burnsChain++; burnHit(cc); }
            invalidate();
            h.postDelayed(stepR, clearCell >= 0 ? 35 : 120);
            return;
        }
        if (movLeft > 0) {
            ArrayList<Integer> a = board.get(movSrc);
            Fl f = new Fl();
            int m = a.size();
            f.x0 = cellX(movSrc); f.y0 = cellY(movSrc) - (m - 1) * th(m);
            f.col = a.remove(m - 1); f.dst = movDst; f.t0 = System.currentTimeMillis();
            fls.add(f); movLeft--;
            invalidate();
            h.postDelayed(stepR, 72);
            return;
        }
        if (!fls.isEmpty() || !rks.isEmpty()) { invalidate(); h.postDelayed(stepR, 25); return; }
        while (!kills.isEmpty()) {
            int k = kills.remove(0);
            if (!board.get(k).isEmpty() && !frozen(k)) { beginClear(k, board.get(k).size()); return; }
        }
        if (guard++ > 3000) { finish(); return; }
        int bs = -1, bd = -1, bsc = Integer.MIN_VALUE;
        for (int a = 0; a < nCells; a++) {
            if (board.get(a).isEmpty() || frozen(a)) continue;
            for (int b : nb[a]) {
                ArrayList<Integer> A = board.get(a), B = board.get(b);
                if (B.isEmpty() || frozen(b) || !A.get(A.size() - 1).equals(B.get(B.size() - 1))) continue;
                int sc = impact(a, b);
                if (sc == Integer.MIN_VALUE) continue;
                if (sc > bsc) { bsc = sc; bs = a; bd = b; }
            }
        }
        int[] hm = hubMove();
        if (hm != null) { bs = hm[0]; bd = hm[1]; }
        if (bs >= 0) {
            if (rocket[bs]) launch(bs, bs, bd);
            if (rocket[bd]) launch(bd, bs, bd);
            movSrc = bs; movDst = bd;
            movLeft = Math.min(run(board.get(bs)), CAP - board.get(bd).size());
            h.postDelayed(stepR, 60);
            return;
        }
        for (int i = 0; i < nCells; i++)
            if (!frozen(i) && run(board.get(i)) >= CLEAR) { beginClear(i, run(board.get(i))); return; }
        finish();
    }

    int impact(int a, int b) {
        ArrayList<Integer> A = board.get(a), B = board.get(b);
        int room = CAP - B.size();
        if (room <= 0) return Integer.MIN_VALUE;
        int ra = run(A), mv = Math.min(ra, room), sc = mv;
        if (run(B) + mv >= CLEAR) sc += 100 + mv;
        if (mv == A.size()) sc += 6;
        else if (mv == ra) {
            int nc = A.get(A.size() - ra - 1);
            for (int x : nb[a]) {
                ArrayList<Integer> X = board.get(x);
                if (x != b && !X.isEmpty() && X.get(X.size() - 1) == nc) { sc += 12; break; }
            }
        }
        if (mv == ra) {
            int col = A.get(A.size() - 1);
            for (int x : nb[a]) {
                if (x == b || frozen(x)) continue;
                ArrayList<Integer> X = board.get(x);
                if (X.isEmpty() || !X.get(X.size() - 1).equals(col)) continue;
                boolean adj = false;
                for (int y : nb[b]) if (y == x) adj = true;
                if (!adj) sc -= 15;
            }
        }
        return sc * 2 + (B.size() >= A.size() ? 1 : 0);
    }

    void finish() {
        busy = false;
        if (burnsChain == 0) { combo = 0; comboTiles = 0; }
        burnsChain = 0;
        for (int i = 0; i < nCells; i++) if (board.get(i).isEmpty()) rocket[i] = false;
        streak = removed - turnStart >= 10 ? streak + 1 : 0;
        if (streak >= 3) { streak = 0; dropRocket(); }
        if (endless && score > best) { best = score; prefs.edit().putInt("best", best).apply(); }
        if (!endless && removed >= goal && locksLeft() == 0) {
            overlay = 1;
            if (level + 1 > savedLevel) { savedLevel = level + 1; prefs.edit().putInt("level", savedLevel).apply(); }
        } else {
            boolean full = true;
            for (int i = 0; i < nCells; i++) if (usable(i)) full = false;
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
        s = Math.min(Math.min(W * 0.96f / (2 * rmax + 1.9f), (bottom - top) / (2 * rmax + 3.5f)), W * 0.11f);
        cx = W / 2f; cy = (top + bottom) / 2f + s * 0.3f;
        float a = menu ? 0 : rotA;
        cosA = (float) Math.cos(a); sinA = (float) Math.sin(a);
        for (int k = 0; k < 6; k++) {
            double v = -Math.PI / 2 + k * Math.PI / 3 + a;
            hc[k] = (float) Math.cos(v); hs[k] = (float) Math.sin(v);
        }
    }

    float cellX(int i) { return cx + s * (ux[i] * cosA - uy[i] * sinA); }
    float cellY(int i) { return cy + s * (ux[i] * sinA + uy[i] * cosA); }
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
            if (!usable(i)) continue;
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
                downX = x; downY = y; moved = false; rotated = false; prevX = x; prevY = y;
                if (!menu && overlay == 0 && !hammer)
                    for (int i = 0; i < 3; i++)
                        if (!tray.get(i).isEmpty() && Math.hypot(x - trayX(i), y - (trayY() - s * 0.8f)) < s * 1.7f) {
                            drag = i; dx = x; dy = y;
                        }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) > s * 0.3f) moved = true;
                if (drag >= 0) { dx = x; dy = y; invalidate(); }
                else if (!menu && overlay == 0 && downY > H * 0.13f && downY < H * 0.76f
                        && (rotated || Math.hypot(x - downX, y - downY) > s * 0.5f)) {
                    float d = (float) (Math.atan2(y - cy, x - cx) - Math.atan2(prevY - cy, prevX - cx));
                    if (d > Math.PI) d -= 2 * Math.PI;
                    if (d < -Math.PI) d += 2 * Math.PI;
                    if (Math.hypot(x - cx, y - cy) < s * 1.2f) d = (x - prevX) * 0.008f;
                    rotA += d; rotated = true; invalidate();
                }
                prevX = x; prevY = y;
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
        if (rotated) { rotated = false; return; }
        if (backR().contains(x, y)) { goMenu(); return; }
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
        if (busy) for (RectF rr : b) if (rr.contains(x, y)) return;
        if (b[0].contains(x, y)) undo();
        else if (b[1].contains(x, y)) { hammer = !hammer; selected = -1; }
        else if (b[2].contains(x, y)) { save(); for (int i = 0; i < 3; i++) tray.set(i, gen(3)); selected = -1; }
        else {
            int c = nearest(x, y);
            if (c < 0) return;
            if (hammer && !busy) {
                if (lock[c] > 0 || blocked(c) || !board.get(c).isEmpty()) {
                    save();
                    if (lock[c] > 0) { lock[c] = 0; boom(c); }
                    else if (kind[c] >= 2 && hp[c] > 0) { hp[c] = 0; kind[c] = 0; boom(c); }
                    else { board.get(c).clear(); rocket[c] = false; }
                    hammer = false;
                }
            }
            else if (!hammer && selected >= 0 && usable(c)) place(c, selected);
        }
    }

    // ---------- drawing ----------
    void hex(Canvas c, float x, float y, float r) {
        path.reset();
        for (int k = 0; k < 6; k++) {
            float px = x + r * hc[k], py = y + r * hs[k];
            if (k == 0) path.moveTo(px, py); else path.lineTo(px, py);
        }
        path.close();
        c.drawPath(path, p);
    }

    static int shade(int col, float f) {
        return Color.rgb((int) (Color.red(col) * f), (int) (Color.green(col) * f), (int) (Color.blue(col) * f));
    }

    void stack(Canvas c, float x, float y, ArrayList<Integer> l) { stack(c, x, y, l, true); }

    void stack(Canvas c, float x, float y, ArrayList<Integer> l, boolean badge) {
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
        if (badge && m > 0) text(c, String.valueOf(run(l)), x, y - (m - 1) * th + s * 0.25f, s * 0.75f, (l.get(m - 1) == 0 || l.get(m - 1) == 5 || l.get(m - 1) == 6) ? 0xFF222222 : WHITE);
    }

    void drawObstacle(Canvas c, int i) {
        float x = cellX(i), y = cellY(i) - s * 0.15f, r = s * 0.92f;
        boolean crate = kind[i] == 2;
        p.setStyle(Paint.Style.FILL);
        p.setColor(crate ? 0xFF6B4423 : 0xFF4B5563); hex(c, x, y + s * 0.3f, r);
        p.setColor(crate ? 0xFFB07A43 : 0xFF8E9BB0); hex(c, x, y, r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.07f);
        p.setColor(crate ? 0xFF6B4423 : 0xFF4B5563);
        if (crate) {
            c.drawLine(x - r * 0.85f, y, x + r * 0.85f, y, p);
            c.drawLine(x, y - r * 0.9f, x, y + r * 0.9f, p);
            text(c, String.valueOf(hp[i]), x, y + s * 0.27f, s * 0.8f, WHITE);
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2D3748); c.drawCircle(x, y, s * 0.2f, p);
            for (int k = 0; k < hp[i]; k++) {
                double a = 2 * Math.PI * k / hp[i] - Math.PI / 2;
                p.setColor(0xFFF1C40F);
                c.drawCircle(x + (float) Math.cos(a) * r * 0.5f, y + (float) Math.sin(a) * r * 0.5f, s * 0.24f, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
    }

    void drawIce(Canvas c, int i) {
        float x = cellX(i), by = cellY(i), r = s * 0.97f;
        int m = board.get(i).size();
        float ty = by - (m - 1) * th(m), st = Math.max(1f, th(m) * 0.5f);
        c.saveLayerAlpha(x - 2 * s, ty - 2 * s, x + 2 * s, by + 2 * s, 120);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFBFF0FF);
        for (float yy = ty; yy <= by + th(m) + 0.5f; yy += st) hex(c, x, yy, r);
        c.restore();
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.09f); p.setColor(0xFFE8FAFF);
        hex(c, x, ty, r); hex(c, x, by + th(m), r);
        p.setStrokeWidth(s * 0.05f); p.setColor(WHITE);
        c.drawLine(x - r * 0.55f, ty - r * 0.1f, x - r * 0.2f, ty - r * 0.45f, p);
        c.drawLine(x - r * 0.35f, ty + r * 0.1f, x - r * 0.15f, ty - r * 0.1f, p);
        float bx = x + r * 0.55f, byy = ty - r * 0.3f;
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF1E88E5); c.drawCircle(bx, byy, s * 0.4f, p);
        p.setStyle(Paint.Style.STROKE); p.setColor(WHITE); c.drawCircle(bx, byy, s * 0.4f, p);
        text(c, String.valueOf(hp[i]), bx, byy + s * 0.22f, s * 0.6f, WHITE);
        p.setStyle(Paint.Style.FILL);
    }

    void drawRocket(Canvas c, float x, float y, float z) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFE74C3C);
        path.reset(); path.moveTo(x - 0.2f * z, y + 0.3f * z); path.lineTo(x - 0.45f * z, y + 0.65f * z); path.lineTo(x - 0.15f * z, y + 0.5f * z); path.close(); c.drawPath(path, p);
        path.reset(); path.moveTo(x + 0.2f * z, y + 0.3f * z); path.lineTo(x + 0.45f * z, y + 0.65f * z); path.lineTo(x + 0.15f * z, y + 0.5f * z); path.close(); c.drawPath(path, p);
        path.reset(); path.moveTo(x - 0.2f * z, y - 0.3f * z); path.lineTo(x, y - 0.8f * z); path.lineTo(x + 0.2f * z, y - 0.3f * z); path.close(); c.drawPath(path, p);
        p.setColor(0xFFF39C12);
        path.reset(); path.moveTo(x - 0.1f * z, y + 0.55f * z); path.lineTo(x, y + 0.95f * z); path.lineTo(x + 0.1f * z, y + 0.55f * z); path.close(); c.drawPath(path, p);
        p.setColor(0xFFECF0F1); c.drawRoundRect(x - 0.2f * z, y - 0.35f * z, x + 0.2f * z, y + 0.55f * z, 0.1f * z, 0.1f * z, p);
        p.setColor(0xFF3498DB); c.drawCircle(x, y - 0.05f * z, 0.11f * z, p);
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
        if (combo > 0) {
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.LEFT); p.setFakeBoldText(true);
            p.setColor(0xFFFFE262); p.setTextSize(W * 0.04f);
            c.drawText("Стопок: " + combo, W * 0.03f, H * 0.108f, p);
            p.setTextSize(W * 0.034f); p.setColor(0xFFB8C7E0);
            c.drawText("+" + comboTiles + " плит.", W * 0.03f, H * 0.133f, p);
        }
        if (!endless) {
            text(c, "Уровень " + level, W * 0.5f, H * 0.062f, W * 0.05f, WHITE);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF26324F);
            c.drawRoundRect(rc(.28f, .085f, .96f, .10f), 12, 12, p);
            p.setColor(0xFF2ECC71);
            c.drawRoundRect(rc(.28f, .085f, .28f + .68f * Math.min(1f, (float) removed / goal), .10f), 12, 12, p);
            text(c, "Плиток: " + Math.max(0, goal - removed) + (locksLeft() > 0 ? "  •  Препятствий: " + locksLeft() : ""), W * 0.62f, H * 0.125f, W * 0.038f, 0xFF9FB0D0);
        } else {
            text(c, "Очки: " + score, W * 0.5f, H * 0.062f, W * 0.05f, WHITE);
            text(c, "Рекорд: " + best, W * 0.5f, H * 0.10f, W * 0.038f, 0xFF9FB0D0);
        }
        Integer[] ordB = new Integer[nCells];
        for (int i = 0; i < nCells; i++) ordB[i] = i;
        Arrays.sort(ordB, (u, v) -> Float.compare(cellY(u), cellY(v)));
        int hover = drag >= 0 ? target(dx, dy - s * 2.2f) : -1;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < nCells; i++)
            if (board.get(i).isEmpty()) {
                p.setColor(lock[i] > 0 ? 0xFF5D6B82 : 0xFFC0DEFF);
                hex(c, cellX(i), cellY(i), s * 0.92f);
                if (lock[i] > 0) text(c, String.valueOf(lock[i]), cellX(i), cellY(i) + s * 0.25f, s * 0.7f, WHITE);
                else if (kind[i] == 2 || kind[i] == 4) drawObstacle(c, i);
            }
        if (hover >= 0) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * 0.08f); p.setColor(0xFF1E88E5);
            hex(c, cellX(hover), cellY(hover), s * 0.92f);
        }
        for (Integer ii : ordB)
            if (!board.get(ii).isEmpty()) {
                int i = ii;
                boolean fz = frozen(i);
                stack(c, cellX(i), cellY(i), board.get(i), !fz);
                if (fz) drawIce(c, i);
                if (rocket[i]) {
                    int mm = board.get(i).size();
                    float yy = cellY(i) - (mm - 1) * th(mm) - s * 0.65f;
                    long dt = System.currentTimeMillis() - rkT[i];
                    if (dt < 450) yy -= (1 - dt / 450f) * s * 5;
                    drawRocket(c, cellX(i), yy, s);
                }
            }
        long now = System.currentTimeMillis();
        for (Fl f : fls) {
            float tt = Math.min(1f, (now - f.t0) / (float) FD);
            int dm = board.get(f.dst).size();
            float x1 = cellX(f.dst), y1 = cellY(f.dst) - dm * th(dm + 1);
            ArrayList<Integer> one = new ArrayList<>();
            one.add(f.col);
            stack(c, f.x0 + (x1 - f.x0) * tt, f.y0 + (y1 - f.y0) * tt - (float) Math.sin(Math.PI * tt) * s * 1.3f, one, false);
        }
        for (Rk r : rks) {
            float tt = Math.min(0.97f, (now - r.t0) / (float) FDR);
            int mm = Math.max(1, board.get(r.tg).size());
            float x1 = cellX(r.tg), y1 = cellY(r.tg) - (mm - 1) * th(mm);
            float ax = r.x0 + (x1 - r.x0) * tt, ay = r.y0 + (y1 - r.y0) * tt - (float) Math.sin(Math.PI * tt) * s * 3f;
            float t2 = tt + 0.03f;
            float bx = r.x0 + (x1 - r.x0) * t2, by = r.y0 + (y1 - r.y0) * t2 - (float) Math.sin(Math.PI * t2) * s * 3f;
            c.save(); c.translate(ax, ay);
            c.rotate((float) Math.toDegrees(Math.atan2(bx - ax, -(by - ay))));
            drawRocket(c, 0, 0, s);
            c.restore();
        }
        for (Iterator<Pt> it = pts.iterator(); it.hasNext(); ) {
            Pt q = it.next();
            float ms = now - q.t0;
            if (ms > FW) { it.remove(); continue; }
            float tt = ms / 1000f, al = 1 - ms / FW;
            p.setStyle(Paint.Style.FILL); p.setColor(q.col); p.setAlpha((int) (255 * al));
            c.drawCircle(cellX(q.cell) + q.vx * s * tt, cellY(q.cell) - s * 0.6f + (q.vy * tt + 2.5f * tt * tt) * s, s * 0.11f * (0.5f + al), p);
            p.setAlpha(255);
        }
        boolean anim = !fls.isEmpty() || !rks.isEmpty() || !pts.isEmpty();
        for (int i = 0; i < nCells; i++) if (rocket[i] && now - rkT[i] < 450) anim = true;
        if (anim) postInvalidateOnAnimation();
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
