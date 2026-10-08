# Portes : grade pompier, lockpick, serrures renforcées, onglet, aperçu admin

Date : 2026-10-08 — Branche : `feat/pompier` — Mod : minenorth-portes (Forge 1.20.1)

## Objectif
Six ajouts au système de portes :
1. Tous les membres des secours (grade pompier) ouvrent les portes POMPIER.
2. Les portes de maison (`PERSONAL`) peuvent être crochetées ; ENTREPRISE, ORGANISATION, POLICE, POMPIER ne le peuvent jamais.
3. Item `lockpick` + mini-jeu de serrure (écran client).
4. Renfort de serrure en 3 niveaux (Normal 75 %, Avancé 35 %, Expert 5 % de réussite du crochetage), posé par le propriétaire en sneak + clic droit avec l'item.
5. Onglet créatif « MineNorth Portes » regroupant les items.
6. Un OP qui fait sneak + clic droit sur une porte déjà réglée voit d'abord l'aperçu joueur, avec un bouton « Admin » en bas qui ouvre le panneau admin actuel.

## 1. Grade pompier
`SecoursApi.isSecours` (réflexion, `SecoursCompat`) renvoie déjà vrai pour tout membre des secours, tous grades confondus. Le défaut est ailleurs :
- `DoorLockClientHandler` annule le clic droit des portes que le serveur a listées comme verrouillées pour ce joueur, et la liste n'est recalculée que toutes les 200 ticks. Un joueur fraîchement nommé pompier a donc une porte « verrouillée » côté client.
- `handleClientInteract` (non sneak) n'ouvre rien quand `canOpen` est vrai.

Correctifs :
- `handleClientInteract` : si `canOpen`, basculer la porte côté serveur (moitié basse, `DoorBlock.setOpen`).
- `OwnerDoorManager.onServerTick` : resynchroniser toutes les 40 ticks (au lieu de 200). `syncDoorLocks` n'envoie déjà que si la liste a changé.

## 2. Données : niveau de sécurité
- `DoorEntry` gagne `int security` (0 = Normal, 1 = Avancé, 2 = Expert), 0 par défaut. Les constructeurs existants restent valides.
- Sauvegarde NBT (`Security`) dans `OwnerDoorData`, et `securite` dans `porte.toml` (`SystemeConfigFiles.writeDoors` et lecture).
- `assign`, `setHouseName`, `transferHouse`, `returnHouseToMarket` conservent ou réinitialisent le niveau de façon cohérente : le transfert de maison (achat/revente) remet Normal.
- Pourcentages : `{75, 35, 5}` indexés par `security`.

## 3. Items et onglet
- Nouveau paquet `fr.minenorth.portes.item` : `ModItems` (`DeferredRegister<Item>`), `ModCreativeTabs` (`DeferredRegister<CreativeModeTab>`), branchés sur le bus de `MineNorthPortes`.
- Items : `lockpick` (durabilité 5, pile 1), `serrure_normal`, `serrure_avancee`, `serrure_expert` (pile 16).
- Les 4 items sont dans l'onglet créatif « MineNorth Portes ».
- Ressources : `models/item/*.json` (`item/generated`), `textures/item/*.png` 16x16 générées par script, `lang/fr_fr.json` et `en_us.json`.
- Pas de recette pour l'instant.

## 4. Renfort de serrure
Dans `OwnerDoorManager.interact` (sneak + clic droit), avant tout autre flux : si l'item en main est une serrure et que la porte est `PERSONAL` avec `entry.owner() == p.getUUID()` :
- appliquer le niveau, consommer 1 item, message actionbar, sauvegarder (`writeDoors`), annuler l'événement ;
- sinon message d'erreur (« seul le propriétaire… » / « cette porte ne peut pas être renforcée »).
Le niveau s'applique à la porte cliquée uniquement.

## 5. Lockpick
Éligibilité (`LockpickManager.canPick`) : porte enregistrée, type `PERSONAL`, joueur pas déjà autorisé (`!canOpen`), distance ≤ 6 blocs.

Flux :
1. Clic droit (non sneak) avec le lockpick sur une porte verrouillée. Le client intercepte déjà ces clics (`DoorLockClientHandler` → `DoorInteractPacket`) ; `handleClientInteract` et `interact` détectent l'item.
2. Serveur → client : `LockpickStartPacket(pos, security)`, et création d'une session `(joueur, clé de porte, expiration 60 s)`.
3. Client : `LockpickScreen`, 3 goupilles. Un curseur oscille ; le joueur clique pour l'arrêter dans la zone verte. Largeur de zone et vitesse selon le niveau (Normal large/lent, Expert étroit/rapide). Une goupille manquée = échec.
4. Client → serveur : `LockpickResultPacket(won)`.
5. Serveur : session valide, joueur à ≤ 6 blocs ? Sinon on ignore. La session est consommée dans tous les cas.
   - mini-jeu perdu → échec ;
   - mini-jeu gagné → tirage `random < pourcentage(security)` ;
   - tentative = 1 usage de durabilité du lockpick, quel que soit le résultat.
6. Réussite : la porte est ouverte et une autorisation temporaire (30 s, en mémoire, par joueur et porte) est ajoutée dans `canOpen` pour `PERSONAL`. Le joueur n'est pas ajouté aux personnes de confiance. Les clients doivent voir la porte déverrouillée : `syncDoorLocks` pour ce joueur.

Sécurité : le résultat du mini-jeu est fourni par le client (non vérifiable) ; le tirage au sort, l'éligibilité, la distance et la durée sont côté serveur.

## 6. Aperçu admin
- `sendXPanel` : le paquet `DoorPanelPacket` gagne `boolean adminPreview`.
- `OwnerDoorManager` : un OP qui sneak-clique une porte enregistrée reçoit le panneau que le flux non-OP aurait choisi (annonce, demande d'accès, panneau propriétaire/patron), avec `adminPreview = true`. Si ce flux ne produit rien (POLICE/POMPIER, ou entreprise sans accès), panneau d'info (mode 5) : type, propriétaire, niveau de sécurité.
- `DoorPanelScreen` : si `adminPreview`, bouton « Admin » en bas ; il envoie une `DoorActionPacket` d'un nouveau code d'action qui rappelle `sendAdminPanel` (OP vérifié côté serveur).
- Porte non enregistrée : inchangé (écran d'attribution direct).
- `PROTOCOL` passe de "1" à "2".

## Hors périmètre
Recettes de craft, alertes police au crochetage, renfort sur une maison entière, autres types de porte crochetables.

## Vérification
Pas de suite de tests dans le dépôt. La vérification est : `./gradlew build` OK, puis test en jeu (`runClient` / serveur dédié) :
- pompier nommé ouvre une porte POMPIER sans attendre ;
- lockpick refusé sur ENTREPRISE/POLICE/POMPIER/ORGANISATION ;
- les 3 niveaux de serrure se posent, persistent après redémarrage, et reviennent à Normal à l'achat ;
- l'OP voit l'aperçu puis le panneau admin via le bouton.
