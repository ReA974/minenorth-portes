package fr.minenorth.portes.client;

import com.mojang.blaze3d.platform.NativeImage;
import fr.minenorth.portes.MineNorthPortes;
import fr.minenorth.portes.agency.PropertyImages;
import fr.minenorth.portes.network.ModNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
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
import java.util.Arrays;

/** Capture d'aperçu d'un bien : image de la vue du joueur (panneaux fermés), réduite en JPEG puis envoyée au serveur par morceaux. */
@Mod.EventBusSubscriber(modid = MineNorthPortes.MOD_ID, value = Dist.CLIENT)
public final class PropertyCapture {
    private PropertyCapture() {}

    private static final int WIDTH = 640, HEIGHT = 360;
    private static int countdown = -1;

    /** Demande une capture dans quelques ticks (le temps que l'écran se ferme). */
    public static void request() { countdown = 4; }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || countdown < 0) return;
        if (--countdown >= 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) return;
        try (NativeImage shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            byte[] jpeg = encode(shot);
            if (jpeg.length > PropertyImages.MAX_BYTES) { mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§cCapture trop lourde, réessayez."), true); return; }
            int total = (jpeg.length + PropertyImages.CHUNK_BYTES - 1) / PropertyImages.CHUNK_BYTES;
            for (int i = 0; i < total; i++) {
                byte[] part = Arrays.copyOfRange(jpeg, i * PropertyImages.CHUNK_BYTES, Math.min(jpeg.length, (i + 1) * PropertyImages.CHUNK_BYTES));
                ModNetwork.CHANNEL.sendToServer(new fr.minenorth.portes.network.ImageUploadPacket(i, total, part));
            }
        } catch (Exception ex) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§cCapture impossible."), true);
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
