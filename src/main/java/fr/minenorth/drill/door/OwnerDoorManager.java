package fr.minenorth.portes.door;

import fr.minenorth.portes.config.SystemeConfigFiles;
import fr.minenorth.portes.network.ModNetwork;
import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;

@Mod.EventBusSubscriber
public final class OwnerDoorManager {
    private OwnerDoorManager() {}
    private static final Map<UUID, OwnerDoorData.DoorKey> CONTEXTS = new HashMap<>();
    private static final Map<UUID, BlockPos> CONTEXT_POS = new HashMap<>();
    private static final Map<String, PermissionNode<Boolean>> PERMISSION_NODES = new HashMap<>();

    @SubscribeEvent
    public static void gatherPermissions(PermissionGatherEvent.Nodes event) {
        registerPermission(event, "grade.police");
        registerPermission(event, "grade.pompier");
        registerPermission(event, "grade.medical");
        registerPermission(event, "grade.mairie");
        // Custom company/organisation nodes can be declared in porte.toml before startup.
        for (String permission : SystemeConfigFiles.readConfiguredPermissions()) registerPermission(event, permission);
    }

    private static void registerPermission(PermissionGatherEvent.Nodes event, String permission) {
        if (permission == null || permission.isBlank() || PERMISSION_NODES.containsKey(permission)) return;
        PermissionNode<Boolean> node = new PermissionNode<>("minenorthportes", permission, PermissionTypes.BOOLEAN, (player, uuid, context) -> player != null && player.hasPermissions(2));
        PERMISSION_NODES.put(permission, node);
        event.addNodes(node);
    }

    /** Handles a right click forwarded by the client when the door is locally locked. */
    public static void handleClientInteract(ServerPlayer p, BlockPos pos, boolean sneaking) {
        ServerLevel l = p.serverLevel();
        if (!(l.getBlockState(pos).getBlock() instanceof DoorBlock)) return;
        OwnerDoorData d = OwnerDoorData.get(l);
        OwnerDoorData.DoorEntry entry = d.get(l, pos);
        if (entry == null) {
            // The client may have stale lock data; only OPs may manage an unassigned door.
            if (sneaking && p.hasPermissions(2)) {
                openAssignment(p, l, pos);
            }
            return;
        }
        if (sneaking) {
            // OP administrators can manage ANY assigned door, including doors
            // already owned by another player. This check must happen before
            // sale/request/owner panels so the administrator always gets the
            // management panel.
            if (p.hasPermissions(2)) {
                sendAdminPanel(p, l, pos, entry);
                return;
            }
            // Porte reliée à une entreprise : son patron peut la gérer.
            if (entry.type() == OwnerDoorData.DoorType.ENTREPRISE && EntrepriseCompat.linked(entry.permission())
                    && EntrepriseCompat.isOwner(p, EntrepriseCompat.id(entry.permission()))) {
                sendGroupOwnerPanel(p, l, pos, entry);
                return;
            }
            OwnerDoorData.DoorListing listing = d.listing(l, pos);
            if (listing != null && listing.active() && (entry.owner() == null || !entry.owner().equals(p.getUUID()))) {
                sendListingPanel(p, l, pos, entry);
                return;
            }
            if (entry.owner() != null && entry.owner().equals(p.getUUID())) {
                sendOwnerPanel(p, l, pos, entry);
            } else if (p.hasPermissions(2) && entry.owner() == null) {
                sendGroupOwnerPanel(p, l, pos, entry);
            } else if (entry.type() == OwnerDoorData.DoorType.PERSONAL && entry.owner() != null) {
                sendRequestPanel(p, l, pos, entry);
            }
            return;
        }
        if (canOpen(p, l, pos, entry)) {
            // Le client croyait la porte verrouillée (liste pas encore resynchronisée) : on l'ouvre côté serveur.
            toggleDoor(l, pos);
        } else {
            p.displayClientMessage(Component.translatable("message.minenorthsysteme.door_denied"), true);
        }
    }

    /** Ouvre ou ferme une porte sans passer par l'interaction vanilla. */
    static void toggleDoor(ServerLevel l, BlockPos pos) {
        BlockPos low = OwnerDoorData.normalize(l, pos);
        var state = l.getBlockState(low);
        if (state.getBlock() instanceof DoorBlock door) door.setOpen(null, l, state, low, !state.getValue(DoorBlock.OPEN));
    }

    @SubscribeEvent
    public static void interact(PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide || !(e.getEntity() instanceof ServerPlayer p) || !(e.getLevel() instanceof ServerLevel l)) return;
        if (!(l.getBlockState(e.getPos()).getBlock() instanceof DoorBlock)) return;

        OwnerDoorData d = OwnerDoorData.get(l);
        OwnerDoorData.DoorEntry entry = d.get(l, e.getPos());

        if (!p.isShiftKeyDown() && entry == null) {
            OwnerDoorData.DoorListing listing = d.listing(l, e.getPos());
            if (listing != null && listing.active()) {
                sendListingPanel(p, l, e.getPos(), null);
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }

        if (p.isShiftKeyDown()) {
            // A protected door must NEVER receive the vanilla interaction when
            // the player is sneaking. This prevents the door from opening
            // client-side/server-side before the management/request UI is shown.
            if (entry == null) {
                if (p.hasPermissions(2)) {
                    openAssignment(p, l, e.getPos());
                }
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }

            // OP always gets the administration panel first, even when the
            // door is unowned or is a Police/Pompier/Entreprise/Organisation
            // door whose logical owner is represented by its permission type.
            // This is intentionally checked before the normal owner flow.
            if (p.hasPermissions(2)) {
                sendAdminPanel(p, l, e.getPos(), entry);
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            // Porte reliée à une entreprise : son patron peut la gérer.
            if (entry.type() == OwnerDoorData.DoorType.ENTREPRISE && EntrepriseCompat.linked(entry.permission())
                    && EntrepriseCompat.isOwner(p, EntrepriseCompat.id(entry.permission()))) {
                sendGroupOwnerPanel(p, l, e.getPos(), entry);
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }

            OwnerDoorData.DoorListing listing = d.listing(l, e.getPos());
            if (listing != null && listing.active() && (entry.owner() == null || !entry.owner().equals(p.getUUID()))) {
                sendListingPanel(p, l, e.getPos(), entry);
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }

            if (entry.owner() != null && entry.owner().equals(p.getUUID())) {
                sendOwnerPanel(p, l, e.getPos(), entry);
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }

            // Only Enterprise and Organisation doors expose an access-request
            // menu. Police and Firefighter doors never show that menu.
            if ((entry.type() == OwnerDoorData.DoorType.ENTREPRISE
                    || entry.type() == OwnerDoorData.DoorType.ORGANISATION)
                    && entry.owner() != null && !canOpen(p, l, e.getPos(), entry)) {
                sendRequestPanel(p, l, e.getPos(), entry);
            }

            // Police/Pompier, as well as unauthorized Enterprise/Organisation,
            // are always cancelled here so the vanilla door cannot animate.
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.FAIL);
            return;
        }

        // Une porte personnelle en vente/location se présente directement au clic droit normal.
        // Le joueur n'a pas besoin de faire Shift + clic droit pour consulter l'annonce.
        if (entry != null) {
            OwnerDoorData.DoorListing listing = d.listing(l, e.getPos());
            // Le locataire (ou toute personne autorisée) ouvre la porte : l'annonce ne s'affiche qu'aux autres.
            if (listing != null && listing.active() && !canOpen(p, l, e.getPos(), entry)
                    && (entry.owner() == null || !entry.owner().equals(p.getUUID()))) {
                sendListingPanel(p, l, e.getPos(), entry);
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            if (!canOpen(p, l, e.getPos(), entry)) {
                p.displayClientMessage(Component.translatable("message.minenorthsysteme.door_denied"), true);
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.FAIL);
            }
        }
    }

    public static boolean canOpen(ServerPlayer player, ServerLevel level, BlockPos pos, OwnerDoorData.DoorEntry entry) {
        OwnerDoorData data = OwnerDoorData.get(level);
        return switch (entry.type()) {
            // Le locataire doit pouvoir ouvrir même si la maison n'a aucun propriétaire (maison gérée par les OP).
            case PERSONAL -> data.isRenter(level, pos, player.getUUID())
                    || (entry.owner() != null && data.isTrusted(entry.owner(), player.getUUID()))
                    // Perquisition acceptée par le Commissaire : la police ouvre les portes du citoyen.
                    || PoliceCompat.canSearch(player, entry.owner());
            case POLICE -> hasPermission(player, "grade.police") || PoliceCompat.isPolice(player);
            case POMPIER -> hasPermission(player, "grade.pompier") || SecoursCompat.isSecours(player);
            case MAIRIE -> hasPermission(player, "grade.mairie") || MairieCompat.isStaff(player);
            case ENTREPRISE, ORGANISATION -> EntrepriseCompat.linked(entry.permission())
                    // Porte reliée à une entreprise : patron + employés du grade autorisé (les OP ouvrent toujours).
                    ? player.hasPermissions(2) || EntrepriseCompat.canOpen(player, EntrepriseCompat.id(entry.permission()), EntrepriseCompat.minGrade(entry.permission()))
                    : !entry.permission().isBlank() && hasPermission(player, entry.permission());
        };
    }

    /**
     * Checks a permission node through Forge's permission service when available.
     * The fallback accepts OP so server administrators never get locked out.
     */
    public static boolean hasPermission(ServerPlayer player, String permission) {
        if (player.hasPermissions(2)) return true;
        PermissionNode<Boolean> node = PERMISSION_NODES.get(permission);
        if (node == null) return false;
        try { return PermissionAPI.getPermission(player, node); }
        catch (RuntimeException ignored) { return false; }
    }

    private static void openAssignment(ServerPlayer p, ServerLevel l, BlockPos pos) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        ModNetwork.sendDoorPanel(p, l, pos, 0, "", List.of(), List.of(), OwnerDoorData.DoorType.PERSONAL, "", false);
    }

    private static void sendOwnerPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        OwnerDoorData d = OwnerDoorData.get(l);
        List<String> trusted = new ArrayList<>();
        if (e.owner() != null) {
            for (UUID u : d.trusted(e.owner())) {
                trusted.add(MineNorth.displayName(p.server, u));
            }
        }
        List<String> req = new ArrayList<>();
        if (e.owner() != null) {
            for (UUID u : d.requests(e.owner()).keySet()) {
                req.add(MineNorth.displayName(p.server, u) + "|" + u);
            }
        }
        ModNetwork.sendDoorPanel(p, l, pos, 1, ownerLabel(p, e), trusted, req, e.type(), e.permission(), e.temporary());
    }

    private static void sendGroupOwnerPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        ModNetwork.sendDoorPanel(p, l, pos, 1, ownerLabel(p, e), List.of(), List.of(), e.type(), e.permission(), e.temporary());
    }

    private static void sendAdminPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        OwnerDoorData d = OwnerDoorData.get(l);
        List<String> trusted = new ArrayList<>();
        if (e.owner() != null) {
            for (UUID u : d.trusted(e.owner())) {
                trusted.add(MineNorth.displayName(p.server, u));
            }
        }
        ModNetwork.sendDoorPanel(p, l, pos, 4, ownerLabel(p, e), trusted, List.of(), e.type(), e.permission(), e.temporary());
    }

    private static void sendRequestPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        ModNetwork.sendDoorPanel(p, l, pos, 2, ownerLabel(p, e), List.of(), List.of(), e.type(), e.permission(), e.temporary());
    }

    private static void sendListingPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        OwnerDoorData.DoorListing listing = OwnerDoorData.get(l).listing(l, pos);
        if (listing == null) return;
        String renter = "";
        if (listing.renter() != null) {
            renter = MineNorth.displayName(p.server, listing.renter());
        }
        ModNetwork.sendDoorPanel(p, l, pos, 3, ownerLabel(p, e), List.of(), List.of(), e.type(), e.permission(), e.temporary());
    }

    public static void assign(ServerPlayer actor, BlockPos pos, String name, boolean temporary, OwnerDoorData.DoorType type, String permission) {
        ServerLevel l = actor.serverLevel();
        UUID uuid = null;
        String ownerName = "";
        if (type == OwnerDoorData.DoorType.PERSONAL) {
            String targetName = name == null ? "" : name.trim();
            var profile = actor.server.getProfileCache().get(targetName).orElse(null);
            if (profile == null && actor.getGameProfile().getName().equalsIgnoreCase(targetName)) {
                profile = actor.getGameProfile();
            }
            if (profile == null) { actor.displayClientMessage(Component.translatable("message.minenorthsysteme.door_player_not_found"), true); return; }
            if (temporary && actor.server.getPlayerList().getPlayer(profile.getId()) == null) { actor.displayClientMessage(Component.translatable("message.minenorthsysteme.door_temp_online"), true); return; }
            uuid = profile.getId(); ownerName = profile.getName();
        } else {
            temporary = false;
            permission = switch (type) {
                case POLICE -> "grade.police";
                case POMPIER -> "grade.pompier";
                case MAIRIE -> "grade.mairie";
                case ENTREPRISE, ORGANISATION -> permission == null ? "" : permission.trim();
                default -> "";
            };
            if ((type == OwnerDoorData.DoorType.ENTREPRISE || type == OwnerDoorData.DoorType.ORGANISATION) && permission.isBlank()) {
                actor.displayClientMessage(Component.translatable("message.minenorthsysteme.door_permission_required"), true); return;
            }
            ownerName = switch (type) {
                case POLICE -> "Police";
                case POMPIER -> "Pompiers";
                case MAIRIE -> "Mairie";
                case ENTREPRISE -> companyLabel(actor, permission);
                case ORGANISATION -> "Organisation";
                default -> "";
            };
        }
        OwnerDoorData data = OwnerDoorData.get(l);
        data.assign(l, pos, uuid, ownerName, temporary, type, permission);
        SystemeConfigFiles.writeDoors(actor.server, data);
        actor.displayClientMessage(Component.translatable("message.minenorthsysteme.door_assigned", ownerName), true);
        ModNetwork.syncDoorLocks(actor.server);
    }

    /** Nom affiché comme « propriétaire » d'une porte d'entreprise. */
    private static String companyLabel(ServerPlayer actor, String permission) {
        if (!EntrepriseCompat.linked(permission)) return "Entreprise";
        String name = EntrepriseCompat.name(actor.server, EntrepriseCompat.id(permission));
        return name.isBlank() ? "Entreprise" : name;
    }

    public static void addTrusted(ServerPlayer owner, String name) {
        var profile = owner.server.getProfileCache().get(name).orElse(null);
        if (profile == null) { owner.displayClientMessage(Component.translatable("message.minenorthsysteme.door_player_not_found"), true); return; }
        boolean ok = OwnerDoorData.get(owner.serverLevel()).addTrusted(owner.getUUID(), profile.getId());
        owner.displayClientMessage(Component.translatable(ok ? "message.minenorthsysteme.door_access_added" : "message.minenorthsysteme.door_access_exists", profile.getName()), true);
        ModNetwork.syncDoorLocks(owner.server);
    }

    public static void removeTrusted(ServerPlayer owner, String name) {
        var profile = owner.server.getProfileCache().get(name).orElse(null);
        if (profile == null) { owner.displayClientMessage(Component.translatable("message.minenorthsysteme.door_player_not_found"), true); return; }
        OwnerDoorData.get(owner.serverLevel()).removeTrusted(owner.getUUID(), profile.getId());
        owner.displayClientMessage(Component.translatable("message.minenorthsysteme.door_access_removed", profile.getName()), true);
        ModNetwork.syncDoorLocks(owner.server);
    }

    public static void request(ServerPlayer player, BlockPos pos) {
        ServerLevel l = player.serverLevel(); OwnerDoorData d = OwnerDoorData.get(l); OwnerDoorData.DoorEntry e = d.get(l, pos);
        if (e == null || e.owner() == null || e.type() != OwnerDoorData.DoorType.PERSONAL) return;
        if (d.isTrusted(e.owner(), player.getUUID())) return;
        d.request(l, pos, player.getUUID(), e.owner());
        ServerPlayer owner = player.server.getPlayerList().getPlayer(e.owner());
        if (owner != null) owner.displayClientMessage(Component.translatable("message.minenorthsysteme.door_request", MineNorth.displayName(player)), true);
        player.displayClientMessage(Component.translatable("message.minenorthsysteme.door_request_sent"), true);
    }

    public static void respond(ServerPlayer owner, UUID requester, boolean accept) {
        OwnerDoorData d = OwnerDoorData.get(owner.serverLevel()); OwnerDoorData.DoorKey key = d.popRequest(owner.getUUID(), requester);
        if (key == null) return;
        if (accept) d.addTrusted(owner.getUUID(), requester);
        ModNetwork.syncDoorLocks(owner.server);
        ServerPlayer p = owner.server.getPlayerList().getPlayer(requester);
        if (p != null) p.displayClientMessage(Component.translatable(accept ? "message.minenorthsysteme.door_request_accepted" : "message.minenorthsysteme.door_request_refused", MineNorth.displayName(owner)), true);
    }

    public static void unassign(ServerPlayer owner, BlockPos pos) {
        OwnerDoorData data = OwnerDoorData.get(owner.serverLevel());
        data.unassign(owner.serverLevel(), pos);
        SystemeConfigFiles.writeDoors(owner.server, data);
        ModNetwork.syncDoorLocks(owner.server);
        owner.displayClientMessage(Component.translatable("message.minenorthsysteme.door_unassigned"), true);
    }

    private static long price(String text) {
        try {
            String v = text == null ? "" : text.trim().replace(',', '.').replace("€", "").trim();
            if (v.isBlank()) return -1;
            return Math.round(Double.parseDouble(v) * 100.0);
        } catch (Exception e) { return -1; }
    }

    public static void listSale(ServerPlayer owner, BlockPos pos, long cents, String houseName) {
        ServerLevel l = owner.serverLevel(); OwnerDoorData d = OwnerDoorData.get(l); OwnerDoorData.DoorEntry e = d.get(l,pos);
        if (e == null || e.type() != OwnerDoorData.DoorType.PERSONAL || cents <= 0 || !owner.hasPermissions(2)) return;
        String house = houseName == null ? "" : houseName.trim();
        d.setSale(l,pos,cents,house); SystemeConfigFiles.writeDoors(owner.server,d); ModNetwork.syncDoorLocks(owner.server);
        owner.displayClientMessage(Component.literal("§aPorte mise en vente pour " + String.format("%.2f", cents/100.0) + " €" + (house.isBlank()?"":" • Maison : "+house)), true);
    }

    public static void listRental(ServerPlayer owner, BlockPos pos, long cents, int days, String houseName) {
        ServerLevel l = owner.serverLevel(); OwnerDoorData d = OwnerDoorData.get(l); OwnerDoorData.DoorEntry e = d.get(l,pos);
        if (e == null || e.type() != OwnerDoorData.DoorType.PERSONAL || cents <= 0 || days <= 0 || !owner.hasPermissions(2)) return;
        String house = houseName == null ? "" : houseName.trim();
        d.setRental(l,pos,cents,days,house); SystemeConfigFiles.writeDoors(owner.server,d); ModNetwork.syncDoorLocks(owner.server);
        owner.displayClientMessage(Component.literal("§aPorte proposée en location : " + String.format("%.2f", cents/100.0) + " € / " + days + " jour(s)" + (house.isBlank()?"":" • Maison : "+house)), true);
    }

    /** Nom affiché du propriétaire : nom RP (MineNorth API) pour une porte personnelle, sinon le nom enregistré. */
    private static String ownerLabel(ServerPlayer viewer, OwnerDoorData.DoorEntry e) {
        return e.owner() != null ? MineNorth.displayName(viewer.server, e.owner()) : e.ownerName();
    }

    /**
     * Paiement par carte d'une vente ou location. Porte d'un joueur : l'argent va au propriétaire (virement).
     * Porte sans propriétaire (marché, mairie) ou propriétaire sans compte : l'argent part au trésor public.
     */
    private static PayResult pay(ServerPlayer payer, java.util.UUID owner, long cents, String source) {
        var bank = MineNorth.bank();
        if (owner == null || owner.equals(payer.getUUID()) || !bank.hasAccount(payer.server, owner)) return bank.charge(payer, cents, source);
        PayResult check = bank.check(payer, cents);
        if (check != PayResult.OK) return check;
        PayResult r = bank.transfer(payer.server, payer.getUUID(), owner, cents);
        if (r == PayResult.OK) {
            ServerPlayer o = payer.server.getPlayerList().getPlayer(owner);
            if (o != null) o.sendSystemMessage(Component.literal("§a" + MineNorth.displayName(payer) + " vous a versé "
                    + String.format("%.2f", cents / 100.0) + " € (" + (source.endsWith("location") ? "location" : "achat") + ")."));
        }
        return r;
    }

    private static void buy(ServerPlayer buyer, ServerLevel l, BlockPos pos) {
        if (buyer.distanceToSqr(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5) > 36.0) return;
        OwnerDoorData d = OwnerDoorData.get(l); OwnerDoorData.DoorEntry e = d.get(l,pos); OwnerDoorData.DoorListing listing=d.listing(l,pos);
        if(e==null || listing==null || listing.mode()!=OwnerDoorData.ListingMode.SALE) return;
        if(e.owner()!=null && e.owner().equals(buyer.getUUID())) return;
        PayResult r = pay(buyer, e.owner(), listing.price(), "portes:achat");
        if(r != PayResult.OK){ buyer.displayClientMessage(Component.literal("§cPaiement refusé : "+r.message()), true); return; }
        String oldOwner=e.ownerName();
        String house=listing.houseName();
        OwnerDoorData.DoorKey key=new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l,pos));
        d.transferHouse(key,buyer.getUUID(),buyer.getGameProfile().getName(),listing.price());
        SystemeConfigFiles.writeDoors(buyer.server,d); ModNetwork.syncDoorLocks(buyer.server);
        String scope=house == null || house.isBlank() ? "cette porte" : "la maison « "+house+" »";
        buyer.displayClientMessage(Component.literal("§aVous avez acheté "+scope+" pour "+String.format("%.2f",listing.price()/100.0)+" €. Toutes les portes de cette maison vous sont accessibles."), false);
    }

    private static void resell(ServerPlayer seller, ServerLevel l, BlockPos pos) {
        if (seller.distanceToSqr(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5) > 36.0) return;
        OwnerDoorData d = OwnerDoorData.get(l);
        OwnerDoorData.DoorEntry e = d.get(l, pos);
        if (e == null || e.owner() == null || !e.owner().equals(seller.getUUID()) || e.type() != OwnerDoorData.DoorType.PERSONAL) return;
        OwnerDoorData.DoorKey key = new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos));
        long originalPrice = d.housePurchasePrice(key);
        if (originalPrice <= 0) {
            seller.displayClientMessage(Component.literal("§cCette maison n'a pas de prix d'achat enregistré."), true);
            return;
        }
        long refund = originalPrice / 2;
        if (refund <= 0) return;
        MineNorth.bank().refund(seller.server, seller.getUUID(), refund, "portes:revente");
        d.returnHouseToMarket(key, originalPrice);
        SystemeConfigFiles.writeDoors(seller.server, d);
        ModNetwork.syncDoorLocks(seller.server);
        seller.displayClientMessage(Component.literal("§aMaison revendue. Vous récupérez "+String.format("%.2f", refund/100.0)+" € (50 % du prix d'achat). La maison est remise en vente à "+String.format("%.2f", originalPrice/100.0)+" €."), false);
    }

    private static void rent(ServerPlayer renter, ServerLevel l, BlockPos pos) {
        if (renter.distanceToSqr(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5) > 36.0) return;
        OwnerDoorData d=OwnerDoorData.get(l); OwnerDoorData.DoorEntry e=d.get(l,pos); OwnerDoorData.DoorListing listing=d.listing(l,pos);
        if(e==null || listing==null || listing.mode()!=OwnerDoorData.ListingMode.RENTAL || listing.renter()!=null) return;
        if(e.owner()!=null && e.owner().equals(renter.getUUID())) return;
        PayResult r=pay(renter, e.owner(), listing.price(), "portes:location");
        if(r!=PayResult.OK){ renter.displayClientMessage(Component.literal("§cPaiement refusé : "+r.message()), true); return; }
        long expiry=System.currentTimeMillis()+listing.days()*86_400_000L;
        d.rent(l,pos,renter.getUUID(),expiry); SystemeConfigFiles.writeDoors(renter.server,d); ModNetwork.syncDoorLocks(renter.server);
        renter.displayClientMessage(Component.literal("§aLocation activée pour "+listing.days()+" jour(s). Toutes les portes de cette maison vous sont accessibles."), false);
    }

    public static void handleAction(ServerPlayer player, int action, String text, boolean flag, String extra) {
        OwnerDoorData.DoorKey key = CONTEXTS.get(player.getUUID()); if (key == null) return;
        ServerLevel level = player.server.getLevel(key.dimension()); if (level == null) return;
        BlockPos pos = key.pos(); OwnerDoorData data = OwnerDoorData.get(level); OwnerDoorData.DoorEntry entry = data.get(level, pos);
        switch (action) {
            case 0 -> {
                // Attribution directe : aucune mise en vente/location n'est requise.
                if (!player.hasPermissions(2) || entry != null) return;
                String[] parts = (extra == null ? "PERSONAL|" : extra).split("\\|", 2);
                OwnerDoorData.DoorType type = OwnerDoorData.DoorType.from(parts[0]);
                String permission = parts.length > 1 ? parts[1] : "";
                assign(player, pos, text, flag, type, permission);
                if (type == OwnerDoorData.DoorType.PERSONAL && permission != null && !permission.isBlank()) {
                    data.setHouseName(level, pos, permission.trim());
                    SystemeConfigFiles.writeDoors(player.server, data);
                    ModNetwork.syncDoorLocks(player.server);
                }
                OwnerDoorData.DoorEntry updated = data.get(level, pos);
                if (updated != null && type == OwnerDoorData.DoorType.PERSONAL) {
                    sendOwnerPanel(player, level, pos, updated);
                } else if (updated != null) {
                    sendAdminPanel(player, level, pos, updated);
                }
            }
            case 18 -> {
                // Porte libre : création de la maison (sans propriétaire) + mise en vente/location en une seule étape.
                // extra = "SALE|0|<maison>" ou "RENTAL|<jours>|<maison>", text = prix.
                if (!player.hasPermissions(2) || entry != null) return;
                String[] parts = (extra == null ? "" : extra).split("\\|", 3);
                boolean rental = "RENTAL".equals(parts[0]);
                int days = 0;
                try { days = Integer.parseInt(parts.length > 1 ? parts[1].trim() : "0"); } catch (NumberFormatException ignored) {}
                String house = parts.length > 2 ? parts[2].trim() : "";
                long cents = price(text);
                if (house.isBlank() || cents <= 0 || (rental && days <= 0)) {
                    player.displayClientMessage(Component.literal("§cNom de maison, prix" + (rental ? " et durée" : "") + " obligatoires."), true);
                    return;
                }
                data.assign(level, pos, null, "", false, OwnerDoorData.DoorType.PERSONAL, "");
                data.setHouseName(level, pos, house);
                if (rental) listRental(player, pos, cents, days, house); else listSale(player, pos, cents, house);
                OwnerDoorData.DoorEntry updated = data.get(level, pos);
                if (updated != null) sendAdminPanel(player, level, pos, updated);
            }
            case 19 -> {
                // Le patron (ou un OP) choisit à partir de quel grade les employés ouvrent cette porte.
                if (entry == null || entry.type() != OwnerDoorData.DoorType.ENTREPRISE || !EntrepriseCompat.linked(entry.permission())) return;
                int cid = EntrepriseCompat.id(entry.permission());
                if (!player.hasPermissions(2) && !EntrepriseCompat.isOwner(player, cid)) return;
                int min;
                try { min = Integer.parseInt(text.trim()); } catch (RuntimeException ex) { return; }
                data.assign(level, pos, null, entry.ownerName(), false, OwnerDoorData.DoorType.ENTREPRISE, EntrepriseCompat.permission(cid, min));
                SystemeConfigFiles.writeDoors(player.server, data);
                ModNetwork.syncDoorLocks(player.server);
                OwnerDoorData.DoorEntry updated = data.get(level, pos);
                if (updated != null) sendGroupOwnerPanel(player, level, pos, updated);
            }
            case 16 -> {
                // OP creates a personal property shell without any owner. The house is sold/rented later.
                if (!player.hasPermissions(2) || entry != null) return;
                String house = text == null ? "" : text.trim();
                if (house.isBlank()) return;
                data.assign(level, pos, null, "", false, OwnerDoorData.DoorType.PERSONAL, "");
                data.setHouseName(level, pos, house);
                SystemeConfigFiles.writeDoors(player.server, data);
                ModNetwork.syncDoorLocks(player.server);
                OwnerDoorData.DoorEntry updated = data.get(level, pos);
                if (updated != null) sendAdminPanel(player, level, pos, updated);
            }
            case 1 -> { if (entry == null || entry.owner() == null || !entry.owner().equals(player.getUUID())) return; addTrusted(player, text); sendOwnerPanel(player, level, pos, entry); }
            case 2 -> { if (entry == null || entry.owner() == null || !entry.owner().equals(player.getUUID())) return; removeTrusted(player, text); sendOwnerPanel(player, level, pos, entry); }
            case 3 -> { if (entry == null || !(entry.owner() == null ? player.hasPermissions(2) : entry.owner().equals(player.getUUID()))) return; unassign(player, pos); }
            case 4 -> request(player, pos);
            case 5, 6 -> { if (entry == null || entry.owner() == null || !entry.owner().equals(player.getUUID())) return; UUID u; try { u = UUID.fromString(extra); } catch (Exception ex) { return; } respond(player, u, action == 5); sendOwnerPanel(player, level, pos, entry); }
            case 7 -> { if (entry == null || (!player.hasPermissions(2) && (entry.owner() == null || !entry.owner().equals(player.getUUID())))) return; long cents=price(text); if(cents>0) listSale(player,pos,cents,extra); if(player.hasPermissions(2)) sendAdminPanel(player,level,pos,data.get(level,pos)); else sendOwnerPanel(player,level,pos,data.get(level,pos)); }
            case 8 -> { if (entry == null || (!player.hasPermissions(2) && (entry.owner() == null || !entry.owner().equals(player.getUUID())))) return; long cents=price(text); int days=0; String house=""; try { int sep=extra.indexOf('|'); if(sep>=0){days=Integer.parseInt(extra.substring(0,sep).trim()); house=extra.substring(sep+1).trim();} else days=Integer.parseInt(extra.trim()); } catch(Exception ex){} if(cents>0&&days>0) listRental(player,pos,cents,days,house); if(player.hasPermissions(2)) sendAdminPanel(player,level,pos,data.get(level,pos)); else sendOwnerPanel(player,level,pos,data.get(level,pos)); }
            case 15 -> {
                if (entry == null || (!player.hasPermissions(2) && (entry.owner() == null || !entry.owner().equals(player.getUUID())))) return;
                data.setHouseName(level,pos,text);
                SystemeConfigFiles.writeDoors(player.server,data); ModNetwork.syncDoorLocks(player.server);
                OwnerDoorData.DoorEntry updated=data.get(level,pos);
                if (updated != null) { if (player.hasPermissions(2)) sendAdminPanel(player,level,pos,updated); else sendOwnerPanel(player,level,pos,updated); }
            }
            case 9 -> { if (entry == null || entry.owner() == null || !entry.owner().equals(player.getUUID())) return; data.clearListing(level,pos); SystemeConfigFiles.writeDoors(player.server,data); ModNetwork.syncDoorLocks(player.server); sendOwnerPanel(player,level,pos,entry); }
            case 10 -> buy(player,level,pos);
            case 11 -> rent(player,level,pos);
            case 17 -> {
                if (entry == null || entry.owner() == null || !entry.owner().equals(player.getUUID()) || entry.type() != OwnerDoorData.DoorType.PERSONAL) return;
                resell(player, level, pos);
                OwnerDoorData.DoorEntry updated = data.get(level, pos);
                if (updated != null) sendOwnerPanel(player, level, pos, updated);
            }
            // OP-only administration of an already assigned door.
            case 12 -> {
                if (!player.hasPermissions(2) || entry == null) return;
                String[] parts = (extra == null ? "PERSONAL|" : extra).split("\\|", 2);
                OwnerDoorData.DoorType type = OwnerDoorData.DoorType.from(parts[0]);
                String permission = parts.length > 1 ? parts[1] : "";
                assign(player, pos, text, flag, type, permission);
                // assign() replaces the entry; refresh the admin panel with the new state.
                OwnerDoorData.DoorEntry updated = data.get(level, pos);
                if (updated != null) sendAdminPanel(player, level, pos, updated);
            }
            case 13 -> {
                if (!player.hasPermissions(2) || entry == null) return;
                unassign(player, pos);
            }
            case 14 -> {
                if (!player.hasPermissions(2) || entry == null) return;
                data.clearListing(level, pos);
                SystemeConfigFiles.writeDoors(player.server, data);
                ModNetwork.syncDoorLocks(player.server);
                OwnerDoorData.DoorEntry updated = data.get(level, pos);
                if (updated != null) sendAdminPanel(player, level, pos, updated);
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent e) {
        if (e.phase != net.minecraftforge.event.TickEvent.Phase.END || e.getServer().getTickCount() % 20 != 0) return;
        OwnerDoorData d = OwnerDoorData.get(e.getServer().overworld());
        // Fin de location : accès coupé tout de suite. Sinon, recalcul toutes les 10 s pour suivre les changements
        // faits par les autres mods (grade police, embauche/licenciement en entreprise, perquisition…).
        // Seuls les joueurs dont la liste a changé reçoivent un paquet.
        boolean expired = d.expireRentals();
        if (expired) SystemeConfigFiles.writeDoors(e.getServer(), d);
        if (expired || e.getServer().getTickCount() % 40 == 0) ModNetwork.syncDoorLocks(e.getServer());
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            OwnerDoorData data = OwnerDoorData.get(p.serverLevel());
            CONTEXTS.remove(p.getUUID());
            CONTEXT_POS.remove(p.getUUID());
            ModNetwork.forgetLocks(p.getUUID());
            // On ne réécrit porte.toml que si le joueur avait des portes temporaires.
            if (data.clearTemporary(p.getUUID())) {
                SystemeConfigFiles.writeDoors(p.server, data);
                ModNetwork.syncDoorLocks(p.server);
            }
        }
    }

    @SubscribeEvent public static void login(PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            ModNetwork.syncDoorLocks(p.server);
        }
    }
}
