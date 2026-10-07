package fr.minenorth.portes.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Bouton plat MineNorth (même rendu que le mod Permis). Couleur GHOST = simple texte aligné à droite. */
public class MineNorthButton extends AbstractButton {
    public static final int GHOST = 0;
    private final Runnable action; private final int color;
    public MineNorthButton(int x,int y,int w,int h,Component label,int color,Runnable action){super(x,y,w,h,label);this.color=color;this.action=action;}
    public MineNorthButton enabled(boolean on){this.active=on;return this;}
    @Override public void onPress(){if(active)action.run();}
    @Override protected void updateWidgetNarration(NarrationElementOutput out){defaultButtonNarrationText(out);}
    @Override public void renderWidget(GuiGraphics g,int mx,int my,float pt){
        boolean hov=isHoveredOrFocused();
        if(color==GHOST){
            Font font=Minecraft.getInstance().font;
            int ty=getY()+(height-8)/2;
            int tc=!active?MineNorthStyle.MUTED:hov?MineNorthStyle.WHITE:MineNorthStyle.TEXT;
            int tx=getX()+width-font.width(getMessage());
            g.drawString(font,getMessage(),tx,ty,tc,false);
            if(hov&&active)g.fill(tx,ty+9,getX()+width,ty+10,tc);
            return;
        }
        MineNorthStyle.button(g,getX(),getY(),width,height,getMessage().getString(),color,hov,active);
    }
}
