# Guide de test — Souvenir notifications

Version candidate actuelle : 0.4.17 (versionCode 21). Adapter ce guide à la version réellement envoyée aux testeurs.

Pour les essais de distribution, installer la version depuis la piste de test Google Play. Éviter de demander aux testeurs d’installer un APK depuis un navigateur ou un site de partage : Play Protect peut avertir ou bloquer le sideload d’une application qui demande l’accès aux notifications.

Avant de remplacer la copie installée depuis Android Studio, exporter une sauvegarde si l’historique doit être conservé. La version Play est signée avec une autre clé que la version debug; Android peut donc demander de désinstaller l’ancienne copie avant l’installation de la version Play, ce qui efface ses données locales. Les sauvegardes ZIP ne sont pas chiffrées : les placer dans un emplacement privé et les supprimer après restauration.

## Instructions pour l’équipe d’examen Google Play

Souvenir ne demande pas de compte ni de connexion. Pour essayer sa fonction principale :

1. Ouvrir Souvenir et toucher le bouton de gestion de l’accès aux notifications.
2. Dans les réglages Android, autoriser l’accès aux notifications pour Souvenir, puis revenir dans l’application.
3. Ouvrir l’onglet d’une application de messagerie prise en charge et activer son archivage. Confirmer l’écran d’information et de consentement.
4. Faire arriver une notification de cette application sur l’appareil. La notification devrait apparaître dans l’historique si la source l’a publiée et si Android la transmet au service.
5. Essayer la recherche, le filtre par application, la corbeille et la restauration d’un élément de test.

Les données de test sont locales. Aucun identifiant ou compte n’est nécessaire. Les notifications photo/vidéo et les données d’expéditeur dépendent de leur disponibilité dans la notification source; l’application ne télécharge pas les médias manquants.

## Liste de vérification pour les testeurs

- [ ] L’écran d’accueil indique correctement si l’accès aux notifications est accordé.
- [ ] Une application désactivée ou non consentie n’est pas archivée.
- [ ] Après activation et consentement, un nouveau message test apparaît dans l’historique.
- [ ] Une mise à jour répétée d’une même conversation ne crée pas de cartes en double.
- [ ] Le nom et l’avatar apparaissent quand la notification les fournit; sinon une initiale est affichée.
- [ ] Une photo jointe fournie par Android s’affiche et peut être ouverte entièrement.
- [ ] Tester une courte vidéo; noter si elle apparaît comme pièce jointe ou seulement comme texte. WhatsApp a déjà transmis un résumé vidéo textuel sans fichier média dans un test connu.
- [ ] Si l’importation WhatsApp est activée, choisir explicitement le dossier WhatsApp/Media, vérifier l’import de médias téléchargés, puis retirer l’accès et confirmer que les analyses s’arrêtent. Les fichiers déjà copiés restent à gérer dans Souvenir.
- [ ] Supprimer depuis Souvenir le fichier local d’un média importé et laisser passer une analyse ultérieure; vérifier que le fichier supprimé ne revient pas automatiquement dans le stockage.
- [ ] La recherche et les onglets filtrent les éléments sans les supprimer.
- [ ] Mettre un message de test dans la corbeille, le restaurer, puis le supprimer définitivement.
- [ ] Créer une sauvegarde ZIP dans un emplacement de test, puis vérifier sa restauration. Ne pas utiliser une archive contenant des notifications personnelles.
- [ ] Vérifier que désactiver l’archivage arrête la conservation des notifications suivantes.
- [ ] Sur Android 14 et Android 16, vérifier fermeture/réouverture, reconnexion du service et stabilité générale.
- [ ] Le verrouillage par empreinte/code est facultatif; vérifier le comportement s’il est activé et laisser désactivé s’il n’est pas testé.

## Comment remonter un problème

Indiquer le modèle du téléphone, la version Android, la version de Souvenir, l’application source, l’heure approximative, le résultat attendu et ce qui s’est réellement passé. Pour une notification manquante, préciser si elle était visible dans Android et si l’application source était ouverte. Masquer les noms, textes, photos et numéros personnels avant de joindre une capture.

Ne pas envoyer de vraie notification privée, de sauvegarde personnelle ou de clé de signature au développeur pour un test.




