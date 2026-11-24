# Guelma Control

Application Android indépendante pour le Sauter Guelma 2 MP 65 L Wi-Fi.

## V0.1.1 — diagnostic
- Connexion expérimentale au cloud Cozytouch Magellan et lecture des appareils/capacités. Si aucun appareil ne remonte, échange JWT et découverte Overkiz (référence https://github.com/NicolasYDDER/homey-cozytouch). Aucun ordre de pilotage envoyé ; seules les commandes déclarées sont listées.
- Aucun ordre de chauffe ; aucune modification de la configuration.
- Mode démo clairement identifié, sans connexion réseau.
- Rapport copiable limité aux identifiants de modèles et capacités, valeurs numériques/booléennes et métadonnées techniques. Vérifiez-le avant de le partager.
- Identifiants et jeton uniquement en mémoire, pas de télémétrie, pas de journalisation des réponses brutes. Le mot de passe est effacé du formulaire lors de la connexion.
- L'API est non officielle ; la compatibilité Guelma 2 MP reste à valider. Référence : https://github.com/gduteil/cozytouch. L'identifiant de client de cette intégration est renseigné dans le code ; il ne s'agit pas de vos identifiants personnels.

## Compiler et installer
JDK 17, SDK Android 35, Gradle 8.9. Exécuter `gradle :app:assembleDebug` ou utiliser Actions > Android APK > Run workflow. Télécharger l'artifact Guelma-Control-debug, décompresser puis ouvrir l'APK. Les builds debug ne sont pas destinés à la distribution publique. La signature debug peut changer entre compilations ; désinstaller l'ancienne version si Android refuse la mise à jour.

Android 8.0 minimum. La V0.1 ne programme pas la chauffe. En cas d'erreur, utiliser Cozytouch et ne pas multiplier les tentatives.
