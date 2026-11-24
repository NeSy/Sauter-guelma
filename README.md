# Guelma Control

Application Android indépendante pour le Sauter Guelma 2 MP 65 L Wi-Fi.

## V0.2 — tableau de bord et pilotage expérimental
Le diagnostic réel a confirmé Overkiz Modbuslink pour ce modèle. Tableau de bord des douches, températures, chauffe, modes et index énergétiques bruts. Les unités énergétiques restent à confirmer.

Commandes limitées à un unique composant compatible disponible, à sa liste de commandes, et aux valeurs autorisées. Confirmation avant chaque envoi ; actualisation obligatoire après un ordre. Une acceptation cloud ne garantit pas l'exécution physique. Pas de commande d'absence, programmation ou modification de la protection anti-légionellose dans cette version. Aucun ordre envoyé automatiquement. Aucun identifiant appareil ni jeton dans le rapport. 

API non officielle ; commandes à valider sur matériel réel. Références : https://github.com/gduteil/cozytouch ; https://github.com/NicolasYDDER/homey-cozytouch ; https://github.com/home-assistant/core/blob/dev/homeassistant/components/overkiz/water_heater/atlantic_domestic_hot_water_production_mlb_component.py .

## V0.2.1 — mises à jour et connexion mémorisée
L’option de mémorisation chiffre les identifiants avec AES-GCM et une clé Android Keystore non exportable. La reconnexion est tentée à l’ouverture ; la déconnexion explicite efface les identifiants. Aucun mot de passe dans GitHub.

Configurer une seule fois le secret GitHub Actions `ANDROID_SIGNING_BUNDLE` (JSON contenant `keystore_b64` et `password`, clé PKCS12 alias guelma). Conserver une sauvegarde privée de cette clé. Ne jamais la committer, la publier en artifact ou la remplacer entre versions. Le workflow échoue si ce secret manque, pour éviter de publier un APK avec une signature différente. La première migration depuis un APK debug nécessite une dernière désinstallation. Les versions suivantes signées avec cette même clé peuvent se mettre à jour en conservant les données.

## Compiler
JDK 17, Android SDK 35, Gradle 8.9. Actions > Android APK > Artifacts > Guelma-Control. Décompresser et installer l'APK. Android 8 minimum. Signature stable en CI.

## V0.3.0 — observation des usages
Option volontaire, connexion mémorisée requise. Mesures locales SQLite à chaque actualisation et environ toutes les 30 minutes via WorkManager (retards possibles). Conservation 90 jours, purge manuelle disponible. Aucune commande automatique. Les intervalles candidats exigent une baisse d'au moins une douche estimée et 3 °C sur une sonde, sans changement de mode/consigne/boost/absence, et des observations proches. Classement semaine/week-end indicatif, non une mesure du débit ou une prévision. Le compteur électrique est enregistré brut, sans conversion non vérifiée.

## V0.3.1 — confort de pilotage
Onglets Pilotage, Analyse et Réglages. Thème clair/sombre selon Android. Actualisation toutes les deux minutes en premier plan et vérification bornée du retour d’état après commande (sans réenvoi automatique). En cas de résultat incertain, actualisation manuelle requise. L’observation demeure volontaire et désactivée à la première installation.

## V0.4.0 — analyse approfondie et confort
Journal de collecte temporaire (arrière-plan et application), courbes de températures/réserve/puissance, carte hebdomadaire, cycles observés, tarifs saisis manuellement, index tarifaires historisés (migration SQLite non destructive), export CSV via le sélecteur Android, modèle thermique exploratoire et simulateur de décalage tarifaire. Le calcul en kWh est conditionné à la confirmation de l'unité Wh. Mode absence expérimental avec confirmation explicite et vérification des dates dans Cozytouch. Aucun ordre prédictif automatique. La collecte est désactivée par défaut. Les estimations ne constituent pas une mesure du débit ni des économies garanties.

## V0.4.1 — qualité et journal
Tentatives journalisées avant les appels réseau en premier plan, état WorkManager et prochaine date d'éligibilité (pas une garantie). Prévision exploratoire par jour et heure avec seuil minimal de 14 jours bien couverts et 60 % de couverture. Aucune automatisation de chauffe.

## V0.5.0 — plan prédictif en mode conseil
Apprentissage sur 30 jours complets, validation chronologique sur les 7 derniers jours, seuils de couverture et de récurrence, comparaison de l’erreur probabiliste avec une référence constante. Estimation prudente du temps de recharge à partir des variations de réserve en chauffe et marge de confort réglable. Plan des 24 prochaines heures exportable par copie. Aucune action automatique : le plan doit être validé sur des mesures réelles avant toute future commande autonome. Observation volontaire, signature et base de données inchangées.

## V0.6.0 — refonte de l’interface
Nouvelle navigation fixe Accueil / Planning / Analyse / Réglages, tableau de bord avec réserve animée, cartes de température, choix de mode, Boost et réglages manuels dans des dialogues. Diagnostics et index bruts dans un panneau technique replié. Démonstration visuelle sans communication ni commandes. Le moteur de pilotage, la validation des ordres, l’historique et la signature restent inchangés.

## V0.7.0 — préparation Hoben H5
Espace indépendant Hoben, favoris locaux nommés (température, durée), sélection du favori du widget Android. Le widget ouvre le détail, il n’envoie aucune commande. Diagnostic de transport TLS avec validation du certificat et du nom d’hôte, sans identifiant ni message applicatif. La connexion au poêle et les commandes restent verrouillées tant que le protocole et l’autorisation ne sont pas vérifiés. Aucun APK tiers ni secret inclus dans le dépôt.

## V0.7.1 — widgets 1×1
Widget compact 1×1, température et durée uniquement, association indépendante de chaque instance à un favori, configuration/réglage depuis les lanceurs compatibles. Aucun ordre réseau depuis le widget. Les favoris existants restent conservés. Analyse APK MyHOBEN 2.2 : présence confirmée d'une branche TLS distante et d'une phase d'identification distincte ; aucun message applicatif Hoben n'est encore envoyé, tant que le format d'ouverture et l'autorisation de lecture ne sont pas validés.
