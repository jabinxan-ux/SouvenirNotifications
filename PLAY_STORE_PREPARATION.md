# Préparation de Souvenir pour Google Play

État au **18 novembre 2026** : préparation avancée; l’application n’est pas encore publiée sur Google Play.

## Ce qui est prêt dans le projet

- Identifiant Android : `fr.xan.souvenirnotifications`.
- Version source : `0.4.17`, `versionCode 21` (candidate non publiée).
- `compileSdk` et `targetSdk` : API 36. Depuis le 31 août 2026, Google Play demande API 36 ou supérieure pour les nouvelles applications et leurs mises à jour mobiles.
- Consentement par application avant l’archivage; un écran d’information supplémentaire précède maintenant l’ouverture des réglages d’accès aux notifications Android.
- Aucune permission Internet n’est déclarée dans le manifeste; pas de compte, serveur, publicité ou analytique dans le code actuellement examiné.
- Politique publiée : https://jabinxan-ux.github.io/souvenir-confidentialite/ ; le lien est aussi disponible dans l’application. Le code local décrit maintenant correctement l’import périodique WhatsApp, mais la page publique doit recevoir cette mise à jour avant la soumission.
- Brouillons de fiche, de Sécurité des données et de consignes test : voir les fichiers liés ci-dessous.
- Visuels prêts : icône 512 × 512, image de présentation 1024 × 500 et deux captures de téléphone conformes au format PNG 24 bits sans alpha. Les captures doivent refléter l’interface de la version finale.
- Le dossier est maintenant initialisé en dépôt Git local et une CI GitHub compile les variantes debug et release. Aucun dépôt distant de code n’est relié; la politique de confidentialité reste hébergée dans son dépôt GitHub Pages séparé.

## Ce qui bloque encore la soumission

1. **Recompiler la version candidate actuelle.** L’AAB qui se trouve dans `app/build-souvenir-0.4.17/outputs/bundle/release/app-release.aab` est ancien par rapport aux dernières corrections et est non signé. Il ne doit pas être envoyé à Play Console.
2. **Créer la clé d’envoi et générer un AAB signé.** Fais-le dans Android Studio avec le guide [`SIGNED_AAB_GUIDE.md`](SIGNED_AAB_GUIDE.md). Garde la clé `.jks` et ses mots de passe hors du projet et hors de Git; configure Play App Signing.
3. **Tester cette version exacte.** Installer l’AAB par une piste interne/fermée, vérifier permissions, captures, conversations privées/groupes, doublons, médias, restauration, corbeille et stabilité. Les consignes sont dans [`PLAY_TEST_GUIDE.md`](PLAY_TEST_GUIDE.md).
4. **Terminer les formulaires Play Console.** Ajouter la politique publique, vérifier les coordonnées et le nom d’éditeur public, saisir les descriptions de [`PLAY_STORE_LISTING_DRAFT.md`](PLAY_STORE_LISTING_DRAFT.md), compléter Sécurité des données selon [`DATA_SAFETY_DRAFT.md`](DATA_SAFETY_DRAFT.md), puis remplir classification du contenu, public visé, annonces, pays et prix.
5. **Vérifier le compte développeur et le nom de package.** Confirmer dans Play Console l’identité, les coordonnées et l’enregistrement automatique de `fr.xan.souvenirnotifications`. Comme l’application a déjà été installée hors Play sur un téléphone certifié, Play Console peut demander une preuve de contrôle de la clé utilisée pour cette installation; suivre son message avant de créer la fiche ou de changer l’identifiant.
6. **Test avant production.** Si le compte est personnel et a été créé après le 13 novembre 2023, Google exige un test fermé avec au moins 12 testeurs inscrits sans interruption pendant 14 jours, puis une demande d’accès à la production. Vérifier l’éligibilité directement dans le tableau de bord Play Console.
7. **Vérifier les visuels finaux.** Les fichiers présents respectent les dimensions requises, mais les captures doivent être refaites sur la version finale et contrôlées pour éviter les éléments tronqués ou des données personnelles.
8. **Relier le dépôt de code.** Créer un dépôt GitHub vide, choisir une licence si le code doit être open source, puis connecter et pousser la branche `main`. Le projet local ne dispose pas encore d’un dépôt distant.

## Fiche proposée

- Nom : **Souvenir notifications**.
- Catégorie : **Outils**.
- Description courte : **Archive localement les notifications que tu choisis.**
- Contact : `jabinxan@gmail.com`.
- Nom d’éditeur choisi : **Souvenir Studio** (nom public du profil développeur; les informations légales demandées par Google doivent rester exactes).
- Publicités : aucune intégrée dans le code actuellement examiné; confirmer la réponse pour l’AAB final.

La fiche indique clairement que Souvenir archive les informations transmises par Android et ne récupère pas les messages supprimés ni les médias absents des notifications.

## Vérifications techniques déjà faites

- Le manifeste source ne déclare ni permission Internet ni permission de stockage générale. Il déclare la biométrie pour le verrouillage facultatif et `RECEIVE_BOOT_COMPLETED` afin de conserver le JobScheduler de scan WhatsApp après redémarrage.
- API 36 correspond à l’exigence actuelle de niveau d’API pour une nouvelle app mobile. [Exigences officielles de niveau d’API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=fr)
- L’icône et les visuels ont les bonnes dimensions. Google demande au moins deux captures, PNG 24 bits ou JPEG, entre 320 et 3840 px, avec un rapport maximum de 2:1. [Règles officielles des visuels](https://support.google.com/googleplay/android-developer/answer/9866151?hl=fr)
- Les données restent locales d’après le code observé; les formulaires de confidentialité restent à confirmer sur le paquet final et pour toutes les dépendances incluses. Google demande une politique dans l’application et dans la fiche, ainsi qu’une déclaration Sécurité des données exacte. [Règles officielles sur les données utilisateur](https://support.google.com/googleplay/android-developer/answer/10144311?hl=fr)
- Tout AAB envoyé doit être signé avec une clé d’envoi; Play App Signing gère ensuite le certificat de distribution. [Signature Android officielle](https://developer.android.com/studio/publish/app-signing)
- Exigence de test fermé applicable à certains comptes personnels : [article officiel Google Play](https://support.google.com/googleplay/android-developer/answer/14151465?hl=fr).

## Fichiers de préparation

- [Fiche Play à copier](PLAY_STORE_LISTING_DRAFT.md)
- [Brouillon Sécurité des données](DATA_SAFETY_DRAFT.md)
- [Guide de test et accès pour l’examen](PLAY_TEST_GUIDE.md)
- [Message d’invitation aux testeurs](PLAY_TESTER_INVITE.md)
- [Guide de création de l’AAB signé](SIGNED_AAB_GUIDE.md)
- [Politique publiée et guide GitHub Pages](PRIVACY_POLICY_HOSTING_GUIDE.md)
- Visuels : `play-store-assets/`
