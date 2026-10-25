package dev.spotifyhud;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;

/** HUD customization screen: drag the live preview, tweak settings in the right-hand panel. */
public class ConfigScreen extends Screen {
    private static final String[] TABS = {"Layout", "Colors", "Show", "Spotify"};
    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{6}");
    private static final int PW = 176;

    private final Screen parent;
    private int tab;
    private boolean dragging;
    private int offX, offY;
    private final List<Object[]> labels = new ArrayList<>(); // {String, x, y}

    public ConfigScreen(Screen parent) {
        super(Text.literal("Spotify HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        labels.clear();
        HudConfig c = HudConfig.get();
        int px = width - PW - 6, y = 6;
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            ButtonWidget b = ButtonWidget.builder(Text.literal(TABS[i]), bt -> { tab = idx; clearAndInit(); })
                    .dimensions(px + i * 44, y, 42, 16).build();
            b.active = idx != tab;
            addDrawableChild(b);
        }
        y = 30;
        switch (tab) {
            case 0 -> {
                addDrawableChild(ButtonWidget.builder(Text.literal("Style: " + c.style), b -> {
                    HudConfig.Style[] v = HudConfig.Style.values();
                    c.applyStyle(v[(c.style.ordinal() + 1) % v.length]);
                    clearAndInit();
                }).dimensions(px, y, PW, 20).build());
                y += 22; slider(px, y, "Width", 100, 400, c.width, v -> c.width = v);
                y += 22; slider(px, y, "Height", 24, 140, c.height, v -> c.height = v);
                y += 22; slider(px, y, "Cover size", 16, 96, c.coverSize, v -> c.coverSize = v);
                y += 22; slider(px, y, "Text size %", 60, 200, c.textScalePercent, v -> c.textScalePercent = v);
                y += 22; slider(px, y, "Corner radius", 0, 12, c.cornerRadius, v -> c.cornerRadius = v);
                y += 22; slider(px, y, "Border", 0, 4, c.borderWidth, v -> c.borderWidth = v);
            }
            case 1 -> {
                slider(px, y, "Background opacity %", 0, 100, c.bgOpacity, v -> c.bgOpacity = v);
                y += 24; hex(px, y, "Background", c.bgColor, v -> c.bgColor = v);
                y += 22; hex(px, y, "Text", c.textColor, v -> c.textColor = v);
                y += 22; hex(px, y, "Secondary text", c.subTextColor, v -> c.subTextColor = v);
                y += 22; hex(px, y, "Accent", c.accentColor, v -> c.accentColor = v);
                y += 22; hex(px, y, "Border", c.borderColor, v -> c.borderColor = v);
            }
            case 2 -> {
                int hw = PW / 2 - 2;
                toggle(px, y, hw, "HUD", () -> c.enabled, v -> c.enabled = v);
                toggle(px + hw + 4, y, hw, "Cover", () -> c.showCover, v -> c.showCover = v);
                y += 22; toggle(px, y, hw, "Title", () -> c.showTitle, v -> c.showTitle = v);
                toggle(px + hw + 4, y, hw, "Artist", () -> c.showArtist, v -> c.showArtist = v);
                y += 22; toggle(px, y, hw, "Progress", () -> c.showProgress, v -> c.showProgress = v);
                toggle(px + hw + 4, y, hw, "Times", () -> c.showTimes, v -> c.showTimes = v);
                y += 22; toggle(px, y, hw, "Controls", () -> c.showControls, v -> c.showControls = v);
                toggle(px + hw + 4, y, hw, "Status", () -> c.showStatus, v -> c.showStatus = v);
                y += 22; toggle(px, y, hw, "Equalizer", () -> c.showEqualizer, v -> c.showEqualizer = v);
                toggle(px + hw + 4, y, hw, "Glow", () -> c.glow, v -> c.glow = v);
                y += 22; toggle(px, y, hw, "Text shadow", () -> c.textShadow, v -> c.textShadow = v);
                toggle(px + hw + 4, y, hw, "Bold title", () -> c.boldTitle, v -> c.boldTitle = v);
            }
            default -> {
                TextFieldWidget f = new TextFieldWidget(textRenderer, px, y + 10, PW, 18, Text.literal("Client ID"));
                f.setMaxLength(64);
                f.setPlaceholder(Text.literal("Spotify Client ID"));
                f.setText(c.clientId);
                f.setChangedListener(s -> c.clientId = s.trim());
                addDrawableChild(f);
                labels.add(new Object[]{"Client ID", px, y});
                y += 32;
                addDrawableChild(ButtonWidget.builder(Text.literal("Connect to Spotify"), b -> { HudConfig.save(); SpotifyHudMod.login(); })
                        .dimensions(px, y, PW, 20).build());
                y += 22;
                addDrawableChild(ButtonWidget.builder(Text.literal("Disconnect"), b -> Spotify.logout())
                        .dimensions(px, y, PW, 20).build());
            }
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(px, height - 26, 84, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset style"), b -> { c.applyStyle(c.style); clearAndInit(); })
                .dimensions(px + 88, height - 26, 88, 20).build());
    }

    private void slider(int x, int y, String label, int min, int max, int val, IntConsumer set) {
        addDrawableChild(new Sl(x, y, label, min, max, val, set));
    }

    private void toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label + ": " + (get.getAsBoolean() ? "ON" : "OFF")), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(Text.literal(label + ": " + (get.getAsBoolean() ? "ON" : "OFF")));
        }).dimensions(x, y, w, 20).build());
    }

    private void hex(int x, int y, String label, int val, IntConsumer set) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x + 100, y, PW - 100, 18, Text.literal(label));
        f.setMaxLength(7);
        f.setText(String.format("#%06X", val));
        f.setChangedListener(s -> { if (HEX.matcher(s).matches()) set.accept(Integer.parseInt(s.replace("#", ""), 16)); });
        addDrawableChild(f);
        labels.add(new Object[]{label, x, y + 5});
    }

    private static final class Sl extends SliderWidget {
        private final String label;
        private final int min, max;
        private final IntConsumer set;

        Sl(int x, int y, String label, int min, int max, int val, IntConsumer set) {
            super(x, y, PW, 20, Text.empty(), (val - min) / (double) (max - min));
            this.label = label; this.min = min; this.max = max; this.set = set;
            updateMessage();
        }

        private int cur() { return min + (int) Math.round(value * (max - min)); }

        @Override protected void updateMessage() {
            if (label != null) setMessage(Text.literal(label + ": " + cur()));
        }

        @Override protected void applyValue() { set.accept(cur()); }
    }

    // ------------------------------------------------------------------ render

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0x88000000);
        ctx.fill(width - PW - 12, 0, width, height, 0xCC101418);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        for (Object[] l : labels) ctx.drawTextWithShadow(textRenderer, (String) l[0], (Integer) l[1], (Integer) l[2], 0xFFDDDDDD);
        if (tab == 3) {
            int y = 30 + 32 + 22 + 22 + 6;
            Spotify.Snapshot s = Spotify.snapshot();
            ctx.drawTextWithShadow(textRenderer, "Status: " + (s.status() == Spotify.Status.OK ? "Connected" : s.message()),
                    width - PW - 6, y, 0xFFFFFF88);
            String help = "1) developer.spotify.com/dashboard: create an app.  2) Add redirect URI " + Spotify.redirectUri()
                    + "  3) Paste its Client ID above, press Connect and approve in your browser.";
            y += 14;
            for (OrderedText line : textRenderer.wrapLines(Text.literal(help), PW)) {
                ctx.drawTextWithShadow(textRenderer, line, width - PW - 6, y, 0xFF999999);
                y += 10;
            }
        }
        HudRenderer.render(ctx, width, height, mx, my);
        ctx.drawTextWithShadow(textRenderer, "Drag the HUD to move it. Click its buttons to control Spotify.", 8, height - 14, 0xFFAAAAAA);
    }

    // --------------------------------------------------------------- dragging

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() == 0) {
            double mx = click.x(), my = click.y();
            Runnable r = HudRenderer.controlAt(mx, my);
            if (r != null) { r.run(); return true; }
            if (HudRenderer.inPanel(mx, my)) {
                HudConfig c = HudConfig.get();
                c.x = HudRenderer.lastX; c.y = HudRenderer.lastY;
                offX = (int) mx - c.x; offY = (int) my - c.y;
                dragging = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging) {
            HudConfig c = HudConfig.get();
            c.x = HudRenderer.clamp((int) click.x() - offX, 0, width - HudRenderer.lastW);
            c.y = HudRenderer.clamp((int) click.y() - offY, 0, height - HudRenderer.lastH);
            return true;
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (dragging) { dragging = false; HudConfig.save(); return true; }
        return super.mouseReleased(click);
    }

    @Override
    public void removed() { HudConfig.save(); }

    @Override
    public void close() { client.setScreen(parent); }
}
