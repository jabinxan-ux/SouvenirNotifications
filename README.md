# Souvenir notifications

Souvenir est une application Android native en Kotlin qui conserve localement les notifications choisies par l’utilisateur.

## Fonctions

- Historique des notifications sélectionnées, filtré par application.
- Conversations privées et groupes, recherche et corbeille avec restauration.
- Galerie des médias transmis par Android et import WhatsApp/Media facultatif.
- Export et restauration de sauvegardes déclenchés par l’utilisateur.
- Choix explicite des applications à archiver; aucun compte, publicité ou serveur Souvenir.

## Limites et confidentialité

Souvenir n’accède pas aux bases de données privées des messageries et ne récupère pas les messages supprimés. Elle archive seulement ce qu’Android transmet; les noms, photos et pièces jointes dépendent des données fournies par l’application source et Android. L’import WhatsApp facultatif ne copie que les fichiers déjà présents dans un dossier autorisé. Les archives restent dans le stockage privé de l’application jusqu’à leur suppression ou désinstallation.

La [politique de confidentialité](https://jabinxan-ux.github.io/souvenir-confidentialite/) décrit le traitement local et les exports.

## Ouvrir et compiler

1. Ouvre ce dossier dans Android Studio.
2. Installe Android SDK Platform 36 et utilise JDK 21 pour Gradle.
3. Laisse Gradle synchroniser le projet puis lance la configuration app sur un appareil Android.

La compilation locale peut aussi se faire avec gradlew.bat assembleDebug. GitHub Actions compile les variantes debug et release. Le bundle produit par CI n’est pas signé pour Google Play : crée une clé d’envoi privée et suis [SIGNED_AAB_GUIDE.md](SIGNED_AAB_GUIDE.md).

## Publication

La version source candidate est 0.4.17 (versionCode 21), cible Android 16 / API 36. Avant une soumission, relis [PLAY_STORE_PREPARATION.md](PLAY_STORE_PREPARATION.md), régénère et teste l’AAB signé, puis termine les formulaires Play Console. La version actuellement publiée sur Google Play n’existe pas encore.
