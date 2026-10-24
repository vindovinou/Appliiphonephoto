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
   - exposition un peu plus basse (Samsung surexpose) ;
   - verts moins saturés et tirés vers l'olive, ciels du cyan vers un bleu azur plus profond ;
   - peau protégée et recentrée sur une teinte chaude naturelle ;
   - hautes lumières désaturées et neutres, ombres calmes ;
   - courbe en S douce, noirs à peine relevés.
4. **Netteté douce, à petit rayon**, avec un seuil pour ne pas faire ressortir le bruit dans les
   ciels et sur la peau.

Le **viseur affiche le rendu en direct** : la même LUT 3D tourne sur le GPU, donc ce que tu vois
correspond à la photo finale (sans le Smart HDR, qui n'est appliqué qu'à la capture).

## Interface (façon iPhone)

- Boutons de zoom **.5 · 1× · 2 · 4 · 8** comme sur le 17 Pro Max. L'ultra grand-angle du S25
  descend à 0,6×, c'est lui qu'on obtient avec « .5 ».
- **Styles photographiques** : Standard, Contraste riche, Éclatant, Naturel, Ambre, Froid, N&B.
- Toucher pour faire la mise au point, puis glisser vers le haut ou le bas pour l'exposition (☀).
- Pincer pour zoomer, boutons de volume pour déclencher.
- Flash Auto / On / Off, Smart HDR on/off.
- Les photos sont enregistrées dans `DCIM/iLook` (JPEG qualité 95, EXIF conservés).

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
