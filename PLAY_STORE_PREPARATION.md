# Préparation de Souvenir pour Google Play

État au **18 novembre 2026** : le code source est publié, mais l’application n’est pas encore disponible sur Google Play.

## État technique

- Identifiant Android : `fr.xan.souvenirnotifications`.
- Version candidate : `0.4.17` (`versionCode 21`), non publiée.
- `compileSdk` et `targetSdk` : API 36.
- GitHub Actions compile avec succès les variantes debug et release. Le bundle généré par la CI n’est pas signé pour Play.
- Politique de confidentialité : https://jabinxan-ux.github.io/souvenir-confidentialite/ (à revérifier et aligner avec les fonctions de la version finale avant soumission).
- L’application archive les données transmises par Android; elle ne récupère pas les messages supprimés ni les médias que l’application source ne fournit pas.

## À faire avant l’envoi

1. Créer une clé d’envoi privée et générer un AAB signé depuis Android Studio. Conserver le fichier `.jks` et ses mots de passe hors de Git. Voir [`SIGNED_AAB_GUIDE.md`](SIGNED_AAB_GUIDE.md).
2. Installer et tester cette version exacte sur les appareils visés : accès aux notifications, messages privés et groupes, doublons, photos/vidéos/vocaux, restauration, corbeille et stabilité. Voir [`PLAY_TEST_GUIDE.md`](PLAY_TEST_GUIDE.md).
3. Vérifier le nom de package et le compte développeur dans Play Console; compléter la fiche, les coordonnées, la classification du contenu, le public visé et la déclaration Sécurité des données. Utiliser les brouillons de ce dépôt et vérifier chaque réponse contre le binaire final.
4. Actualiser les captures et visuels pour qu’ils correspondent à la version soumise.
5. Suivre les exigences de test affichées dans Play Console. Pour certains nouveaux comptes développeur personnels, Google demande un test fermé avant l’accès à la production.

Le dépôt public est [jabinxan-ux/SouvenirNotifications](https://github.com/jabinxan-ux/SouvenirNotifications). Aucun AAB signé n’est stocké dans GitHub. La compilation réussie n’équivaut ni à une validation Play ni à une publication.

## Fiche proposée

- Nom : **Souvenir notifications**.
- Catégorie : **Outils**.
- Description courte : **Archive localement les notifications que tu choisis.**
- Contact : `jabinxan@gmail.com`.
- Nom d’éditeur envisagé : **Souvenir Studio**; les informations légales du compte doivent rester exactes.

## Références officielles

- [Exigences de niveau d’API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=fr)
- [Règles de fiche et visuels](https://support.google.com/googleplay/android-developer/answer/9866151?hl=fr)
- [Données utilisateur et confidentialité](https://support.google.com/googleplay/android-developer/answer/10144311?hl=fr)
- [Signature des applications Android](https://developer.android.com/studio/publish/app-signing)
- [Tests fermés pour certains comptes](https://support.google.com/googleplay/android-developer/answer/14151465?hl=fr)
