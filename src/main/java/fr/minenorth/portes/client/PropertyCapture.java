package fr.minenorth.portes.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.minenorth.portes.MineNorthPortes;
import fr.minenorth.portes.agency.PropertyImages;
import fr.minenorth.portes.network.ImageUploadPacket;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mode photo d'un admin : un clic droit prend une photo (interface masquée), 3 photos en tout. Dès la 3e, elles partent
 * toutes au serveur, qui les enregistre sur l'annonce. Ouvrir un menu annule le mode.
 */
@Mod.EventBusSubscriber(modid = MineNorthPortes.MOD_ID, value = Dist.CLIENT)
public final class PropertyCapture {
    private PropertyCapture() {}

    private static final int WIDTH = 640, HEIGHT = 360;
    private static boolean active;
    private static final List<byte[]> shots = new ArrayList<>();
    /** Ticks restants avant la prise (le temps que l'interface masquée soit dessinée), -1 = pas de prise en cours. */
    private static int waiting = -1;
    private static boolean previousHideGui;

    /** Entre dans le mode photo. */
    public static void start() {
        active = true;
        shots.clear();
        waiting = -1;
        say("§bMode photo : clic droit pour prendre la photo 1/" + PropertyImages.PHOTOS + " (ouvrez un menu pour annuler).");
    }

    private static void say(String text) {
        var p = Minecraft.getInstance().player;
        if (p != null) p.displayClientMessage(Component.literal(text), true);
    }

    private static void cancel(String reason) {
        if (waiting >= 0) Minecraft.getInstance().options.hideGui = previousHideGui;
        active = false;
        waiting = -1;
        shots.clear();
        if (reason != null) say(reason);
    }

    /** Pendant le mode photo, le clic droit prend la photo ; les autres actions (casser, poser, ouvrir) sont bloquées. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered e) {
        if (!active) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        if (e.isUseItem() && waiting < 0) {
            Minecraft mc = Minecraft.getInstance();
            previousHideGui = mc.options.hideGui;
            mc.options.hideGui = true;
            waiting = 2;
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { active = false; waiting = -1; shots.clear(); return; }
        if (mc.screen != null) { cancel("§cMode photo annulé."); return; }
        if (waiting < 0) return;
        if (--waiting > 0) return;
        waiting = -1;
        try (NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            byte[] jpeg = encode(shot);
            mc.options.hideGui = previousHideGui;
            if (jpeg.length > PropertyImages.MAX_BYTES) { say("§cPhoto trop lourde, reprenez-la."); return; }
            shots.add(jpeg);
        } catch (Exception ex) {
            mc.options.hideGui = previousHideGui;
            say("§cPhoto impossible.");
            return;
        }
        if (shots.size() < PropertyImages.PHOTOS) {
            say("§aPhoto " + shots.size() + "/" + PropertyImages.PHOTOS + " prise. Clic droit pour la photo " + (shots.size() + 1) + ".");
            return;
        }
        upload();
        active = false;
        shots.clear();
        say("§aLes " + PropertyImages.PHOTOS + " photos sont envoyées au serveur.");
    }

    private static void upload() {
        for (int photo = 0; photo < shots.size(); photo++) {
            byte[] jpeg = shots.get(photo);
            int total = (jpeg.length + PropertyImages.CHUNK_BYTES - 1) / PropertyImages.CHUNK_BYTES;
            for (int i = 0; i < total; i++) {
                byte[] part = Arrays.copyOfRange(jpeg, i * PropertyImages.CHUNK_BYTES, Math.min(jpeg.length, (i + 1) * PropertyImages.CHUNK_BYTES));
                ModNetwork.CHANNEL.sendToServer(new ImageUploadPacket(photo, i, total, part));
            }
        }
    }

    /** Réduit l'image à 640x360 et l'encode en JPEG (qualité 0,8). */
    private static byte[] encode(NativeImage src) throws Exception {
        BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) {
            int sy = y * src.getHeight() / HEIGHT;
            for (int x = 0; x < WIDTH; x++) {
                int abgr = src.getPixelRGBA(x * src.getWidth() / WIDTH, sy);
                int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF;
                out.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.8f);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(out, null, null), param);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }
}
