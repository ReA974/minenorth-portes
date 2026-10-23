package fr.minenorth.portes.gang;

import net.minecraft.world.item.Item;

/**
 * Objet « Fabrique d'armes ». Volontairement un objet simple et NON un BlockItem : WorldGuard refuse toute pose de bloc hors de ses zones
 * autorisées dès qu'un objet de type bloc est en main (serveur hybride). La pose et la reprise sont faites par
 * {@link FactoryManager#placeFromUse} (setBlock direct, après nos propres règles), appelé depuis {@link GangEvents}.
 */
public class FactoryItem extends Item {
    public FactoryItem(Properties props) { super(props); }
}
