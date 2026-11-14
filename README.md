# Diesel Moins Cher (Android)

Affiche les stations au diesel (gazole) le moins cher dans un rayon de 150 km autour de vous.
Les prix viennent des données officielles du ministère de l'Économie (prix-carburants.gouv.fr),
mises à jour toutes les 10 minutes. Pas de clé API, pas de compte.

- En haut : le meilleur prix, l'adresse et un bouton « Y aller » (ouvre Maps / Waze).
- En dessous : les 50 stations les moins chères, avec la distance et l'heure de mise à jour.
- Les prix vieux de plus de 7 jours sont ignorés.

## Obtenir l'APK

### Option A : GitHub (sans rien installer)
1. Créez un dépôt GitHub et envoyez-y ce dossier.
2. Onglet **Actions** → « Compiler l'APK » se lance tout seul (~5 min).
3. Téléchargez l'artefact `DieselMoinsCher-apk`, dézippez-le, copiez `app-debug.apk` sur le téléphone
   et ouvrez-le (autorisez « Installer des applis inconnues »).

### Option B : Android Studio
Ouvrez ce dossier dans Android Studio, puis **Build → Build APK(s)**, ou branchez votre téléphone et appuyez sur ▶.

## Réglages
Dans `MainActivity.kt` : `RAYON_KM`, `MAX_RESULTATS`, `PRIX_RECENTS_JOURS`.
