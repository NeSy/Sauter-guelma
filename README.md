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

## V0.7.2 — appairage MyHOBEN expérimental
D’après les méthodes ConnectToServer, SendDeviceAuth et ManageOpened de MyHOBEN 2.2 : ouverture TLS avec identifiant fourni par le propriétaire, identifiant client propre à cette installation, demande éventuelle du code affiché sur le poêle. Lecture de la réponse d’identification (type, révision, version) uniquement. Aucun registre de commande, aucun réglage de chauffe, aucune tentative automatique ni contournement d’autorisation. Identifiant mémorisé uniquement dans un coffre Android Keystore distinct de Cozytouch, après réponse positive. Validation réseau sur matériel réel requise.

## V0.7.3 — Hoben H5 lecture seule
Source vérifiée : HobenCore de MyHOBEN 2.2, StoveFactory.Create associe type 5 à StoveV4 ; GetRegApplicatif appelle Modbus TCP fonction 04, unité 1, 20 registres à partir de 1024, transaction 65535, préfixe 13 (GetApplicatif). Décodage des réponses 14/7 et filtrage des transactions. Températures signées en dixièmes, états, ventilation, puissance, informations de dérogation. Appairage conservé. Lecture ponctuelle uniquement, aucun registre écrit, aucune commande de chauffe. Réponse réelle à valider sur H5 type 5 révision 0 logiciel 8.x.

## V0.7.4 — dérogations H5 expérimentales
Le relevé réel de V0.7.3 est confirmé sur H5 type 5, révision 0, logiciel 8.2. Les méthodes StoveV4 ActivateDerogation/DeactivateDerogation utilisent fonction 16, transaction 65520, enveloppe 13, registres 1281–1283 (température en dixièmes, départ en minutes, durée en minutes) et 1282–1283 à zéro pour l'annulation. Activation immédiate limitée aux favoris 15–25 °C et 15–480 min, après confirmation, appairage mémorisé et lecture fraîche sans défaut ni absence/arrêt externe/hors gel. Pas de remplacement automatique d'une dérogation existante, ni mise en marche forcée. Vérification de l'accusé et relecture bornée, sans renvoi d'ordre. En cas d'incertitude, actualiser avant de commander. Widget 1×1 ouvre la confirmation, pas de commande silencieuse. Test matériel nécessaire. Correction des libellés d'états selon EOperationState.

## V0.7.5 — icônes Hoben carrées
Widget 1×1 : fond extérieur transparent, icône centrée de 44 × 44 dp, coins arrondis de 9 dp. La grille du lanceur peut rester rectangulaire mais le fond coloré ne s'étire plus. Les associations de favoris et le parcours de confirmation sont inchangés.

## V0.7.6 — widget Hoben actionnable
Activation et annulation de V0.7.4/0.7.5 validées par le propriétaire sur le H5. Option globale explicite « Activation en un appui » (désactivée par défaut) : chaque widget 1×1 lance le favori qui lui est associé, sans navigation dans l'application, via une petite activité de résultat. Vérification de l'association et des valeurs affichées au moment du clic, lecture fraîche et garde-fous existants, confirmation par relecture. Pas de tâche différée ni de renvoi automatique ; verrou partagé et journal persistant pour les résultats incertains, à lever par une nouvelle lecture réelle. Aucun démarrage forcé ni remplacement automatique d'une dérogation existante. Taille carrée 44dp conservée. Test du widget sur téléphone requis.

## V0.8.0 — maison et observation Hoben
Accueil indépendant des connexions, cartes Guelma et H5 avec états réellement lus et horodatage. Accès au détail sans dépendance à l'autre équipement. Actualisation Hoben à l'écran (2 min, uniquement activité reprise, sans ordre de chauffe), observation locale facultative 30 min via WorkManager, données séparées par empreinte de l'identifiant, conservation 90 jours. Journal commun avec préfixe Hoben, courbes et taux observés, pas d'estimation de granulés. Session Hoben exclusive entre appairage, commandes et lecture. Pas de relance automatique d'une commande. L'historique est désactivé par défaut. Les autres commandes Hoben restent à valider avant intégration.

## V0.8.1 — commandes et configuration Hoben V4
Analyse renouvelée de HobenCore et HobenApp 2.2 : fonction 06 adresses 1280, 1284, 1792 ; dérogations différées ; lecture configuration utilisateur 1536/7, historique natif 2560/35 et plages 3072+21×jour/21. Écriture du programme via fonction 23 (3328+21×jour) avec comparaison du relevé initial, et relecture. Consigne manuelle et vacances : préservation des puissances maximales existantes. Activation vacances distincte de la configuration, pas de changement hors gel. Toutes les écritures restent expérimentales sur matériel, avec confirmation et garde persistante. Aucun ordre de combustion, installateur ou maintenance.

## V0.8.2 — analyse thermique Hoben et export
Prévision indicative à partir de relevés réels 30 jours ; apprentissage sur les 23 premiers jours, validation sur les 7 derniers, minimum 14 jours de couverture d'au moins 12h, 60 % de couverture totale, 12 paires chauffe/apprentissage et 8 de validation. Paires 2–45 minutes, consigne constante, puissance positive aux deux extrémités, absence de dérogation. Comparaison MAE à la persistance ; estimation limitée à 3h et 2°C d'écart, non utilisable si validation insuffisante. Pas de commande automatique ni estimation de pellets. Export CSV local. Tests déterministes du modèle. Données distinctes par poêle.

## V0.8.3 — jours de programmation et états natifs
Vérification de PlagesHorairesViewLS/OnJour_Clicked : dimanche=0, lundi=1, samedi=6. Affichage des dates de mise en service/maintenance si valides (registre historique 2565–2568), états vacances, hors gel, arrêt externe, fenêtre ouverte, nettoyage, anticipation et codes bruts d'avertissement. Blocage de configuration incompatible. Aucune modification des protections. Les commandes de programmation nécessitent toujours une validation matérielle.
