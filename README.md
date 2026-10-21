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
- **Création** : coût réglable (`creation_prix_euros`, en euros, payé par carte via BankService), joueurs connectés minimum optionnel, attente après un départ.
- **Portes de gang** : type de porte `GANG`, permission `gang:<uuid du gang>:<grade minimum>`. Le droit d'ouvrir est recalculé à chaque fois : un membre exclu perd l'accès immédiatement. Pour attribuer une porte : clic droit accroupi dessus (fenêtre « porte » : grade minimum), ou bouton « Porte de gang » du panneau d'une porte de maison à soi. Une porte libre doit se trouver près d'un **Coffre de gang** du gang (`base_rayon`) ; le joueur peut aussi apporter sa propre porte de maison. Les portes de gang peuvent être crochetées (réglable) et le gang est alors prévenu.
- **Coffre de gang** (bloc 3D) : posé par le chef/lieutenant, il ouvre le **coffre commun** du gang (54 cases, un seul conteneur partagé : pas de duplication), réservé aux membres. Chaque entrée/sortie est écrite au **journal** (UUID du joueur), lisible par le chef. Un gang dissous doit avoir vidé son coffre.
- **Territoires** : zones cuboïdes définies par un OP. Un gang qui reste seul dans la zone avec assez de membres (`capture_min_membres`) pendant `capture_secondes` la prend ; barre de progression pour les joueurs présents, annonce à la prise, gel si un autre gang est là, protection après une prise. Revenu passif versé au compte du chef (BankService, plafonné par heure). **Réglable en jeu** (OP) : écran « Mon gang » > Admin > Revenus : revenu de CHAQUE zone (0 = zone sans revenu, vide/défaut = valeur par défaut), et valeurs par défaut (revenu par zone, plafond par heure, intervalle en minutes, versement seulement si un membre est en ligne). Valeurs bornées côté serveur (zone : 0 à 10 000 €), enregistrées avec le territoire (sauvegarde du monde) et les défauts dans `minenorth_gangs.toml` ; appliquées au prochain versement. Un territoire sans valeur propre (anciennes sauvegardes) utilise le défaut. Repères sur la carte (mod Carte, par réflexion) à la couleur du gang. Bloc « Pancarte de territoire » (pose réservée aux OP) pour afficher l'état de la zone.
- **API** (réflexion) : `fr.minenorth.portes.api.GangApi` (`gangOf`, `sameGang`, `gangs`), pour la tablette police ou d'autres mods.
- **Protocole réseau passé de 3 à 5** (4 : 2 paquets ajoutés, état du gang et action ; 5 : revenu par zone dans l'état) : serveur et clients doivent avoir le même jar. Les anciennes sauvegardes de portes se chargent sans changement.

## Gestion des propriétés par le staff (panneau MineNorth Admin)
- **API** (réflexion) : `fr.minenorth.portes.api.PropertyAdminApi` (`list`, `one`, `trusted`, `log`, `act`) ; logique dans `agency/PropertyAdmin`. Aucun contrôle de droit dans l'API : c'est le panneau admin qui vérifie le staff (rôle `PROPRIETES_*`) avant d'appeler.
- Actions : attribuer le propriétaire (prix d'achat 0 : pas de revente remboursée), installer un locataire, ajouter / retirer un colocataire, mettre en vente / en location, changer le prix de la fiche, retirer la fiche, changer la catégorie, expulser l'occupant (sans remboursement), libérer le bien (portes sans propriétaire, fiche et serrures remises à zéro, droits WorldGuard retirés tout de suite).
- **Journal des propriétés** : nouvelle sauvegarde `minenorthportes_property_log` (100 derniers événements par bien : achat, revente, location, prolongation, arrêt, actions du staff, ouverture d'une porte par quelqu'un qui n'est ni propriétaire ni locataire, une ligne par joueur toutes les 5 min). Les anciennes sauvegardes de portes se chargent sans changement.
- Les colocataires sont les joueurs « de confiance » du titulaire (valables pour tous ses biens), comme dans le menu de la porte.
