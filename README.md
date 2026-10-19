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

## Braquage de supérette et de station-service (19/10)
Un joueur **armé** (arme tenue en main principale : par défaut TACZ, liste modifiable) **accroupi + clic droit** sur un PNJ vendeur déclaré braquable lance un braquage : le vendeur se tourne vers lui, une barre de progression s'affiche, la police est alertée après un petit délai (tablette, carte, chat : même alerte que le piratage de radar), puis le butin est versé **en espèces** (billets du mod eurobank).
Échec si le braqueur s'éloigne, tombe, range son arme, ou si un policier arrive à moins de N blocs (caisse verrouillée). Le clic droit simple ouvre toujours la boutique.
- Admin : `/braquage magasin ajouter <nom> [superette|station]` (en visant le PNJ vendeur), `magasin supprimer|reinitialiser|liste`, `/braquage cooldown <min>`, `/braquage montant <min> <max>`, `/braquage policiers <n>`, `/braquage arme ajouter|retirer|liste`, `/braquage info`.
- Config : `config/minenorth_braquages.toml` (durée, distance, butin min/max, plafond par braquage et par heure serveur, braquages par joueur par heure, cooldowns, policiers minimum, délai d'alerte, annonce globale).
- Objets 3D (onglet MineNorth) : **Caisse enregistreuse** (à poser près du vendeur ; son tiroir s'ouvre quand elle est vidée, indestructible en survie) et **Sac de butin** (décoratif).
- État (magasins, cooldowns, registre des butins) dans le monde (SavedData) ; rien n'est confié au client. Package `fr.minenorth.portes.braquage`.
