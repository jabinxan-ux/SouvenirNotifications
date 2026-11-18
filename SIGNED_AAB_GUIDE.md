# Générer l’AAB signé de Souvenir dans Android Studio

À faire une fois les derniers essais terminés, dans le projet qui compile déjà. Le paquet debug installé sur le CMF n’est pas le fichier à envoyer sur Google Play.

## 1. Créer une clé d’envoi privée

1. Dans Android Studio, ouvre **Build > Generate Signed Bundle / APK…**.
2. Choisis **Android App Bundle**, puis **Next**.
3. Vérifie que le module **app** est sélectionné.
4. Dans la fenêtre de signature, choisis **Create new…** à côté de **Key store path**.
5. Enregistre le fichier `.jks` dans un dossier privé qui n’est pas dans le projet, par exemple `Documents\AndroidKeys\Souvenir-upload.jks`. Ne le place pas dans `app/`, `build/` ou un dossier synchronisé publiquement.
6. Choisis un alias et des mots de passe uniques, puis renseigne les champs du certificat. Garde les mots de passe dans ton gestionnaire de mots de passe, et conserve une copie de secours du `.jks` dans un emplacement privé et sûr.
7. La clé d’envoi doit rester confidentielle. Ne partage ni le fichier `.jks`, ni ses mots de passe, ni une capture contenant ces informations. Ne les ajoute jamais à Git ou à un ticket.

## 2. Générer l’AAB

1. Dans l’assistant, sélectionne le fichier `.jks`, l’alias et les mots de passe.
2. Sélectionne **release** comme variante et conserve les signatures proposées.
3. Choisis un dossier de sortie privé, puis clique **Create**.
4. Android Studio affiche un lien **Locate** à la fin. Le fichier se trouve généralement sous `app\release\` dans le dossier de sortie choisi, ou sous `app\build\outputs\bundle\release\`.
5. Vérifie que le fichier se termine par `.aab`, et que la notification Android Studio indique une génération réussie. Ne téléverse pas l’APK debug.

Pour une nouvelle application, Google Play App Signing est configuré lors de la première création de release. Google conserve la clé de signature de l’application et utilise la clé d’envoi uniquement pour vérifier les AAB que tu lui fournis. [Guide officiel Android Studio sur la signature](https://developer.android.com/studio/publish/app-signing?authuser=104) · [Guide Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756?hl=fr).

## Détails propres à ce projet

- Module : `app`.
- Identifiant : `fr.xan.souvenirnotifications`.
- Variante : `release`.
- Version actuelle : `0.4.17` / `versionCode 21`. Si une version a déjà été créée ou téléversée dans Play Console, vérifier que ce code est supérieur aux versions existantes avant de générer le fichier.
- Gradle JDK : JDK 21, conformément au README du projet.
- Un AAB release 0.4.17 se trouve ici : `app\\build-souvenir-0.4.17\\outputs\\bundle\\release\\app-release.aab`. Il a été généré avant la dernière modification de divulgation et n’est donc pas le paquet final. Le terminal Codex n’a pas pu relancer Gradle dans cette session : l’accès au daemon local a échoué. Android Studio avait produit ce bundle lors d’une vérification précédente.
- Cet AAB est **non signé**. Il ne peut pas être téléversé sur Google Play en l’état. Recompile la source actuelle puis crée la clé d’envoi privée et génère l’AAB signé dans Android Studio après les derniers essais. Ne génère pas de clé dans le dépôt et ne partage jamais ses mots de passe.

## Après la génération

La politique de confidentialité a une URL publique, mais l’AAB local est non signé et ne convient pas au téléversement. Après les essais, crée la clé d’envoi privée et génère l’AAB signé. Commence par une piste de test, configure Play App Signing à la demande, et complète les déclarations avant toute demande d’examen.




