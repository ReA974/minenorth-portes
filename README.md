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
