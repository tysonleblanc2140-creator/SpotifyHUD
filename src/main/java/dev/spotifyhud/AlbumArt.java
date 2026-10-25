package dev.spotifyhud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads album covers (JPEG) off-thread, decodes with ImageIO, uploads as a 128x128 GUI texture. */
public final class AlbumArt {
    public static final int SIZE = 128;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SpotifyHUD-art");
        t.setDaemon(true);
        return t;
    });

    private static volatile String requested = "";
    private static String loadedUrl = "";
    private static Identifier current;
    private static int counter;

    private AlbumArt() {}

    /** Returns the texture for this URL once loaded, otherwise null (HUD draws a placeholder). */
    public static Identifier get(String url) {
        if (url == null || url.isEmpty()) return null;
        if (!url.equals(requested)) {
            requested = url;
            fetch(url);
        }
        return url.equals(loadedUrl) ? current : null;
    }

    private static void fetch(String url) {
        EX.execute(() -> {
            try {
                HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) return;
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(r.body()));
                if (src == null) return;
                BufferedImage dst = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = dst.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, SIZE, SIZE, null);
                g.dispose();
                int[] px = dst.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
                MinecraftClient.getInstance().execute(() -> upload(url, px));
            } catch (Throwable t) {
                SpotifyHudMod.LOG.warn("Album art load failed: {}", t.toString());
            }
        });
    }

    private static void upload(String url, int[] px) {
        if (!url.equals(requested)) return; // a newer track was requested meanwhile
        MinecraftClient mc = MinecraftClient.getInstance();
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++)
                img.setColorArgb(x, y, px[y * SIZE + x]);
        Identifier id = Identifier.of("spotifyhud", "cover/" + (++counter));
        mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "spotifyhud cover", img));
        if (current != null) mc.getTextureManager().destroyTexture(current);
        current = id;
        loadedUrl = url;
    }
}
