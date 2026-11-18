# Préparer le dépôt GitHub de Souvenir

## État local

- Le projet n’est pas encore un dépôt Git et aucun dépôt de code Souvenir n’est relié à ce dossier.
- `.gitignore` exclut les caches, les APK/AAB, les clés Android et les anciens snapshots de `work/`.
- `.github/workflows/android-ci.yml` compile l’APK debug et le bundle release sur GitHub Actions avec JDK 21 et Android API 36.
- Les visuels Play sont présents dans `play-store-assets/` : image 1024 × 500, icône 512 × 512 et deux captures de téléphone.

## Avant de rendre le code public

1. Créer un dépôt GitHub vide nommé `SouvenirNotifications` (sans README, licence ni `.gitignore` générés par GitHub).
2. Initialiser Git dans ce dossier, vérifier la liste des fichiers ignorés, puis créer le premier commit.
3. Ajouter le dépôt GitHub comme remote et pousser la branche `main`.
4. Choisir une licence avant d’annoncer le code comme open source. Ne pas publier le fichier `.jks`, les mots de passe, `local.properties`, les anciens dossiers de travail ou des archives personnelles.
5. Vérifier que GitHub Actions termine le build avant de partager le dépôt.

La politique de confidentialité est déjà hébergée séparément sur GitHub Pages. Le dépôt du code et la page de confidentialité sont deux dépôts distincts.

## Limites

La CI vérifie que les variantes compilent; elle ne signe pas l’application et n’exécute pas les essais sur un téléphone. L’AAB de Play doit être régénéré depuis le code final et signé avec une clé d’envoi conservée hors du dépôt.
