# Dépôt GitHub de Souvenir

## Publication effectuée

- Dépôt public : https://github.com/jabinxan-ux/SouvenirNotifications
- Branche publiée : `main`.
- Les sources Kotlin, les ressources Android, les guides Play et les visuels sont publiés avec leurs dossiers.
- GitHub Actions a réussi la compilation de l’APK debug et du bundle release avec JDK 21 et Android API 36.
- `.gitignore` exclut les caches, APK/AAB générés, clés Android et anciens snapshots de `work/`.
- La politique de confidentialité reste dans son dépôt GitHub Pages séparé.

## À garder en tête

- Aucune licence open source n’a été choisie; le code est public et visible, mais sa réutilisation n’est pas accordée par une licence.
- Le terminal n’a pas pu s’authentifier pour pousser. Le code a été publié depuis l’interface GitHub; la branche locale suit maintenant `origin/main`.
- Ne jamais publier le fichier `.jks`, ses mots de passe, `local.properties`, les anciens dossiers de travail ou des archives personnelles.

## Limites

La CI vérifie que les variantes compilent; elle ne signe pas l’application et n’exécute pas les essais sur un téléphone. L’AAB de Play doit être régénéré depuis le code final et signé avec une clé d’envoi conservée hors du dépôt.
