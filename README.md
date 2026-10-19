# MineNorth Portes

Mod Forge 1.20.1 pour MineNorth RP.

Version: 1.0.0

Ce mod est désormais séparé du reste de MineNorth Systeme.

## Licence

**Tous droits réservés - MineNorthRP.** Réutilisation, copie, modification, décompilation / ingénierie
inverse (y compris par outils d'intelligence artificielle) et utilisation pour entraîner une IA sont
**interdites** sans autorisation écrite. Voir [LICENSE](LICENSE).

## Lien WorldGuard (13/10)
Chaque **maison** (les portes qui portent le même nom de maison) correspond à une **région WorldGuard** du même nom. Le propriétaire et le locataire en cours en sont **membres** (ils posent et cassent des blocs) ;
quand la maison est vendue, remise en vente, que le bail se termine ou que le locataire est expulsé, le pont retire l'ancien et ajoute le nouveau, par les commandes WorldGuard lancées à la console.
- Le staff crée la région une fois : `/rg define maison_12` (nom de la maison en minuscules, sans espaces ni accents : « Maison 12 » → `maison_12`).
- Réglages : `config/Minenorth-portes/worldguard.json` (`actif`, `intervalleSecondes`, commandes `region addmember` / `removemember`, `prefixeRegion`, `mondeOverworld`).
- `/portewg resync` (op) renvoie tout à WorldGuard (région créée après coup, ou supprimée puis recréée).
- Seules les portes personnelles non temporaires qui ont un nom de maison sont concernées.

## Gangs (feat/gangs)
Un **gang** = un groupe de joueurs (chef, lieutenants, membres) avec un nom, un **tag** de 2 à 4 caractères et une couleur. L'identité des membres est toujours l'**UUID**. Sauvegarde : `minenorthportes_gangs` (monde principal). Réglages : `config/Minenorth-portes/minenorth_gangs.toml` (créé au premier lancement, `/gang admin recharger`).
- **Interface** (plus de commandes) : écran « Mon gang » ouvert par la touche **G** (réglable dans les contrôles) ou par clic droit sur l'objet 3D **Carnet de gang**. Onglets Membres (inviter les joueurs proches, grades, exclure), Bases (portes, coffre, journal du chef), Territoires (carte des zones, revenu), Paramètres (nom, tag, couleur, quitter, dissoudre avec double confirmation), Invitations, et Admin pour les OP (créer/libérer/supprimer un territoire, dissoudre un gang). Une seule commande de secours : `/gang recharger` (OP, relit minenorth_gangs.toml).
- **Création** : coût réglable (`creation_cout_centimes`, payé par carte via BankService), joueurs connectés minimum optionnel, attente après un départ.
- **Portes de gang** : type de porte `GANG`, permission `gang:<uuid du gang>:<grade minimum>`. Le droit d'ouvrir est recalculé à chaque fois : un membre exclu perd l'accès immédiatement. Pour attribuer une porte : clic droit accroupi dessus (fenêtre « porte » : grade minimum), ou bouton « Porte de gang » du panneau d'une porte de maison à soi. Une porte libre doit se trouver près d'un **Coffre de gang** du gang (`base_rayon`) ; le joueur peut aussi apporter sa propre porte de maison. Les portes de gang peuvent être crochetées (réglable) et le gang est alors prévenu.
- **Coffre de gang** (bloc 3D) : posé par le chef/lieutenant, il ouvre le **coffre commun** du gang (54 cases, un seul conteneur partagé : pas de duplication), réservé aux membres. Chaque entrée/sortie est écrite au **journal** (UUID du joueur), lisible par le chef. Un gang dissous doit avoir vidé son coffre.
- **Territoires** : zones cuboïdes définies par un OP. Un gang qui reste seul dans la zone avec assez de membres (`capture_min_membres`) pendant `capture_secondes` la prend ; barre de progression pour les joueurs présents, annonce à la prise, gel si un autre gang est là, protection après une prise. Revenu passif versé au compte du chef (BankService, plafonné par heure). Repères sur la carte (mod Carte, par réflexion) à la couleur du gang. Bloc « Pancarte de territoire » (pose réservée aux OP) pour afficher l'état de la zone.
- **API** (réflexion) : `fr.minenorth.portes.api.GangApi` (`gangOf`, `sameGang`, `gangs`), pour la tablette police ou d'autres mods.
- **Protocole réseau passé de 3 à 4** (2 paquets ajoutés : état du gang, action) : serveur et clients doivent avoir le même jar. Les anciennes sauvegardes de portes se chargent sans changement.
