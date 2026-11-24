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
