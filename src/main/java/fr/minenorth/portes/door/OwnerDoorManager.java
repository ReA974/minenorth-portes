package fr.minenorth.portes.door;

import fr.minenorth.portes.config.SystemeConfigFiles;
import fr.minenorth.portes.network.ModNetwork;
import fr.minenorth.api.MineNorth;
import fr.minenorth.api.PayResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import fr.minenorth.portes.item.LockUpgradeItem;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
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
    /** Porte dont le panneau est ouvert chez ce joueur (null si aucune). */
    public static OwnerDoorData.DoorKey contextKey(ServerPlayer p) { return CONTEXTS.get(p.getUUID()); }
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
        if (permission.startsWith(fr.minenorth.portes.gang.GangDoors.PREFIX)) return; // portes de gang : le droit vient du gang, pas d'un noeud de permission
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
        if (sneaking && fr.minenorth.portes.gang.GangDoors.onSneakClick(p, l, pos, entry)) return;
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
                sendAdminPreview(p, l, pos, entry);
                return;
            }
            // Porte reliée à une entreprise : son patron peut la gérer.
            if (entry.type() == OwnerDoorData.DoorType.ENTREPRISE && EntrepriseCompat.linked(entry.permission())
                    && EntrepriseCompat.isOwner(p, EntrepriseCompat.id(entry.permission()))) {
                sendGroupOwnerPanel(p, l, pos, entry);
                return;
            }
            // Locataire : il gère les personnes autorisées chez lui (au lieu de l'annonce « location déjà active »).
            if (canManageTrust(p, l, pos, entry) && !(entry.owner() != null && entry.owner().equals(p.getUUID()))) {
                sendRenterPanel(p, l, pos, entry);
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
        // Le clic vanilla arrive aussi au serveur (interact) : l'ouverture, le crochet et la serrure y sont traités.
        if (!canOpen(p, l, pos, entry)) {
            p.displayClientMessage(Component.translatable("message.minenorthsysteme.door_denied"), true);
        }
    }

    /**
     * Sneak + clic droit avec une serrure renforcée sur sa propre porte de maison : applique le niveau et consomme l'item.
     * Renvoie vrai si un item de serrure était en main (pose réussie ou refusée), pour que le clic ne fasse rien d'autre.
     */
    private static boolean tryUpgrade(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry entry) {
        ItemStack held = p.getMainHandItem();
        if (!(held.getItem() instanceof LockUpgradeItem lock)) return false;
        if (entry.type() != OwnerDoorData.DoorType.PERSONAL || entry.owner() == null || !entry.owner().equals(p.getUUID())) {
            p.displayClientMessage(Component.literal("§cSeul le propriétaire d'une porte de maison peut la renforcer."), true);
            return true;
        }
        OwnerDoorData d = OwnerDoorData.get(l);
        int old = OwnerDoorData.securityOf(entry);
        d.setSecurity(l, pos, lock.level() + 1);
        if (!p.getAbilities().instabuild) {
            held.shrink(1);
            // L'ancienne serrure est rendue au propriétaire.
            if (old > 0) giveLock(p, old);
        }
        SystemeConfigFiles.writeDoors(p.server, d);
        String[] names = {"Normal", "Avancé", "Expert"};
        p.displayClientMessage(Component.literal("§aSerrure renforcée installée : niveau " + names[lock.level()] + "."), true);
        return true;
    }

    /** Rend l'item de serrure d'un niveau donné (1 à 3) : inventaire, sinon au sol. */
    private static void giveLock(ServerPlayer p, int security) {
        ItemStack st = new ItemStack(fr.minenorth.portes.item.ModItems.lockFor(security));
        p.getInventory().add(st);
        if (!st.isEmpty()) p.drop(st, false);
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
            // Gangs : attribuer une porte libre de la base, ou gérer une porte du gang (les OP gardent le panneau d'administration).
            if (e.getHand() == InteractionHand.MAIN_HAND && fr.minenorth.portes.gang.GangDoors.onSneakClick(p, l, e.getPos(), entry)) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
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

            if (e.getHand() == InteractionHand.MAIN_HAND && tryUpgrade(p, l, e.getPos(), entry)) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }

            // OP : aperçu joueur d'abord (toutes portes enregistrées), le bouton « Admin »
            // du panneau ouvre ensuite l'administration. Vérifié avant le flux propriétaire normal.
            if (p.hasPermissions(2)) {
                sendAdminPreview(p, l, e.getPos(), entry);
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
            if (e.getHand() == InteractionHand.MAIN_HAND && LockpickManager.tryStart(p, l, e.getPos(), entry)) {
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            if (!canOpen(p, l, e.getPos(), entry)) {
                p.displayClientMessage(Component.translatable("message.minenorthsysteme.door_denied"), true);
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.FAIL);
            } else {
                if (e.getHand() == InteractionHand.MAIN_HAND) fr.minenorth.portes.agency.PropertyLog.opened(p, l, e.getPos(), entry);
                if (e.getHand() == InteractionHand.MAIN_HAND && l.getBlockState(e.getPos()).getBlock() instanceof DoorBlock door
                        && !door.type().canOpenByHand()) {
                    // Porte en fer : le clic vanilla ne l'ouvre pas, un joueur autorisé doit pouvoir l'ouvrir à la main.
                    toggleDoor(l, e.getPos());
                    e.setCanceled(true);
                    e.setCancellationResult(InteractionResult.SUCCESS);
                }
            }
        }
    }

    public static boolean canOpen(ServerPlayer player, ServerLevel level, BlockPos pos, OwnerDoorData.DoorEntry entry) {
        OwnerDoorData data = OwnerDoorData.get(level);
        return switch (entry.type()) {
            // Le locataire doit pouvoir ouvrir même si la maison n'a aucun propriétaire (maison gérée par les OP).
            case PERSONAL -> data.isRenter(level, pos, player.getUUID())
                    || (entry.owner() != null && data.isTrusted(entry.owner(), player.getUUID()))
                    // Le locataire peut autoriser des gens pendant sa location : ils ouvrent la porte comme lui.
                    || data.isTrusted(data.activeRenter(level, pos), player.getUUID())
                    // Perquisition acceptée par le Commissaire : la police ouvre les portes du citoyen.
                    || PoliceCompat.canSearch(player, entry.owner())
                    // Crochetage réussi : accès temporaire, sans droit durable.
                    || LockpickManager.hasTempAccess(player.getUUID(), new OwnerDoorData.DoorKey(level.dimension(), OwnerDoorData.normalize(level, pos)));
            case POLICE -> hasPermission(player, "grade.police") || PoliceCompat.isPolice(player);
            case POMPIER -> hasPermission(player, "grade.pompier") || SecoursCompat.isSecours(player);
            case MAIRIE -> hasPermission(player, "grade.mairie") || MairieCompat.isStaff(player);
            // Porte de gang : tous les membres du gang selon le grade minimum de la porte (recalculé à chaque fois).
            case GANG -> fr.minenorth.portes.gang.GangDoors.canOpen(player, entry.permission())
                    || LockpickManager.hasTempAccess(player.getUUID(), new OwnerDoorData.DoorKey(level.dimension(), OwnerDoorData.normalize(level, pos)));
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
        sendPanel(p, l, pos, 0, "", List.of(), List.of(), OwnerDoorData.DoorType.PERSONAL, "", false);
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
        sendPanel(p, l, pos, 1, ownerLabel(p, e), trusted, req, e.type(), e.permission(), e.temporary());
    }

    /** Vrai si le joueur gère les autorisations de cette porte : son propriétaire, ou son locataire pendant la location. */
    private static boolean canManageTrust(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        if (e == null || e.type() != OwnerDoorData.DoorType.PERSONAL) return false;
        return (e.owner() != null && e.owner().equals(p.getUUID())) || OwnerDoorData.get(l).isRenter(l, pos, p.getUUID());
    }

    /** Renvoie le panneau d'autorisations qui convient : celui du propriétaire, ou celui du locataire. */
    private static void sendTrustPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        if (e.owner() != null && e.owner().equals(p.getUUID())) sendOwnerPanel(p, l, pos, e);
        else sendRenterPanel(p, l, pos, e);
    }

    /** Panneau du locataire (clic accroupi pendant sa location) : il autorise ou retire des joueurs, rien d'autre. */
    private static void sendRenterPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        List<String> trusted = new ArrayList<>();
        for (UUID u : OwnerDoorData.get(l).trusted(p.getUUID())) trusted.add(MineNorth.displayName(p.server, u));
        sendPanel(p, l, pos, 6, ownerLabel(p, e), trusted, List.of(), e.type(), e.permission(), e.temporary());
    }

    private static void sendGroupOwnerPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        sendPanel(p, l, pos, 1, ownerLabel(p, e), List.of(), List.of(), e.type(), e.permission(), e.temporary());
    }

    /** Vrai pendant l'envoi d'un aperçu joueur à un OP : les panneaux partent avec le bouton « Admin ». */
    private static boolean previewing;

    private static void sendPanel(ServerPlayer p, ServerLevel l, BlockPos pos, int mode, String ownerName, List<String> trusted,
                                  List<String> requests, OwnerDoorData.DoorType type, String permission, boolean temporary) {
        ModNetwork.sendDoorPanel(p, l, pos, mode, ownerName, trusted, requests, type, permission, temporary, previewing);
    }

    /** Aperçu qu'un joueur aurait de cette porte (entreprise → annonce → propriétaire → demande), sinon panneau d'infos. */
    private static void sendAdminPreview(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry entry) {
        previewing = true;
        try {
            OwnerDoorData d = OwnerDoorData.get(l);
            // Locataire : il gère les personnes autorisées chez lui (au lieu de l'annonce « location déjà active »).
            if (canManageTrust(p, l, pos, entry) && !(entry.owner() != null && entry.owner().equals(p.getUUID()))) {
                sendRenterPanel(p, l, pos, entry);
                return;
            }
            OwnerDoorData.DoorListing listing = d.listing(l, pos);
            boolean ownerView = entry.owner() != null && entry.owner().equals(p.getUUID());
            if (entry.type() == OwnerDoorData.DoorType.ENTREPRISE && EntrepriseCompat.linked(entry.permission())
                    && EntrepriseCompat.isOwner(p, EntrepriseCompat.id(entry.permission()))) {
                sendGroupOwnerPanel(p, l, pos, entry);
            } else if (listing != null && listing.active() && !ownerView) {
                sendListingPanel(p, l, pos, entry);
            } else if (ownerView) {
                sendOwnerPanel(p, l, pos, entry);
            } else if (entry.owner() != null && !canOpen(p, l, pos, entry)
                    && (entry.type() == OwnerDoorData.DoorType.PERSONAL || entry.type() == OwnerDoorData.DoorType.ENTREPRISE
                    || entry.type() == OwnerDoorData.DoorType.ORGANISATION)) {
                sendRequestPanel(p, l, pos, entry);
            } else {
                CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
                CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
                sendPanel(p, l, pos, 5, ownerLabel(p, entry), List.of(), List.of(), entry.type(), entry.permission(), entry.temporary());
            }
        } finally {
            previewing = false;
        }
    }

    /** Ouvre le panneau d'administration de la porte {@code key} chez un OP, sans qu'il soit devant la porte (depuis l'agence). */
    public static void openAdminPanel(ServerPlayer p, OwnerDoorData.DoorKey key) {
        if (!p.hasPermissions(2)) return;
        ServerLevel l = p.server.getLevel(key.dimension());
        if (l == null) return;
        OwnerDoorData.DoorEntry e = OwnerDoorData.get(l).entry(key);
        if (e == null) return;
        CONTEXTS.put(p.getUUID(), key);
        CONTEXT_POS.put(p.getUUID(), key.pos());
        sendAdminPanel(p, l, key.pos(), e);
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
        sendPanel(p, l, pos, 4, ownerLabel(p, e), trusted, List.of(), e.type(), e.permission(), e.temporary());
    }

    private static void sendRequestPanel(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry e) {
        CONTEXTS.put(p.getUUID(), new OwnerDoorData.DoorKey(l.dimension(), OwnerDoorData.normalize(l, pos)));
        CONTEXT_POS.put(p.getUUID(), OwnerDoorData.normalize(l, pos));
        sendPanel(p, l, pos, 2, ownerLabel(p, e), List.of(), List.of(), e.type(), e.permission(), e.temporary());
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
        sendPanel(p, l, pos, 3, ownerLabel(p, e), List.of(), List.of(), e.type(), e.permission(), e.temporary());
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
        String house = (houseName == null || houseName.isBlank()) ? e.houseName() : houseName.trim();
        if (house == null || house.isBlank()) { owner.displayClientMessage(Component.literal("§cDonnez un nom de maison avant de mettre le bien en vente ou en location."), true); return; }
        d.setSale(l,pos,cents,house); SystemeConfigFiles.writeDoors(owner.server,d); ModNetwork.syncDoorLocks(owner.server);
        owner.displayClientMessage(Component.literal("§aPorte mise en vente pour " + String.format("%.2f", cents/100.0) + " €" + (house.isBlank()?"":" • Maison : "+house)), true);
    }

    public static void listRental(ServerPlayer owner, BlockPos pos, long cents, int days, String houseName) {
        ServerLevel l = owner.serverLevel(); OwnerDoorData d = OwnerDoorData.get(l); OwnerDoorData.DoorEntry e = d.get(l,pos);
        if (e == null || e.type() != OwnerDoorData.DoorType.PERSONAL || cents <= 0 || days <= 0 || !owner.hasPermissions(2)) return;
        String house = (houseName == null || houseName.isBlank()) ? e.houseName() : houseName.trim();
        if (house == null || house.isBlank()) { owner.displayClientMessage(Component.literal("§cDonnez un nom de maison avant de mettre le bien en vente ou en location."), true); return; }
        d.setRental(l,pos,cents,days,house); SystemeConfigFiles.writeDoors(owner.server,d); ModNetwork.syncDoorLocks(owner.server);
        owner.displayClientMessage(Component.literal("§aPorte proposée en location : " + String.format("%.2f", cents/100.0) + " € / " + days + " jour(s)" + (house.isBlank()?"":" • Maison : "+house)), true);
    }

    /** Nom affiché du propriétaire : nom RP (MineNorth API) pour une porte personnelle, sinon le nom enregistré. */
    private static String ownerLabel(ServerPlayer viewer, OwnerDoorData.DoorEntry e) {
        return e.owner() != null ? MineNorth.displayName(viewer.server, e.owner()) : e.ownerName();
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
            case 1 -> { if (!canManageTrust(player, level, pos, entry)) return; addTrusted(player, text); sendTrustPanel(player, level, pos, entry); }
            case 2 -> { if (!canManageTrust(player, level, pos, entry)) return; removeTrusted(player, text); sendTrustPanel(player, level, pos, entry); }
            case 3 -> { if (entry == null || !(entry.owner() == null ? player.hasPermissions(2) : entry.owner().equals(player.getUUID()))) return; unassign(player, pos); }
            case 4 -> request(player, pos);
            case 5, 6 -> { if (entry == null || entry.owner() == null || !entry.owner().equals(player.getUUID())) return; UUID u; try { u = UUID.fromString(extra); } catch (Exception ex) { return; } respond(player, u, action == 5); sendOwnerPanel(player, level, pos, entry); }
            case 7 -> { if (entry == null || (!player.hasPermissions(2) && (entry.owner() == null || !entry.owner().equals(player.getUUID())))) return; long cents=price(text); if(cents>0) listSale(player,pos,cents,extra); if(player.hasPermissions(2)) sendAdminPanel(player,level,pos,data.get(level,pos)); else sendOwnerPanel(player,level,pos,data.get(level,pos)); }
            case 8 -> { if (entry == null || (!player.hasPermissions(2) && (entry.owner() == null || !entry.owner().equals(player.getUUID())))) return; long cents=price(text); int days=0; String house=""; try { int sep=extra.indexOf('|'); if(sep>=0){days=Integer.parseInt(extra.substring(0,sep).trim()); house=extra.substring(sep+1).trim();} else days=Integer.parseInt(extra.trim()); } catch(Exception ex){} if(cents>0&&days>0) listRental(player,pos,cents,days,house); if(player.hasPermissions(2)) sendAdminPanel(player,level,pos,data.get(level,pos)); else sendOwnerPanel(player,level,pos,data.get(level,pos)); }
            case 15 -> {
                if (entry == null || (!player.hasPermissions(2) && (entry.owner() == null || !entry.owner().equals(player.getUUID())))) return;
                if (!player.hasPermissions(2) && WorldGuardBridge.isActive()
                        && !WorldGuardBridge.regionName(text == null ? "" : text.trim()).equals(WorldGuardBridge.regionName(entry.houseName() == null ? "" : entry.houseName().trim()))) {
                    player.displayClientMessage(Component.literal("§cSeul le staff peut renommer une maison : sa région WorldGuard porte ce nom."), true);
                    return;
                }
                data.setHouseName(level,pos,text);
                SystemeConfigFiles.writeDoors(player.server,data); ModNetwork.syncDoorLocks(player.server);
                OwnerDoorData.DoorEntry updated=data.get(level,pos);
                if (updated != null) { if (player.hasPermissions(2)) sendAdminPanel(player,level,pos,updated); else sendOwnerPanel(player,level,pos,updated); }
            }
            case 9 -> { if (entry == null || entry.owner() == null || !entry.owner().equals(player.getUUID())) return; data.clearHouseListings(key); SystemeConfigFiles.writeDoors(player.server,data); ModNetwork.syncDoorLocks(player.server); sendOwnerPanel(player,level,pos,entry); }
            // Achat, location et revente : uniquement a l'agence immobiliere.
            case 10, 11, 17 -> player.displayClientMessage(Component.literal("§cRendez-vous à l'agence immobilière pour acheter, louer ou revendre ce bien."), true);
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
            // Serrure : un OP règle n'importe quel niveau (0 = aucune), le propriétaire ne peut que la retirer (remboursée).
            case 21 -> {
                if (entry == null || entry.type() != OwnerDoorData.DoorType.PERSONAL) return;
                int lvl;
                try { lvl = Integer.parseInt(text.trim()); } catch (RuntimeException ex) { return; }
                lvl = Math.max(0, Math.min(OwnerDoorData.PICK_CHANCE.length - 1, lvl));
                boolean owner = entry.owner() != null && entry.owner().equals(player.getUUID());
                boolean op = player.hasPermissions(2);
                if (!op && !(owner && lvl == 0)) return;
                int old = OwnerDoorData.securityOf(entry);
                if (old == lvl) return;
                data.setSecurity(level, pos, lvl);
                if (owner && lvl == 0 && old > 0 && !player.getAbilities().instabuild) giveLock(player, old);
                SystemeConfigFiles.writeDoors(player.server, data);
                String[] names = {"aucune", "Normale", "Avancée", "Experte"};
                player.displayClientMessage(Component.literal(lvl == 0 ? "§aSerrure retirée." : "§aSerrure réglée : " + names[lvl] + "."), true);
                OwnerDoorData.DoorEntry updated = data.get(level, pos);
                if (updated != null) { if (op && !owner) sendAdminPanel(player, level, pos, updated); else sendOwnerPanel(player, level, pos, updated); }
            }
            // Fiche d'agence du bien (OP) : text = "CATEGORIE|meuble 0/1", extra = description.
            case 22 -> {
                if (!player.hasPermissions(2) || entry == null || entry.houseName().isBlank()) return;
                // text = "CATEGORIE|meuble 0/1|catalogue|entreprise bénéficiaire (id, 0 = aucune)", extra = description.
                String[] parts = text.split("\\|", 4);
                int payee = 0;
                try { if (parts.length > 3) payee = Integer.parseInt(parts[3].trim()); } catch (NumberFormatException ignored) {}
                if (payee > 0 && !Boolean.TRUE.equals(EntrepriseCompat.isActive(player.server, payee))) payee = 0;
                fr.minenorth.portes.agency.PropertyInfoData.get(level).set(key.dimension().location().toString(), entry.houseName(),
                        fr.minenorth.portes.agency.PropertyCategory.from(parts[0]), parts.length > 1 && parts[1].equals("1"), extra,
                        parts.length > 2 ? parts[2] : "", payee);
                player.displayClientMessage(Component.literal("§aFiche d'agence enregistrée."), true);
                sendAdminPanel(player, level, pos, entry);
            }
            // Efface les captures d'aperçu du bien (OP).
            case 23 -> {
                if (!player.hasPermissions(2) || entry == null || entry.houseName().isBlank()) return;
                fr.minenorth.portes.agency.PropertyImages.clear(player.server, fr.minenorth.portes.agency.PropertyInfoData.get(level), key.dimension().location().toString(), entry.houseName());
                player.displayClientMessage(Component.literal("§aCaptures effacées."), true);
                sendAdminPanel(player, level, pos, entry);
            }
            // Bouton « Admin » de l'aperçu joueur.
            case 20 -> {
                if (!player.hasPermissions(2) || entry == null) return;
                sendAdminPanel(player, level, pos, entry);
            }
            case 14 -> {
                if (!player.hasPermissions(2) || entry == null) return;
                data.clearHouseListings(key);
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
        d.expireRentals();
        boolean expired = d.takeExpired() | d.releaseOrphanCompanyHouses(e.getServer());
        if (expired) SystemeConfigFiles.writeDoors(e.getServer(), d);
        if (expired || e.getServer().getTickCount() % 40 == 0) ModNetwork.syncDoorLocks(e.getServer());
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            OwnerDoorData data = OwnerDoorData.get(p.serverLevel());
            CONTEXTS.remove(p.getUUID());
            fr.minenorth.portes.agency.PropertyImages.forget(p.getUUID());
            CONTEXT_POS.remove(p.getUUID());
            ModNetwork.forgetLocks(p.getUUID());
            LockpickManager.forget(p.getUUID());
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
