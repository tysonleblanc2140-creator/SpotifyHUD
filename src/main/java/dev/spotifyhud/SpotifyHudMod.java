package dev.spotifyhud;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class SpotifyHudMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("spotifyhud");
    private static boolean renderWarned;

    @Override
    public void onInitializeClient() {
        HudConfig.load();
        Spotify.init();

        HudElementRegistry.attachElementAfter(VanillaHudElements.SUBTITLES, Identifier.of("spotifyhud", "hud"), (ctx, tick) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!HudConfig.get().enabled || mc.currentScreen instanceof ConfigScreen) return;
            try {
                HudRenderer.render(ctx, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight(), -1, -1);
            } catch (Throwable t) {
                if (!renderWarned) { renderWarned = true; LOG.error("HUD render failed", t); }
            }
        });

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("spotifyhud", "main"));
        KeyBinding open = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.spotifyhud.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, cat));
        KeyBinding prev = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.spotifyhud.prev", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, cat));
        KeyBinding next = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.spotifyhud.next", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, cat));
        KeyBinding play = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.spotifyhud.playpause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (open.wasPressed()) mc.setScreen(new ConfigScreen(mc.currentScreen));
            while (prev.wasPressed()) Spotify.previous();
            while (next.wasPressed()) Spotify.next();
            while (play.wasPressed()) Spotify.togglePlay();
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("spotifyhud")
                        .then(ClientCommandManager.literal("login").executes(c -> { login(); return 1; }))
                        .then(ClientCommandManager.literal("logout").executes(c -> { Spotify.logout(); msg("Disconnected from Spotify."); return 1; }))
                        .then(ClientCommandManager.literal("config").executes(c -> { openConfig(); return 1; }))
                        .then(ClientCommandManager.literal("clientid").then(ClientCommandManager.argument("id", StringArgumentType.word())
                                .executes(c -> {
                                    HudConfig.get().clientId = StringArgumentType.getString(c, "id").trim();
                                    HudConfig.save();
                                    msg("Client ID saved. Now run /spotifyhud login");
                                    return 1;
                                })))));
    }

    public static void openConfig() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new ConfigScreen(null))); // deferred: chat closes itself after a command
    }

    public static void login() {
        try {
            String url = Spotify.beginLogin();
            try { Util.getOperatingSystem().open(URI.create(url)); } catch (Throwable ignored) {}
            Text link = Text.literal("[Click here if your browser didn't open: log in to Spotify]")
                    .setStyle(Style.EMPTY.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))).withColor(Formatting.GREEN).withUnderline(true));
            msg(link);
        } catch (Exception e) {
            msg(Text.literal("Spotify login couldn't start: " + e.getMessage()).formatted(Formatting.RED));
        }
    }

    public static void msg(String s) { msg(Text.literal("[Spotify HUD] " + s)); }

    public static void msg(Text t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> { if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(t); });
    }
}
