# Mini-Jeux Bluetooth

Application Android de 21 mini-jeux : à 2 joueurs, téléphone contre téléphone en Bluetooth (sans Internet), ou en solo contre une IA (Facile, Moyen, Difficile).

**Techno :** Kotlin natif + Jetpack Compose + Bluetooth classique RFCOMM — API intégrée à Android, sans Google Play Services ni bibliothèque réseau, fiable et simple (un flux de lignes de texte).

## Jeux
- **Réflexes et rapidité** : ⚡ Duel de réflexes · 👆 Tapotage éclair · ⏱️ Chrono parfait · 🐹 Chasse aux taupes
- **Mémoire et logique** : 🎨 Suite de couleurs · 🔢 Nombre mystère · 🪵 Allumettes
- **Défis rapides** : ➗ Calcul mental · 🌈 Couleur piège · ⚖️ Le plus grand · 🎲 Pair ou impair · 🔍 L'intrus · 🧮 Compte vite · 🧭 Flèches · 🔤 Mot mélangé · ❓ Quiz express
- **Plateau** : ❌ Morpion · 🟦 Grand morpion · ⚫ Cinq en ligne · 🔴 Quatre en ligne · ✊ Pierre-feuille-ciseaux

## Jouer
1. Téléphone A : **Héberger une partie** (accepter la visibilité Bluetooth).
2. Téléphone B : **Rejoindre une partie** → choisir le téléphone A (accepter l'appairage la 1re fois).
3. Choisir un jeu (c'est l'hôte qui lance ; le choix de l'invité lui est envoyé).

**Solo** : *Jouer en solo* → choisir la difficulté → choisir un jeu. Aux jeux de plateau, l'IA cherche les meilleurs coups (minimax) ; aux jeux de score, elle obtient un score plus ou moins bon selon la difficulté.

## Compiler l'APK
**Avec Android Studio (le plus simple)** : ouvrir ce dossier, attendre la synchronisation Gradle, puis
*Build › Build App Bundle(s) / APK(s) › Build APK(s)*.
L'APK sort dans `app/build/outputs/apk/debug/app-debug.apk`.

**En ligne de commande** (JDK 17 + Android SDK, `ANDROID_HOME` défini, Gradle installé) :
```
gradle wrapper
gradlew assembleDebug
gradlew test
```

**Installer** : copier l'APK sur le téléphone et l'ouvrir (autoriser « sources inconnues »), ou `adb install app-debug.apk`.

## Code
- `Bt.kt` : connexion RFCOMM (hôte / invité), lecture, envoi, déconnexion.
- `MainActivity.kt` : autorisations, recherche d'appareils, écrans d'accueil / salon / menu, protocole.
- `Logic.kt` : protocole, règles, IA et générateurs de défis (testés dans `app/src/test`).
- `Games.kt` / `Ui.kt` : écrans des jeux et composants.
