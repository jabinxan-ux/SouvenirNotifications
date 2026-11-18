# Brouillon — Sécurité des données Google Play

**Application :** Souvenir notifications (`fr.xan.souvenirnotifications`)  
**Version analysée :** 0.4.17 (versionCode 21), source du projet au 17 novembre 2026
**État :** proposition à confirmer dans Play Console pour l’AAB final.

Ce brouillon se fonde sur le manifeste, les sources et les dépendances déclarées dans le projet. Il ne remplace pas la vérification du paquet de publication final : une bibliothèque ou une fonctionnalité ajoutée plus tard peut changer les réponses.

## Réponses proposées

| Question Play Console | Réponse proposée | Motif |
|---|---|---|
| L’application collecte-t-elle des données ? | **Non** | Les notifications, noms, avatars et médias sont lus et stockés localement sur l’appareil. Le projet ne déclare pas de permission Internet ni de serveur, compte, publicité ou analytique. Google exclut de la collecte les données traitées uniquement sur l’appareil et non envoyées hors appareil. |
| L’application partage-t-elle des données ? | **Non**, sous réserve du parcours d’export ci-dessous | L’export ou le partage d’un fichier est déclenché explicitement par l’utilisateur via le sélecteur Android ou la feuille de partage. La règle Play exclut du partage les transferts qu’un utilisateur initie spécifiquement et s’attend raisonnablement à effectuer. La politique explique néanmoins que le fournisseur choisi reçoit ce fichier/média et applique ses propres règles. |
| Types de données à cocher | **Aucun**, si le comportement de la version finale reste identique | Les catégories locales de notifications/messages et images ne sont pas « collectées » au sens de la fiche lorsqu’elles ne quittent jamais l’appareil. Ne pas confondre les données accessibles à l’application et les données transmises hors appareil. |
| Chiffrement en transit | **Non applicable** si aucune donnée n’est collectée/transmise par l’application | L’application n’effectue pas de transfert réseau. Ne pas présenter l’archive locale comme chiffrée : le verrouillage facultatif protège l’ouverture de l’interface mais ne chiffre pas séparément les fichiers ou la base locale. |
| Suppression des données | **Suppression locale fournie dans l’application** | L’utilisateur peut effacer l’historique ou supprimer les éléments de la corbeille; les exports sont à supprimer depuis le fournisseur ou l’emplacement qu’il a choisi. Il n’y a pas de compte ni de serveur Souvenir à supprimer. |

## Ce qui a été contrôlé dans le projet

- `AndroidManifest.xml` déclare `USE_BIOMETRIC` et `RECEIVE_BOOT_COMPLETED` (pour conserver le JobScheduler d’analyse périodique après redémarrage); aucun droit `INTERNET` n’est déclaré. L’accès aux notifications passe par le service Android dédié et nécessite l’activation manuelle de l’utilisateur.
- Le service de notification traite les paquets choisis et consentis; l’écran explique les données lues avant l’accès système et demande une confirmation par application.
- En option, l’utilisateur peut accorder via le sélecteur Android un accès persistant au dossier WhatsApp/Media. L’importateur peut analyser ce dossier à l’ouverture, après des notifications WhatsApp et périodiquement en arrière-plan, puis copier des photos, vidéos et fichiers audio dans le stockage privé. La fiche et la politique doivent décrire ce traitement, même si les fichiers ne quittent pas l’appareil.
- La politique affichée dans l’application et le document HTML décrivent le stockage local, l’absence de serveur/compte/annonces/analytique, et les exports initiés par l’utilisateur.
- Les fonctions d’export et de partage utilisent les interfaces Android de sélection et d’action utilisateur.
- Les dépendances directes actuelles sont AndroidX Core KTX et SwipeRefreshLayout. Les dépendances transitives doivent être revérifiées lors de la génération de l’AAB.

## Vérifications obligatoires avant d’envoyer le formulaire

1. Générer l’AAB final, inspecter ses permissions et examiner les dépendances transitives. Si une SDK envoie des données, réviser les réponses et la politique.
2. Tester une exportation ZIP et un partage média; confirmer qu’aucun envoi automatique ou transfert silencieux n’a lieu.
3. Vérifier que l’écran d’information et le consentement par application précèdent l’activation de l’archivage, et que les libellés de la fiche ne promettent pas de récupérer des fichiers absents des notifications.
4. Répondre dans Play Console selon les options exactes proposées à ce moment-là. Cette page est un brouillon et n’a pas été soumise.

## Références officielles

- Google Play, [fiche Sécurité des données](https://support.google.com/googleplay/android-developer/answer/10787469?hl=fr) : les données traitées uniquement localement et non envoyées hors appareil ne sont pas déclarées comme collectées; les transferts initiés par l’utilisateur et attendus par lui peuvent être exclus du partage.
- Google Play, [politique relative aux données utilisateur](https://support.google.com/googleplay/android-developer/answer/10144311?hl=fr) : divulgation claire, consentement affirmatif et politique de confidentialité accessible dans la fiche et l’application.




