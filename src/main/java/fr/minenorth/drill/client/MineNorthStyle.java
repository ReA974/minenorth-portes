package fr.minenorth.portes.client;

import fr.minenorth.portes.MineNorthPortes;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Charte graphique MineNorth : identique au mod Permis / ATM EuroBank (fond bleu nuit, boutons plats). */
public final class MineNorthStyle {
    public static final int FRAME = 0xFF0E0E10;
    /** Fond du panneau. */
    public static final int PANEL = 0xFF161048;
    /** Fond des blocs / listes. */
    public static final int LIST = 0xFF0E0A34;
    public static final int HOVER = 0xFF2E2480;
    public static final int CYAN = 0xFF20AAEB;
    public static final int DARK = 0xFF4A3CB4;
    public static final int PINK = 0xFFC83CF0;
    /** Vert des boutons de validation. */
    public static final int GREEN = 0xFF1E9E52;
    /** Couleur des libellés de champs. */
    public static final int BLUE = 0xFF20AAEB;
    public static final int TEXT = 0xFFCFE3FF;
    public static final int MUTED = 0xFF8FA8E0;
    public static final int WARN = 0xFFFFE066;
    public static final int ALERT = 0xFFFF6A9A;
    /** Vert des textes de statut. */
    public static final int OK = 0xFF6CFF9A;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int DISABLED = 0xFF2A2468;
    public static final ResourceLocation LOGO = new ResourceLocation(MineNorthPortes.MOD_ID, "textures/gui/logo.png");

    private MineNorthStyle() {}

    public static int lighten(int c) {
        int r=Math.min(255,((c>>16)&255)+35), g=Math.min(255,((c>>8)&255)+35), b=Math.min(255,(c&255)+35);
        return 0xFF000000|(r<<16)|(g<<8)|b;
    }
    public static Component bold(String s){ return Component.literal(s).withStyle(ChatFormatting.BOLD); }

    /** Fond + cadre + logo + titre agrandi, comme les écrans Permis. */
    public static void panel(GuiGraphics g,int left,int top,int w,int h,String title,String subtitle){
        Font font=Minecraft.getInstance().font;
        g.fill(left-3,top-3,left+w+3,top+h+3,FRAME);
        g.fill(left,top,left+w,top+h,PANEL);
        RenderSystem.enableBlend();
        g.blit(LOGO,left+8,top+5,28,28,0,0,256,256,256,256);
        scaled(g,bold(title),left+42,top+9,1.6f,WHITE);
        if(subtitle!=null&&!subtitle.isBlank()) g.drawString(font,subtitle,left+42,top+26,MUTED,false);
        g.fill(left+10,top+40,left+w-10,top+41,HOVER);
    }
    public static void scaled(GuiGraphics g,Component c,int x,int y,float s,int color){
        Font font=Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().scale(s,s,1f);
        g.drawString(font,c,Math.round(x/s),Math.round(y/s),color,false);
        g.pose().popPose();
    }
    /** Bloc sombre avec liseré de couleur à gauche. */
    public static void card(GuiGraphics g,int x,int y,int w,int h,boolean hover,int accent){
        g.fill(x,y,x+w,y+h,hover?HOVER:LIST);
        g.fill(x,y,x+3,y+h,accent);
    }
    /** Bouton plat. */
    public static void button(GuiGraphics g,int x,int y,int w,int h,String label,int color,boolean hover,boolean active){
        Font f=Minecraft.getInstance().font;
        int bg=!active?DISABLED:(hover?lighten(color):color);
        g.fill(x,y,x+w,y+h,bg);
        g.drawString(f,label,x+w/2-f.width(label)/2,y+(h-8)/2,active?WHITE:MUTED,false);
    }
    public static void item(GuiGraphics g,ItemStack st,int x,int y,float scale){
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale(scale,scale,1);g.renderItem(st,0,0);g.pose().popPose();
    }
    public static String euros(long cents){
        long a=Math.abs(cents); return (cents<0?"-":"")+(a/100)+(a%100==0?"":","+String.format("%02d",a%100))+" €";
    }
}
