package fr.minenorth.portes.gang;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** Un gang. L'identité des membres est TOUJOURS leur UUID (jamais le pseudo). Données modifiées uniquement par {@link GangService}. */
public final class Gang {
    /** Ligne du journal du gang : quand, qui (UUID), quoi. */
    public record LogEntry(long timeMs, UUID actor, String text) {}

    /** Mouvement du compte : quand, qui (UUID, null = automatique), montant (+ dépôt / - dépense), libellé. */
    public record Tx(long timeMs, UUID actor, long delta, String label) {}

    public static final int TX_MAX = 100;

    public final UUID id;
    String name;
    String tag;
    int color;
    final long created;
    final Map<UUID, GangRank> members = new LinkedHashMap<>();
    final List<LogEntry> log = new ArrayList<>();
    /** Point de base défini par le chef (dimension + position) : une porte libre proche de ce point peut rejoindre le gang. */
    String baseDim = "";
    long basePos;
    boolean hasBase;
    /** Objets de l'ancien coffre commun (supprimé) : remis au chef dès qu'il se connecte, puis effacés d'ici. */
    final List<ItemStack> legacyItems = new ArrayList<>();
    /** Compte du gang : solde en centimes (géré côté serveur), historique, et grade minimum autorisé à dépenser (0 chef, 1 lieutenant). */
    long balance;
    /** Part des revenus de zones versée à ce gang, en % (100 = normal ; réglée par l'admin). */
    int incomePct = 100;
    /** Fabrications lancées aujourd'hui par recette (limite par jour) et jour concerné (jours depuis 1970). */
    final java.util.Map<String, Integer> fabDaily = new java.util.HashMap<>();
    long fabDay;
    int spendRank = GangRank.LIEUTENANT.level();
    final List<Tx> txs = new ArrayList<>();
    /** Début de l'heure en cours pour le plafond de revenu, et somme déjà versée dans cette heure. */
    long incomeWindowStart;
    long incomePaid;

    Gang(UUID id, String name, String tag, int color, long created) {
        this.id = id; this.name = name; this.tag = tag; this.color = color; this.created = created;
    }

    public String name() { return name; }
    public String tag() { return tag; }
    public int color() { return color; }
    public long created() { return created; }
    public Map<UUID, GangRank> members() { return Collections.unmodifiableMap(members); }
    public GangRank rankOf(UUID player) { return player == null ? null : members.get(player); }
    public boolean isMember(UUID player) { return player != null && members.containsKey(player); }
    public List<LogEntry> log() { return Collections.unmodifiableList(log); }
    public boolean hasBase() { return hasBase; }
    public long balance() { return balance; }
    public int incomePct() { return incomePct; }

    /** Nombre de fabrications lancées aujourd'hui pour cette recette (remis à zéro chaque jour). */
    public int fabToday(String label) {
        long day = java.time.LocalDate.now().toEpochDay();
        if (fabDay != day) { fabDaily.clear(); fabDay = day; }
        return fabDaily.getOrDefault(label, 0);
    }
    public GangRank spendRank() { return GangRank.ofLevel(spendRank); }
    public List<Tx> txs() { return Collections.unmodifiableList(txs); }

    void addTx(UUID actor, long delta, String label) {
        txs.add(new Tx(System.currentTimeMillis(), actor, delta, label));
        while (txs.size() > TX_MAX) txs.remove(0);
    }
    public String baseDim() { return baseDim; }
    public long basePos() { return basePos; }

    public UUID chef() {
        for (var e : members.entrySet()) if (e.getValue() == GangRank.CHEF) return e.getKey();
        return null;
    }

    /** Texte coloré « [TAG] Nom » pour le chat. */
    public String label() { return "§" + GangRules.chatColor(color) + "[" + tag + "] " + name + "§r"; }

    void addLog(UUID actor, String text, int max) {
        log.add(new LogEntry(System.currentTimeMillis(), actor, text));
        while (log.size() > max) log.remove(0);
    }

    CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("Id", id);
        t.putString("Name", name);
        t.putString("Tag", tag);
        t.putInt("Color", color);
        t.putLong("Created", created);
        ListTag ml = new ListTag();
        for (var e : members.entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putUUID("Uuid", e.getKey());
            m.putInt("Rank", e.getValue().level());
            ml.add(m);
        }
        t.put("Members", ml);
        ListTag ll = new ListTag();
        for (LogEntry le : log) {
            CompoundTag l1 = new CompoundTag();
            l1.putLong("Time", le.timeMs());
            if (le.actor() != null) l1.putUUID("Actor", le.actor());
            l1.putString("Text", le.text());
            ll.add(l1);
        }
        t.put("Log", ll);
        if (hasBase) { t.putString("BaseDim", baseDim); t.putLong("BasePos", basePos); }
        if (!legacyItems.isEmpty()) {
            ListTag il = new ListTag();
            for (ItemStack st : legacyItems) il.add(st.save(new CompoundTag()));
            t.put("Legacy", il);
        }
        t.putLong("Balance", balance);
        t.putInt("IncomePct", incomePct);
        t.putLong("FabDay", fabDay);
        ListTag fd = new ListTag();
        for (var e : fabDaily.entrySet()) { CompoundTag c = new CompoundTag(); c.putString("K", e.getKey()); c.putInt("N", e.getValue()); fd.add(c); }
        t.put("FabDaily", fd);
        t.putInt("SpendRank", spendRank);
        ListTag tl = new ListTag();
        for (Tx x : txs) {
            CompoundTag c = new CompoundTag();
            c.putLong("Time", x.timeMs()); if (x.actor() != null) c.putUUID("Actor", x.actor()); c.putLong("Delta", x.delta()); c.putString("Label", x.label());
            tl.add(c);
        }
        t.put("Tx", tl);
        t.putLong("IncomeStart", incomeWindowStart);
        t.putLong("IncomePaid", incomePaid);
        return t;
    }

    static Gang load(CompoundTag t) {
        if (!t.hasUUID("Id")) return null;
        Gang g = new Gang(t.getUUID("Id"), t.getString("Name"), t.getString("Tag"), t.getInt("Color"), t.getLong("Created"));
        ListTag ml = t.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < ml.size(); i++) {
            CompoundTag m = ml.getCompound(i);
            if (m.hasUUID("Uuid")) g.members.put(m.getUUID("Uuid"), GangRank.ofLevel(m.getInt("Rank")));
        }
        ListTag ll = t.getList("Log", Tag.TAG_COMPOUND);
        for (int i = 0; i < ll.size(); i++) {
            CompoundTag l1 = ll.getCompound(i);
            g.log.add(new LogEntry(l1.getLong("Time"), l1.hasUUID("Actor") ? l1.getUUID("Actor") : null, l1.getString("Text")));
        }
        if (t.contains("BaseDim") && !t.getString("BaseDim").isBlank()) { g.hasBase = true; g.baseDim = t.getString("BaseDim"); g.basePos = t.getLong("BasePos"); }
        // Anciens mondes : le contenu de l'ancien coffre commun ("Vault", 54 cases) et les objets déjà en attente ("Legacy") sont gardés pour être rendus au chef.
        for (String key : new String[]{"Vault", "Legacy"}) {
            ListTag il = t.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < il.size(); i++) {
                ItemStack st = ItemStack.of(il.getCompound(i));
                if (!st.isEmpty()) g.legacyItems.add(st);
            }
        }
        g.balance = Math.max(0, t.getLong("Balance"));
        g.incomePct = t.contains("IncomePct") ? Math.max(0, Math.min(300, t.getInt("IncomePct"))) : 100;
        g.fabDay = t.getLong("FabDay");
        ListTag fd = t.getList("FabDaily", Tag.TAG_COMPOUND);
        for (int i = 0; i < fd.size(); i++) g.fabDaily.put(fd.getCompound(i).getString("K"), fd.getCompound(i).getInt("N"));
        g.spendRank = t.contains("SpendRank") ? Math.max(0, Math.min(1, t.getInt("SpendRank"))) : GangRank.LIEUTENANT.level();
        ListTag tl = t.getList("Tx", Tag.TAG_COMPOUND);
        for (int i = 0; i < tl.size(); i++) {
            CompoundTag c = tl.getCompound(i);
            g.txs.add(new Tx(c.getLong("Time"), c.hasUUID("Actor") ? c.getUUID("Actor") : null, c.getLong("Delta"), c.getString("Label")));
        }
        g.incomeWindowStart = t.getLong("IncomeStart");
        g.incomePaid = t.getLong("IncomePaid");
        // Sécurité : un gang chargé doit avoir exactement un chef.
        if (g.members.isEmpty()) return null;
        if (g.chef() == null) g.members.put(g.members.keySet().iterator().next(), GangRank.CHEF);
        return g;
    }
}
