package dev.spotifyhud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/** Draws the HUD. Used both in-game and as the live preview inside the settings screen. */
public final class HudRenderer {
    public static int lastX, lastY, lastW, lastH;
    private static final int[][] hit = new int[3][4];
    private static final Runnable[] actions = {Spotify::previous, Spotify::togglePlay, Spotify::next};
    private static boolean hitsValid;

    private HudRenderer() {}

    public static boolean inPanel(double mx, double my) {
        return mx >= lastX && mx < lastX + lastW && my >= lastY && my < lastY + lastH;
    }

    public static Runnable controlAt(double mx, double my) {
        if (!hitsValid) return null;
        for (int i = 0; i < 3; i++)
            if (mx >= hit[i][0] && mx < hit[i][0] + hit[i][2] && my >= hit[i][1] && my < hit[i][1] + hit[i][3]) return actions[i];
        return null;
    }

    public static void render(DrawContext ctx, int gw, int gh, int mx, int my) {
        HudConfig c = HudConfig.get();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        Spotify.Snapshot s = Spotify.snapshot();
        boolean ok = s.status() == Spotify.Status.OK;

        int w = Math.min(c.width, gw), h = Math.min(c.height, gh);
        int x = clamp(c.x, 0, gw - w), y = clamp(c.y, 0, gh - h);
        lastX = x; lastY = y; lastW = w; lastH = h;
        hitsValid = false;

        int acc = 0xFF000000 | c.accentColor;
        if (c.glow) {
            for (int i = 3; i >= 1; i--)
                panel(ctx, x - i, y - i, w + 2 * i, h + 2 * i, c.cornerRadius + i, 0, ((0x16 * (4 - i)) << 24) | c.accentColor, 0);
        }
        int bg = (Math.round(c.bgOpacity * 2.55f) << 24) | c.bgColor;
        panel(ctx, x, y, w, h, c.cornerRadius, c.borderWidth, bg, 0xFF000000 | c.borderColor);

        final int pad = 6;
        int tx = x + pad;
        if (c.showCover) {
            int size = Math.max(8, Math.min(c.coverSize, h - 2 * pad));
            int cx = x + pad, cy = y + (h - size) / 2;
            Identifier id = ok ? AlbumArt.get(s.coverUrl()) : null;
            if (id != null) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, cx, cy, 0f, 0f, size, size,
                    AlbumArt.SIZE, AlbumArt.SIZE, AlbumArt.SIZE, AlbumArt.SIZE);
            else ctx.fill(cx, cy, cx + size, cy + size, (0x55 << 24) | c.accentColor);
            tx = cx + size + pad;
        }
        int tw = x + w - pad - tx;
        if (tw < 24) return;

        float sc = c.textScalePercent / 100f, ts = sc, as = sc * 0.85f, ms = sc * 0.75f;
        boolean compact = c.style == HudConfig.Style.COMPACT;
        boolean showT = c.showTitle || !ok;
        boolean showA = c.showArtist && !compact && ok;
        int hT = showT ? Math.round(9 * ts) + 2 : 0;
        int hA = showA ? Math.round(9 * as) + 2 : 0;
        int hP = c.showProgress && ok ? 4 + (c.showTimes ? Math.round(9 * ms) + 1 : 0) + 3 : 0;
        int hC = c.showControls ? 14 : 0;
        int cy = y + Math.max(4, (h - (hT + hA + hP + hC)) / 2);

        // title (or status message when Spotify isn't ready)
        boolean eq = c.showEqualizer && ok;
        if (showT) {
            String line = !ok ? s.message()
                    : (compact && c.showArtist && !s.artist().isEmpty() ? s.title() + " - " + s.artist() : s.title());
            text(ctx, tr, line, tx, cy, ts, tw - (eq ? 16 : 0), ok ? c.textColor : c.subTextColor, c.textShadow, ok && c.boldTitle);
        }
        if (eq) {
            long t = System.currentTimeMillis();
            for (int i = 0; i < 4; i++) {
                int bh = s.playing() ? 2 + (int) ((Math.sin(t * 0.007 + i * 1.9) * 0.5 + 0.5) * 8) : 2;
                int bx = x + w - pad - 11 + i * 3;
                ctx.fill(bx, cy + 10 - bh, bx + 2, cy + 10, acc);
            }
        }
        if (showA) {
            String st = c.showStatus ? (s.playing() ? "PLAYING" : "PAUSED") : "";
            int stW = st.isEmpty() ? 0 : Math.round(tr.getWidth(st) * ms);
            text(ctx, tr, s.artist(), tx, cy + hT, as, tw - stW - (stW > 0 ? 6 : 0), c.subTextColor, c.textShadow, false);
            if (stW > 0) text(ctx, tr, st, tx + tw - stW, cy + hT + 1, ms, stW + 2, s.playing() ? acc & 0xFFFFFF : c.subTextColor, false, false);
        }
        if (hP > 0) {
            int by = cy + hT + hA + 1;
            long dur = Math.max(1, s.durationMs()), pos = Spotify.progressNow(s);
            int fw = (int) (tw * Math.min(1.0, (double) pos / dur));
            ctx.fill(tx, by, tx + tw, by + 3, 0x40FFFFFF);
            ctx.fill(tx, by, tx + fw, by + 3, acc);
            ctx.fill(tx + Math.max(0, fw - 1), by - 1, tx + fw + 1, by + 4, 0xFFFFFFFF);
            if (c.showTimes) {
                String l = fmt(pos), r = "-" + fmt(dur - pos);
                text(ctx, tr, l, tx, by + 6, ms, tw, c.subTextColor, false, false);
                text(ctx, tr, r, tx + tw - Math.round(tr.getWidth(r) * ms), by + 6, ms, tw, c.subTextColor, false, false);
            }
        }
        if (c.showControls) {
            int iy = cy + hT + hA + hP + 3, ix = tx;
            boolean playing = ok && s.playing();
            for (int i = 0; i < 3; i++) {
                int bx = ix + i * 18 - 2;
                hit[i][0] = bx; hit[i][1] = iy - 3; hit[i][2] = 16; hit[i][3] = 14;
                boolean hov = mx >= bx && mx < bx + 16 && my >= iy - 3 && my < iy + 11;
                int col = hov ? 0xFFFFFFFF : (0xFF000000 | c.textColor);
                int px = ix + i * 18;
                switch (i) {
                    case 0 -> { ctx.fill(px, iy, px + 1, iy + 8, col); tri(ctx, px + 2, iy, false, col); }
                    case 1 -> {
                        if (playing) { ctx.fill(px + 1, iy, px + 3, iy + 8, col); ctx.fill(px + 5, iy, px + 7, iy + 8, col); }
                        else tri(ctx, px + 1, iy, true, col);
                    }
                    default -> { tri(ctx, px, iy, true, col); ctx.fill(px + 6, iy, px + 7, iy + 8, col); }
                }
            }
            hitsValid = true;
        }
    }

    private static void tri(DrawContext ctx, int x, int y, boolean right, int col) {
        for (int i = 0; i < 5; i++) {
            int hh = 8 - (i * 8) / 5, cx = right ? x + i : x + 4 - i, top = y + (8 - hh) / 2;
            ctx.fill(cx, top, cx + 1, top + hh, col);
        }
    }

    private static void text(DrawContext ctx, TextRenderer tr, String str, int x, int y, float scale, int maxW, int rgb, boolean shadow, boolean bold) {
        if (str == null || str.isEmpty() || maxW <= 0) return;
        int avail = Math.round(maxW / scale) - (bold ? str.length() / 4 : 0);
        if (tr.getWidth(str) > avail) str = tr.trimToWidth(str, Math.max(0, avail - tr.getWidth("..."))) + "...";
        Text t = bold ? Text.literal(str).formatted(Formatting.BOLD) : Text.literal(str);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        ctx.drawText(tr, t, 0, 0, 0xFF000000 | rgb, shadow);
        m.popMatrix();
    }

    /** Rounded rectangle with optional border, drawn as horizontal spans. */
    static void panel(DrawContext ctx, int x, int y, int w, int h, int r, int bw, int bg, int border) {
        r = Math.min(r, Math.min(w, h) / 2);
        bw = Math.min(bw, Math.min(w, h) / 2);
        int ir = Math.max(0, r - bw);
        for (int j = 0; j < h; j++) {
            int o = inset(j, h, r);
            int x0 = x + o, x1 = x + w - o;
            if (bw == 0) { ctx.fill(x0, y + j, x1, y + j + 1, bg); continue; }
            if (j < bw || j >= h - bw) { ctx.fill(x0, y + j, x1, y + j + 1, border); continue; }
            int ii = Math.max(o, bw + inset(j - bw, h - 2 * bw, ir));
            ctx.fill(x0, y + j, x + ii, y + j + 1, border);
            ctx.fill(x + ii, y + j, x + w - ii, y + j + 1, bg);
            ctx.fill(x + w - ii, y + j, x1, y + j + 1, border);
        }
    }

    private static int inset(int j, int hh, int rr) {
        if (rr <= 0) return 0;
        int d = j < rr ? rr - j : (j >= hh - rr ? j - (hh - rr) + 1 : 0);
        if (d == 0) return 0;
        double dy = d - 0.5;
        return rr - (int) Math.round(Math.sqrt(Math.max(0, rr * rr - dy * dy)));
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(Math.max(lo, hi), v)); }
}
