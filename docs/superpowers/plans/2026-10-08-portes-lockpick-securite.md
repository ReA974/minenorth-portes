# Portes : lockpick, serrures, onglet, aperçu admin — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter l'ouverture fiable des portes pompier, le crochetage des portes de maison, trois niveaux de serrure, un onglet créatif et l'aperçu joueur pour les OP.

**Architecture:** Le niveau de sécurité devient un champ de `DoorEntry` (persisté). Un `LockpickManager` serveur gère sessions, éligibilité et tirage ; le client n'affiche que `LockpickScreen` et renvoie « gagné/perdu ». Les items sont enregistrés via `DeferredRegister`. L'aperçu admin réutilise le flux non-OP existant avec un drapeau `adminPreview` dans `DoorPanelPacket`.

**Tech Stack:** Java 17, Forge 1.20.1 (47.4.10), SimpleChannel, Gradle (ForgeGradle 6).

**Spec:** `docs/superpowers/specs/2026-10-08-portes-lockpick-securite-design.md`

Code dans `src/main/java/fr/minenorth/drill/` (package Java `fr.minenorth.portes`). Pas de suite de tests dans le dépôt : chaque tâche se vérifie par `./gradlew build` (succès attendu : `BUILD SUCCESSFUL`) puis par le test en jeu indiqué.

## Global Constraints

- Niveaux : 0 = Normal 75 %, 1 = Avancé 35 %, 2 = Expert 5 % (réussite du crochetage).
- Portes crochetables : `PERSONAL` uniquement. Jamais ENTREPRISE, ORGANISATION, POLICE, POMPIER.
- Lockpick : durabilité 5, 1 usage par tentative ; session 60 s ; autorisation temporaire 30 s ; distance ≤ 6 blocs ; mini-jeu 3 goupilles.
- Serrures : pile 16 ; posées par le propriétaire en sneak + clic droit, sur la porte cliquée seulement, consommées.
- Achat/revente d'une maison remet la sécurité à Normal.
- Onglet créatif : « MineNorth Portes ». `PROTOCOL` réseau : "2".
- Textes en français.

## Review Focus

- Porte POMPIER ouverte juste après la nomination d'un joueur (client pas encore resynchronisé) → elle doit s'ouvrir.
- Résultat de lockpick envoyé sans session, expiré ou trop loin → ignoré, sans usage consommé ni ouverture.
- Lockpick sur porte non crochetable ou déjà autorisée → refus avec message, aucune session.
- Rechargement du serveur → le niveau de serrure est conservé (NBT et `porte.toml`).
- Joueur non OP ou paquet d'action « Admin » forgé → aucun panneau admin.

---

### Task 1: Ouverture fiable des portes pompier

**Files:**
- Modify: `door/OwnerDoorManager.java` (`handleClientInteract`, `onServerTick`)

**Interfaces:**
- Produces: `static void toggleDoor(ServerLevel l, BlockPos pos)` (privé, ouvre/ferme la porte sans l'événement).

- [ ] **Step 1:** Dans `handleClientInteract`, branche non-sneak : si `canOpen(p, l, pos, entry)`, appeler `toggleDoor`. Implémenter `toggleDoor` avec `DoorBlock.setOpen(null, l, state, normalizedPos, !state.getValue(DoorBlock.OPEN))` sur la moitié basse.
- [ ] **Step 2:** Dans `onServerTick`, remplacer le modulo `% 200` par `% 40`.
- [ ] **Step 3:** `./gradlew build` → BUILD SUCCESSFUL.
- [ ] **Step 4:** En jeu : nommer un joueur pompier via `/mnadmin`, il clique tout de suite une porte POMPIER → elle s'ouvre.
- [ ] **Step 5:** Commit `fix: porte pompier ouvre sans attendre la synchro`.

### Task 2: Niveau de sécurité persisté

**Files:**
- Modify: `door/OwnerDoorData.java`, `config/SystemeConfigFiles.java`

**Interfaces:**
- Produces: `DoorEntry` avec composant `int security` en dernière position (les constructeurs existants à 5, 6 et 7 arguments restent et donnent `security = 0`) ; `OwnerDoorData.setSecurity(ServerLevel, BlockPos, int level)` ; `OwnerDoorData.securityOf(DoorEntry) -> int` (borné 0..2) ; `static final int[] PICK_CHANCE = {75, 35, 5}`.

- [ ] **Step 1:** Ajouter `security` au record `DoorEntry` et propager la valeur dans tous les `new DoorEntry(...)` de `OwnerDoorData` (`assign`, `setHouseName`) pour la conserver ; `transferHouse` et `returnHouseToMarket` la remettent à 0.
- [ ] **Step 2:** Sauvegarder/charger la clé NBT `Security` (`putInt` / `getInt`).
- [ ] **Step 3:** `SystemeConfigFiles.writeDoors` écrit `securite = <n>` ; la lecture de `porte.toml` (reloadDoors) la reprend, défaut 0.
- [ ] **Step 4:** `./gradlew build` → BUILD SUCCESSFUL.
- [ ] **Step 5:** Commit `feat: niveau de securite des portes`.

### Task 3: Items, onglet créatif, ressources

**Files:**
- Create: `item/ModItems.java`, `item/ModCreativeTabs.java`, `item/LockpickItem.java`, `item/LockUpgradeItem.java`
- Create: `src/main/resources/assets/minenorthportes/models/item/{lockpick,serrure_normal,serrure_avancee,serrure_expert}.json`, `textures/item/*.png` (16x16), `lang/fr_fr.json`, `lang/en_us.json`
- Modify: `MineNorthPortes.java` (enregistrer les deux registres sur le bus du mod)

**Interfaces:**
- Produces: `ModItems.LOCKPICK`, `ModItems.LOCK_NORMAL`, `ModItems.LOCK_ADVANCED`, `ModItems.LOCK_EXPERT` (`RegistryObject<Item>`) ; `LockUpgradeItem(int level, Properties)` avec `int level()` ; `LockpickItem` (durabilité 5, pile 1).

- [ ] **Step 1:** Créer les items : `lockpick` (`stacksTo(1).durability(5)`), les 3 serrures (`stacksTo(16)`, classe `LockUpgradeItem` niveaux 0, 1, 2).
- [ ] **Step 2:** `ModCreativeTabs.TAB` : onglet « MineNorth Portes » (icône lockpick) contenant les 4 items. Passer `FMLJavaModLoadingContext.get().getModEventBus()` aux deux registres depuis le constructeur de `MineNorthPortes`.
- [ ] **Step 3:** Modèles `item/generated`, textures générées par un script Python (PIL) placé dans le scratchpad, noms de langue : « Crochet », « Serrure renforcée (Normal) », « (Avancée) », « (Experte) ».
- [ ] **Step 4:** `./gradlew build` ; en jeu : l'onglet existe et contient les 4 items avec textures.
- [ ] **Step 5:** Commit `feat: items lockpick et serrures + onglet creatif`.

### Task 4: Pose d'une serrure renforcée

**Files:**
- Modify: `door/OwnerDoorManager.java` (`interact`, `handleClientInteract`)

**Interfaces:**
- Consumes: Task 2 (`setSecurity`), Task 3 (`LockUpgradeItem.level()`).
- Produces: `private static boolean tryUpgrade(ServerPlayer p, ServerLevel l, BlockPos pos, OwnerDoorData.DoorEntry entry)` — vrai si l'événement a été traité.

- [ ] **Step 1:** `tryUpgrade` : item principal de type `LockUpgradeItem` ; refus avec message actionbar si porte non `PERSONAL` ou si `entry.owner()` n'est pas le joueur ; sinon `setSecurity`, `shrink(1)` (hors créatif), `writeDoors`, message « Serrure niveau X installée ».
- [ ] **Step 2:** L'appeler en début de la branche sneak de `interact` et de `handleClientInteract`, avant le panneau OP ; annuler l'événement quand `tryUpgrade` est vrai.
- [ ] **Step 3:** `./gradlew build` ; en jeu : propriétaire pose Avancé puis Expert (niveau remplacé, item consommé) ; un autre joueur est refusé ; redémarrage → niveau conservé.
- [ ] **Step 4:** Commit `feat: pose des serrures renforcees`.

### Task 5: Lockpick côté serveur

**Files:**
- Create: `door/LockpickManager.java`
- Modify: `network/ModNetwork.java` (paquets `LockpickStartPacket(long pos, int security)` serveur→client, `LockpickResultPacket(boolean won)` client→serveur, PROTOCOL "2"), `door/OwnerDoorManager.java` (`canOpen`, `interact`, `handleClientInteract`), `client/ClientNetworkHandler.java`

**Interfaces:**
- Consumes: Task 2 (`PICK_CHANCE`, `securityOf`), Task 3 (`ModItems.LOCKPICK`).
- Produces: `LockpickManager.canPick(ServerPlayer, ServerLevel, BlockPos, DoorEntry) -> boolean` ; `LockpickManager.start(ServerPlayer, ServerLevel, BlockPos)` (envoie le paquet et ouvre la session) ; `LockpickManager.finish(ServerPlayer, boolean won)` ; `LockpickManager.hasTempAccess(UUID, DoorKey) -> boolean`.
- Consumes (Task 6): `ClientNetworkHandler.openLockpick(long pos, int security)`.

- [ ] **Step 1:** `canPick` : type `PERSONAL`, `!canOpen`, joueur à ≤ 6 blocs. `start` : session `(player UUID → DoorKey, expiry now+60 s)` et paquet.
- [ ] **Step 2:** `finish` : retrouver et supprimer la session (absente ou expirée → return) ; revérifier distance et `canPick` ; `hurtAndBreak` 1 sur le lockpick en main ; `won && random.nextInt(100) < PICK_CHANCE[security]` → autorisation temporaire 30 s (`Map<UUID, Map<DoorKey, Long>>`), `toggleDoor` (Task 1), `syncDoorLocks` ; sinon message « La serrure résiste ».
- [ ] **Step 3:** `canOpen` (cas `PERSONAL`) : ajouter `|| LockpickManager.hasTempAccess(player.getUUID(), key)`.
- [ ] **Step 4:** Dans `interact` / `handleClientInteract` (non sneak, lockpick en main, porte enregistrée) : si `canPick` → `start`, sinon message de refus (« Cette porte ne peut pas être crochetée »), annuler l'événement.
- [ ] **Step 5:** `./gradlew build` → BUILD SUCCESSFUL.
- [ ] **Step 6:** Commit `feat: logique serveur du lockpick`.

### Task 6: Mini-jeu `LockpickScreen`

**Files:**
- Create: `client/LockpickScreen.java`
- Modify: `client/ClientNetworkHandler.java`

**Interfaces:**
- Consumes: `ModNetwork.CHANNEL`, `LockpickResultPacket(boolean won)`.
- Produces: `LockpickScreen(long pos, int security)` ; `ClientNetworkHandler.openLockpick(long, int)`.

- [ ] **Step 1:** `LockpickScreen extends Screen`, style `MineNorthStyle`. 3 goupilles à la suite : un curseur oscille (sinusoïde) sur une barre ; clic ou Espace l'arrête ; dans la zone verte → goupille suivante, sinon échec. Zone/vitesse par niveau : Normal 28 px / 1,0 ; Avancé 20 px / 1,4 ; Expert 12 px / 1,9.
- [ ] **Step 2:** À la fin (3 réussies ou 1 échec) : envoyer `LockpickResultPacket(won)` et fermer. Échap ferme sans résultat (la session expire côté serveur).
- [ ] **Step 3:** `./gradlew build` ; en jeu : lockpick sur porte de maison d'un autre → écran ; réussite aux taux attendus ; refus sur portes POLICE/POMPIER/ENTREPRISE/ORGANISATION ; résultat forgé sans session ignoré (log serveur sans effet).
- [ ] **Step 4:** Commit `feat: mini-jeu de crochetage`.

### Task 7: Aperçu joueur pour les OP + bouton Admin

**Files:**
- Modify: `door/OwnerDoorManager.java` (`interact`, `handleClientInteract`, `handleAction`), `network/ModNetwork.java` (`DoorPanelPacket.adminPreview`, `sendDoorPanel`), `client/DoorPanelScreen.java`

**Interfaces:**
- Produces: `DoorPanelPacket` avec champ final `boolean adminPreview` (écrit/lu en dernier) ; `sendDoorPanel(..., boolean adminPreview)` ; action `ACTION_OPEN_ADMIN = 20` dans `handleAction` ; panneau mode 5 « info » (type, propriétaire, niveau de sécurité).

- [ ] **Step 1:** Extraire le flux non-OP actuel (entreprise → annonce → propriétaire → demande) en `private static boolean sendPlayerView(ServerPlayer, ServerLevel, BlockPos, DoorEntry, boolean preview)` qui renvoie vrai si un panneau est parti. Pour un OP sur porte enregistrée : `sendPlayerView(..., true)`, sinon panneau info mode 5 (`preview = true`). Les panneaux envoyés par `sendOwnerPanel` etc. reçoivent le drapeau.
- [ ] **Step 2:** `handleAction` case `20` : `if (!player.hasPermissions(2)) return;` puis `sendAdminPanel` sur le contexte courant. Porte non enregistrée : `openAssignment` inchangé.
- [ ] **Step 3:** `DoorPanelScreen` : si `data.adminPreview()`, bouton « Admin » (en bas du panneau, `MineNorthButton`) qui envoie l'action 20 ; gérer le mode 5 (lecture seule) dans `init`.
- [ ] **Step 4:** `./gradlew build` ; en jeu : OP sneak-clique une porte de maison réglée → panneau joueur + bouton Admin → panneau admin ; porte POLICE → panneau info + bouton ; porte non réglée → attribution directe ; joueur non OP : aucun bouton, action 20 forgée sans effet.
- [ ] **Step 5:** Commit `feat: apercu joueur et bouton admin pour les OP`.

### Task 8: Vérification finale

- [ ] **Step 1:** `./gradlew build` propre depuis zéro (`./gradlew clean build`).
- [ ] **Step 2:** Parcourir la liste « Vérification » du spec en jeu (serveur dédié) et noter tout écart.
- [ ] **Step 3:** Mettre à jour `README.md` (items, onglet, règles de crochetage) et commit `docs: readme portes lockpick`.
