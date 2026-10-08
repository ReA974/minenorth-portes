package fr.minenorth.portes.door;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.registries.Registries;

import java.util.*;

public class OwnerDoorData extends SavedData {
    private static final String DATA_NAME = "minenorthportes_owner_doors";
    /** Chance de réussite du crochetage (%) selon le niveau de serrure : Normal, Avancé, Expert. */
    public static final int[] PICK_CHANCE = {75, 35, 5};

    public static int securityOf(DoorEntry e) { return e == null ? 0 : Math.max(0, Math.min(PICK_CHANCE.length - 1, e.security())); }

    private final Map<DoorKey, DoorEntry> doors = new HashMap<>();
    private final Map<UUID, Set<UUID>> trusted = new HashMap<>();
    private final Map<UUID, Map<UUID, DoorKey>> requests = new HashMap<>();
    private final Map<DoorKey, DoorListing> listings = new HashMap<>();

    public enum DoorType {
        PERSONAL, POLICE, POMPIER, ENTREPRISE, ORGANISATION, MAIRIE;
        public static DoorType from(String value) {
            try { return valueOf(value.toUpperCase(Locale.ROOT)); } catch (Exception e) { return PERSONAL; }
        }
    }

    public enum ListingMode {
        NONE, SALE, RENTAL;
        public static ListingMode from(String value) {
            try { return valueOf(value.toUpperCase(Locale.ROOT)); } catch (Exception e) { return NONE; }
        }
    }

    public record DoorListing(ListingMode mode, long price, int days, UUID renter, long expiryMs, String houseName) {
        public DoorListing(ListingMode mode, long price, int days, UUID renter, long expiryMs) { this(mode, price, days, renter, expiryMs, ""); }
        public boolean active() { return mode != ListingMode.NONE && price > 0; }
        public boolean rentalActive() { return mode == ListingMode.RENTAL && renter != null && expiryMs > System.currentTimeMillis(); }
    }

    public static OwnerDoorData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(OwnerDoorData::load, OwnerDoorData::new, DATA_NAME);
    }

    private static OwnerDoorData load(CompoundTag tag) {
        OwnerDoorData d = new OwnerDoorData();
        ListTag list = tag.getList("Doors", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(e.getString("Dim"));
            if (dim == null) continue;
            UUID owner = parse(e.getString("Owner"));
            String ownerName = e.getString("OwnerName");
            DoorType type = DoorType.from(e.getString("Type"));
            String permission = e.getString("Permission");
            DoorKey key = new DoorKey(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(e.getLong("Pos")));
            d.doors.put(key, new DoorEntry(owner, e.getBoolean("Temporary"), ownerName, type, permission, e.getString("HouseName"), e.getLong("PurchasePrice"), e.getInt("Security")));
        }
        ListTag tl = tag.getList("Trusted", 10);
        for (int i = 0; i < tl.size(); i++) {
            CompoundTag e = tl.getCompound(i);
            UUID owner = parse(e.getString("Owner"));
            if (owner == null) continue;
            Set<UUID> s = new HashSet<>();
            ListTag ids = e.getList("Players", 8);
            for (int j = 0; j < ids.size(); j++) {
                UUID u = parse(ids.getString(j));
                if (u != null) s.add(u);
            }
            d.trusted.put(owner, s);
        }
        ListTag sl = tag.getList("Listings", 10);
        for (int i = 0; i < sl.size(); i++) {
            CompoundTag e = sl.getCompound(i);
            ResourceLocation dim = ResourceLocation.tryParse(e.getString("Dim"));
            if (dim == null) continue;
            DoorKey key = new DoorKey(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(e.getLong("Pos")));
            DoorListing listing = new DoorListing(
                    ListingMode.from(e.getString("Mode")),
                    e.getLong("Price"), e.getInt("Days"), parse(e.getString("Renter")), e.getLong("Expiry"), e.getString("HouseName")
            );
            if (listing.active()) d.listings.put(key, listing);
        }
        d.expireRentals();
        return d;
    }

    private static UUID parse(String s) { try { return UUID.fromString(s); } catch (Exception e) { return null; } }

    @Override public CompoundTag save(CompoundTag tag) {
        ListTag dl = new ListTag();
        for (var en : doors.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putString("Dim", en.getKey().dimension.location().toString());
            e.putLong("Pos", en.getKey().pos.asLong());
            if (en.getValue().owner != null) e.putString("Owner", en.getValue().owner.toString());
            e.putBoolean("Temporary", en.getValue().temporary);
            e.putString("OwnerName", en.getValue().ownerName);
            e.putString("Type", en.getValue().type.name());
            e.putString("Permission", en.getValue().permission);
            e.putString("HouseName", en.getValue().houseName);
            e.putLong("PurchasePrice", en.getValue().purchasePrice());
            e.putInt("Security", en.getValue().security());
            dl.add(e);
        }
        tag.put("Doors", dl);
        ListTag tl = new ListTag();
        for (var en : trusted.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putString("Owner", en.getKey().toString());
            ListTag ids = new ListTag();
            for (UUID u : en.getValue()) ids.add(net.minecraft.nbt.StringTag.valueOf(u.toString()));
            e.put("Players", ids); tl.add(e);
        }
        tag.put("Trusted", tl);
        ListTag sl = new ListTag();
        for (var en : listings.entrySet()) {
            DoorListing l = en.getValue();
            if (!l.active()) continue;
            CompoundTag e = new CompoundTag();
            e.putString("Dim", en.getKey().dimension.location().toString());
            e.putLong("Pos", en.getKey().pos.asLong());
            e.putString("Mode", l.mode.name());
            e.putLong("Price", l.price());
            e.putInt("Days", l.days());
            if (l.renter() != null) e.putString("Renter", l.renter().toString());
            e.putLong("Expiry", l.expiryMs());
            e.putString("HouseName", l.houseName());
            sl.add(e);
        }
        tag.put("Listings", sl);
        return tag;
    }

    public static BlockPos normalize(ServerLevel level, BlockPos pos) {
        var s = level.getBlockState(pos);
        if (s.getBlock() instanceof DoorBlock && s.hasProperty(DoorBlock.HALF) && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) return pos.below();
        return pos;
    }

    public DoorEntry get(ServerLevel level, BlockPos pos) { return doors.get(new DoorKey(level.dimension(), normalize(level, pos))); }

    public void assign(ServerLevel level, BlockPos pos, UUID uuid, String name, boolean temporary, DoorType type, String permission) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        String house = "";
        DoorEntry previous = doors.get(key);
        if (previous != null) house = previous.houseName();
        long purchasePrice = previous == null ? 0 : previous.purchasePrice();
        int security = previous == null ? 0 : previous.security();
        doors.put(key, new DoorEntry(uuid, temporary, name, type, permission == null ? "" : permission, house, purchasePrice, security));
        listings.remove(key);
        setDirty();
    }

    public void unassign(ServerLevel level, BlockPos pos) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        doors.remove(key); listings.remove(key); setDirty();
    }

    public void setSecurity(ServerLevel level, BlockPos pos, int security) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        DoorEntry e = doors.get(key);
        if (e == null) return;
        doors.put(key, new DoorEntry(e.owner(), e.temporary(), e.ownerName(), e.type(), e.permission(), e.houseName(), e.purchasePrice(), Math.max(0, Math.min(PICK_CHANCE.length - 1, security))));
        setDirty();
    }

    public boolean isTrusted(UUID owner, UUID player) { return owner != null && (owner.equals(player) || trusted.getOrDefault(owner, Collections.emptySet()).contains(player)); }
    public boolean addTrusted(UUID owner, UUID player) { boolean a = trusted.computeIfAbsent(owner, k -> new HashSet<>()).add(player); if (a) setDirty(); return a; }
    public boolean removeTrusted(UUID owner, UUID player) { Set<UUID> s = trusted.get(owner); boolean a = s != null && s.remove(player); if (a) setDirty(); return a; }
    public Set<UUID> trusted(UUID owner) { return Set.copyOf(trusted.getOrDefault(owner, Collections.emptySet())); }

    public void request(ServerLevel level, BlockPos pos, UUID requester, UUID owner) { requests.computeIfAbsent(owner, k -> new HashMap<>()).put(requester, new DoorKey(level.dimension(), normalize(level, pos))); setDirty(); }
    public Map<UUID, DoorKey> requests(UUID owner) { return Map.copyOf(requests.getOrDefault(owner, Collections.emptyMap())); }
    public DoorKey popRequest(UUID owner, UUID requester) { Map<UUID, DoorKey> m = requests.get(owner); if (m == null) return null; DoorKey k = m.remove(requester); setDirty(); return k; }
    /** Retire les portes temporaires du joueur. Renvoie vrai si au moins une a été retirée. */
    public boolean clearTemporary(UUID owner) { boolean r = doors.entrySet().removeIf(e -> e.getValue().temporary && owner.equals(e.getValue().owner)); if (r) setDirty(); return r; }
    public Collection<Map.Entry<DoorKey, DoorEntry>> allDoors() { return List.copyOf(doors.entrySet()); }

    public DoorListing listing(ServerLevel level, BlockPos pos) {
        expireRentals();
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        DoorListing direct = listings.get(key);
        if (direct != null) return direct;
        DoorEntry entry = doors.get(key);
        if (entry == null || entry.houseName().isBlank()) return null;
        for (var en : listings.entrySet()) {
            if (!en.getKey().dimension().equals(key.dimension())) continue;
            DoorEntry other = doors.get(en.getKey());
            if (other != null && entry.houseName().equalsIgnoreCase(other.houseName())) return en.getValue();
        }
        return null;
    }
    public DoorListing listing(DoorKey key) {
        expireRentals();
        return listings.get(key);
    }

    public void setSale(ServerLevel level, BlockPos pos, long price) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        String house = doors.containsKey(key) ? doors.get(key).houseName() : "";
        setSale(level, pos, price, house);
    }

    public void setSale(ServerLevel level, BlockPos pos, long price, String houseName) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        String house = houseName == null ? "" : houseName.trim();
        setHouseName(level, pos, house);
        if (!house.isBlank()) {
            for (DoorKey k : houseDoors(key)) listings.put(k, new DoorListing(ListingMode.SALE, price, 0, null, 0, house));
        } else {
            listings.put(key, new DoorListing(ListingMode.SALE, price, 0, null, 0, house));
        }
        setDirty();
    }

    public void setRental(ServerLevel level, BlockPos pos, long price, int days) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        String house = doors.containsKey(key) ? doors.get(key).houseName() : "";
        setRental(level, pos, price, days, house);
    }

    public void setRental(ServerLevel level, BlockPos pos, long price, int days, String houseName) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        String house = houseName == null ? "" : houseName.trim();
        setHouseName(level, pos, house);
        if (!house.isBlank()) {
            for (DoorKey k : houseDoors(key)) listings.put(k, new DoorListing(ListingMode.RENTAL, price, Math.max(1, days), null, 0, house));
        } else {
            listings.put(key, new DoorListing(ListingMode.RENTAL, price, Math.max(1, days), null, 0, house));
        }
        setDirty();
    }

    public void clearListing(ServerLevel level, BlockPos pos) {
        listings.remove(new DoorKey(level.dimension(), normalize(level, pos)));
        setDirty();
    }

    public void rent(ServerLevel level, BlockPos pos, UUID renter, long expiryMs) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        DoorListing old = listings.get(key);
        if (old == null || old.mode() != ListingMode.RENTAL) return;
        listings.put(key, new DoorListing(ListingMode.RENTAL, old.price(), old.days(), renter, expiryMs, old.houseName()));
        setDirty();
    }

    public void clearRental(ServerLevel level, BlockPos pos) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        DoorListing old = listings.get(key);
        if (old != null && old.mode() == ListingMode.RENTAL) listings.put(key, new DoorListing(ListingMode.RENTAL, old.price(), old.days(), null, 0, old.houseName()));
        setDirty();
    }

    public boolean isRenter(ServerLevel level, BlockPos pos, UUID player) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        DoorListing direct = listing(level, pos);
        if (direct != null && direct.rentalActive() && direct.renter().equals(player)) return true;
        DoorEntry entry = doors.get(key);
        if (entry == null || entry.houseName().isBlank()) return false;
        for (var en : listings.entrySet()) {
            if (!en.getKey().dimension().equals(key.dimension())) continue;
            DoorListing l = en.getValue();
            if (!entry.houseName().equalsIgnoreCase(l.houseName())) continue;
            if (l.rentalActive() && l.renter().equals(player)) return true;
        }
        return false;
    }

    public void setHouseName(ServerLevel level, BlockPos pos, String houseName) {
        DoorKey key = new DoorKey(level.dimension(), normalize(level, pos));
        DoorEntry e = doors.get(key);
        if (e == null) return;
        String house = houseName == null ? "" : houseName.trim();
        doors.put(key, new DoorEntry(e.owner(), e.temporary(), e.ownerName(), e.type(), e.permission(), house, e.purchasePrice(), e.security()));
        DoorListing l = listings.get(key);
        if (l != null) listings.put(key, new DoorListing(l.mode(), l.price(), l.days(), l.renter(), l.expiryMs(), house));
        setDirty();
    }

    public List<DoorKey> houseDoors(DoorKey key) {
        DoorEntry base = doors.get(key);
        if (base == null || base.houseName().isBlank()) return List.of(key);
        List<DoorKey> result = new ArrayList<>();
        for (var en : doors.entrySet()) {
            if (en.getKey().dimension().equals(key.dimension()) && base.houseName().equalsIgnoreCase(en.getValue().houseName())) result.add(en.getKey());
        }
        return result;
    }

    public void transferHouse(DoorKey key, UUID newOwner, String newOwnerName, long purchasePrice) {
        List<DoorKey> keys = houseDoors(key);
        for (DoorKey k : keys) {
            DoorEntry e = doors.get(k);
            if (e != null && e.type() == DoorType.PERSONAL) {
                doors.put(k, new DoorEntry(newOwner, false, newOwnerName, DoorType.PERSONAL, "", e.houseName(), purchasePrice));
                listings.remove(k);
            }
        }
        setDirty();
    }

    public void transferHouse(DoorKey key, UUID newOwner, String newOwnerName) {
        DoorListing listing = listing(key);
        transferHouse(key, newOwner, newOwnerName, listing != null && listing.mode() == ListingMode.SALE ? listing.price() : 0);
    }

    public long housePurchasePrice(DoorKey key) {
        long price = 0;
        for (DoorKey k : houseDoors(key)) {
            DoorEntry e = doors.get(k);
            if (e != null && e.purchasePrice() > price) price = e.purchasePrice();
        }
        return price;
    }

    public void relistHouseForSale(DoorKey key, long price) {
        String house = doors.containsKey(key) ? doors.get(key).houseName() : "";
        for (DoorKey k : houseDoors(key)) {
            listings.put(k, new DoorListing(ListingMode.SALE, price, 0, null, 0, house));
        }
        setDirty();
    }

    public void relistHouseForRental(DoorKey key, long price, int days) {
        String house = doors.containsKey(key) ? doors.get(key).houseName() : "";
        for (DoorKey k : houseDoors(key)) {
            listings.put(k, new DoorListing(ListingMode.RENTAL, price, Math.max(1, days), null, 0, house));
        }
        setDirty();
    }

    public void returnHouseToMarket(DoorKey key, long price) {
        String house = doors.containsKey(key) ? doors.get(key).houseName() : "";
        for (DoorKey k : houseDoors(key)) {
            DoorEntry e = doors.get(k);
            if (e != null && e.type() == DoorType.PERSONAL) {
                doors.put(k, new DoorEntry(null, false, "", DoorType.PERSONAL, "", e.houseName(), 0));
                listings.put(k, new DoorListing(ListingMode.SALE, price, 0, null, 0, house));
            }
        }
        setDirty();
    }

    public void clearHouseListings(DoorKey key) {
        for (DoorKey k : houseDoors(key)) listings.remove(k);
        setDirty();
    }

    /** Remet en location les maisons dont la location est terminée. Renvoie vrai si au moins une a expiré. */
    public boolean expireRentals() {
        long now = System.currentTimeMillis();
        boolean dirty = false;
        for (var e : new ArrayList<>(listings.entrySet())) {
            DoorListing l = e.getValue();
            if (l.mode() == ListingMode.RENTAL && l.renter() != null && l.expiryMs() <= now) {
                relistHouseForRental(e.getKey(), l.price(), l.days());
                dirty = true;
            }
        }
        if (dirty) setDirty();
        return dirty;
    }

    public void replaceDoors(Collection<Map.Entry<DoorKey, DoorEntry>> entries) {
        doors.clear();
        for (var entry : entries) doors.put(entry.getKey(), entry.getValue());
        listings.entrySet().removeIf(e -> !doors.containsKey(e.getKey()));
        setDirty();
    }

    public record DoorKey(ResourceKey<Level> dimension, BlockPos pos) {}
    /** security : 0 = Normal, 1 = Avancé, 2 = Expert (voir PICK_CHANCE). */
    public record DoorEntry(UUID owner, boolean temporary, String ownerName, DoorType type, String permission, String houseName, long purchasePrice, int security) {
        public DoorEntry(UUID owner, boolean temporary, String ownerName, DoorType type, String permission, String houseName, long purchasePrice) { this(owner, temporary, ownerName, type, permission, houseName, purchasePrice, 0); }
        public DoorEntry(UUID owner, boolean temporary, String ownerName, DoorType type, String permission, String houseName) { this(owner, temporary, ownerName, type, permission, houseName, 0, 0); }
        public DoorEntry(UUID owner, boolean temporary, String ownerName, DoorType type, String permission) { this(owner, temporary, ownerName, type, permission, "", 0, 0); }
    }
}
