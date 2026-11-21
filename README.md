<p align="center"><img src="docs/icon.png" width="160" alt="Fluxo"></p>

# Fluxo

Application Android tout-en-un créée par **SuperEssence Studio**.

## Télécharger l'APK

Va dans l'onglet **Releases** du dépôt et télécharge `Fluxo.apk` (dernière version).
L'APK est compilé automatiquement par GitHub Actions à chaque push (onglet **Actions**,
artefact `Fluxo-apk`). Android 8.0 ou plus est requis.

Compilé avec AGP 9.5, Gradle 9.9, Kotlin 2.4, Compose (BOM 2026.11) et le SDK Android 37.

## Fonctions

L'accueil est une simple liste (ou une grille) classée en 6 rubriques, avec une recherche,
des favoris (appui long) et les outils récents. Thème clair, sombre ou comme le téléphone,
6 couleurs au choix (**Réglages**). Tous les fichiers créés se retrouvent dans **Mes fichiers**.

### Fichiers
| Outil | Détails |
|---|---|
| **Mes fichiers** | Historique de tout ce que Fluxo a créé : recherche, tri par type, ouvrir, partager, supprimer. |
| **Envoyer à un appareil** | Wi‑Fi local (TCP), sans limite de taille. Découverte automatique, QR code ou IP. |
| **Liens & musique** | YouTube, YouTube Music, SoundCloud… → MP3, M4A, FLAC, WAV, OGG, OPUS, MP4 ; recherche ; liens directs. |
| **Sauvegarder une appli** | APK (ou .apks) de n'importe quelle appli installée. |
| **Réduire la taille** | Images, vidéos, audio optimisés, ou tout en ZIP. |
| **Infos d'un fichier** | Détails, empreintes, comparer deux fichiers, compter les mots, contenu d'un ZIP, durée totale, EXIF complet avec lien carte. |

### Convertir
Convertir un fichier (vidéo, audio, image, PDF, Word, JSON/CSV/XML, archives) · XAPK → APK · Vidéo → images ·
Vidéo → texte (TXT + SRT, hors connexion) · Photo → texte (OCR, + extraction des liens, e-mails, numéros) ·
Texte → voix · **Unités & devises** (19 catégories dont cuisine, angle, puissance, débit internet, consommation ; 31 devises) ·
**Nombres** (binaire/octal/hexa, chiffres romains, en toutes lettres, PGCD/PPCM, nombres premiers).

### Modifier
| Outil | Actions |
|---|---|
| **Vidéo** (34) | Couper, retirer le son, vitesse, pivoter, redimensionner, à l'envers, capture, changer la musique, fusionner, diaporama, **réduire le poids, extraire le MP3, GIF, recadrer (1:1, 9:16…), format TikTok/Reels avec fond flou, texte, logo, 9 filtres, flou/pixels, anti-grain, stabiliser, fondus, boomerang, boucle, accéléré/ralenti, retourner, images/s, monter le son, supprimer un passage, découper en morceaux, planche d'aperçus, audio + image → vidéo**. |
| **Audio** (31) | Couper, volume, normaliser, vitesse, basses, karaoké, fondus, à l'envers, fusion, mono, **tonalité, nightcore, slowed + reverb, écho/réverb, son 8D, anti-bruit, voix podcast, supprimer les silences, aigus, égaliseur, sonnerie iPhone M4R, qualité MP3, découper, mélanger deux pistes, boucle, canaux G/D, silence au début, image de l'onde, spectrogramme, limiteur**. |
| **Photo** (32) | Redimensionner, pivoter, N&B, carré, filigrane, supprimer EXIF/GPS, réduire le poids, collage, **changer de format, recadrer (16:9, 4:5…), taille exacte, 9 filtres, flou/pixels, netteté, vignette, coins arrondis, rond (profil), cadre, carré sans recadrer, découper en grille, mème, légende, date de prise, sticker WhatsApp, icônes d'appli, fond blanc, retourner, couleurs dominantes, ASCII, photos → GIF**. |
| **PDF** (21) | Fusionner, extraire, séparer, pivoter, supprimer, protéger/déverrouiller, images → PDF, **réduire le poids, numéroter, filigrane, inverser, réorganiser, pages paires/impaires, recto/verso, page blanche, signer avec une image, pages → images, extraire le texte, infos, titre/auteur**. |
| **Texte** | 50 transformations : casses (camelCase, snake_case, slug…), tris, doublons, Base64, URL, JSON lisible/compact, morse, binaire, hexa, ROT13, MD5/SHA-256, HTML, mots fréquents, somme des nombres… + rechercher/remplacer (regex), préfixe/suffixe, lorem ipsum, UUID. |
| **QR codes** | Scanner, créer (texte, lien, Wi‑Fi, e-mail, téléphone, SMS). |

### Calculer
**Calculatrice** (parenthèses, %, puissances, racines, trigo, historique) · **Pourcentages** (x % de y, évolution, TVA HT/TTC, remises cumulées) ·
**Argent** (pourboire et partage, crédit, épargne, brut → net, taux horaire) · **Dates** (écart, jours ouvrés, ajouter/retirer, âge, prochain anniversaire, semaine) ·
**Santé** (IMC, calories, eau, zones cardio) · **Petits calculs** (trajet et carburant, prix au kilo, moyenne avec coefficients, recette, allure de course).

### Au quotidien
Chronomètre (tours), minuteur, Pomodoro · Bloc-notes · Liste à cocher · Compteurs · Hasard (dés, pile ou face, nombres, tirage, équipes) ·
Mots de passe (générateur, phrase de passe, PIN, test de solidité) · Couleurs (HEX/RGB/HSL/CMJN, nuances, contraste) · Heure du monde (32 villes) · Dictaphone.

### Téléphone
Lampe torche (SOS, stroboscope, écran coloré) · Niveau à bulle et boussole · Sonomètre · Métronome et générateur de fréquence ·
Mon téléphone (batterie, stockage, RAM, écran, capteurs) · Test d'écran (pixels morts, tactile, règle, dégradé) · Réglages.

Tous les résultats sont enregistrés dans **Téléchargements/Fluxo**.

## Signature (optionnel, recommandé)

Sans configuration, l'APK est signé avec une clé de debug différente à chaque build :
il faut alors désinstaller l'ancienne version avant d'installer la nouvelle.
Pour des mises à jour directes, crée une clé une fois et ajoute ces secrets au dépôt
(Settings → Secrets and variables → Actions) :

```bash
keytool -genkeypair -keystore release.jks -alias superessence -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # → secret SE_KEYSTORE_BASE64
```

Secrets : `SE_KEYSTORE_BASE64`, `SE_KEYSTORE_PASSWORD`, `SE_KEY_ALIAS`, `SE_KEY_PASSWORD`.

## Compiler soi-même

```bash
./gradlew assembleRelease
```

## Licences

FFmpeg (GPL), Vosk (Apache 2.0), NewPipe Extractor (GPL v3), ARSCLib (Apache 2.0),
ZXing, PdfBox-Android, Apache Commons Compress, junrar. L'application est donc distribuée sous GPL v3.
Le téléchargement depuis YouTube est réservé aux contenus dont tu as les droits.
