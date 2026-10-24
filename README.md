# iLook Camera

Appli photo Android (pensée pour le **Galaxy S25 Ultra**) qui reproduit le rendu
photo d'un **iPhone 17 Pro Max**.

## Ce qu'elle fait

Samsung et Apple n'ont pas le même traitement d'image. Par rapport à un iPhone, le S25 Ultra sort
des photos plus lumineuses, plus froides et plus saturées (surtout les verts et le cyan des
ciels), avec une accentuation très marquée. L'appli récupère l'image du capteur avec le moins de
traitement Samsung possible, puis applique son propre pipeline « à la Apple » :

1. **Accentuation Samsung désactivée** (`EDGE_MODE_OFF`) et réduction de bruit en haute qualité
   au moment de la capture.
2. **Smart HDR** : tone mapping local qui remonte les ombres et retient les hautes lumières en
   gardant le contraste local (comme le Smart HDR / Photonic Engine).
3. **Science des couleurs iPhone** (dans l'espace Oklab) :
   - balance des blancs légèrement plus chaude ;
   - exposition lumineuse et aérée ;
   - verts riches tirés vers le jaune, ciels du cyan vers un bleu azur plus profond ;
   - peau protégée et recentrée sur une teinte chaude naturelle ;
   - hautes lumières désaturées et neutres, ombres calmes ;
   - blancs tenus juste sous la saturation (pas de blancs brûlés) ;
   - courbe en S douce, ombres relevées.
4. **Netteté douce, à petit rayon**, avec un seuil pour ne pas faire ressortir le bruit dans les
   ciels et sur la peau.

Le **viseur affiche le rendu en direct** : la même LUT 3D tourne sur le GPU, donc ce que tu vois
correspond à la photo finale (sans le Smart HDR, qui n'est appliqué qu'à la capture).

## Interface (copie de l'appareil photo iOS 26 du 17 Pro Max)

- Modes **PHOTO** et **VIDÉO** en bas ; balayer l'image à gauche/droite pour changer de mode.
- **Flèche ⌃** en haut : ouvre le volet de réglages à la place des modes :
  Flash (Auto/On/Off), Format (**4:3 · 16:9 · 1:1**), Retardateur (Off/3 s/10 s),
  Exposition (curseur), Styles photographiques, Grille.
- **QuickTake** : appui long sur le déclencheur en mode Photo pour filmer, relâcher pour arrêter.
- Vidéo 1080p avec le même rendu de couleurs que les photos, chrono rouge pendant
  l'enregistrement, lampe en vidéo.
- Boutons de zoom **.5 · 1× · 2 · 4 · 8**. L'ultra grand-angle du S25 descend à 0,6×, c'est lui
  qu'on obtient avec « .5 ».
- Les icônes pivotent avec le téléphone et les photos prises en paysage sont dans le bon sens.
- Toucher pour la mise au point puis glisser pour l'exposition (☀), pincer pour zoomer, boutons
  de volume pour déclencher, son et vibration du déclencheur.
- Photos et vidéos dans `DCIM/iLook` (JPEG qualité 95, EXIF conservés).

Pas reproduits, car ils dépendent du matériel ou du logiciel d'Apple : Cinématique, Ralenti,
Spatial, Panorama, mode Nuit, Live Photo, Portrait.

## Installer

Télécharge l'APK (Actions GitHub → dernier build → artefact `iLookCamera-apk`), ouvre-le sur le
téléphone et autorise l'installation depuis cette source.

Pour compiler toi-même : `./gradlew assembleRelease` → `app/build/outputs/apk/release/`.

## Limites

Un rendu **strictement identique** est impossible : le capteur, l'optique, le traitement
multi-images et le Deep Fusion de l'iPhone sont propres à Apple. Cette appli imite la *signature
colorimétrique et tonale* de l'iPhone. Pour l'affiner, le mieux est de prendre la même scène avec
les deux téléphones et d'ajuster les paramètres de `Styles` dans
`app/src/main/java/com/vindovinou/ilook/ColorScience.kt`.
