# Guelma Control

Application Android indépendante pour le Sauter Guelma 2 MP 65 L Wi-Fi.

## V0.2 — tableau de bord et pilotage expérimental
Le diagnostic réel a confirmé Overkiz Modbuslink pour ce modèle. Tableau de bord des douches, températures, chauffe, modes et index énergétiques bruts. Les unités énergétiques restent à confirmer.

Commandes limitées à un unique composant compatible disponible, à sa liste de commandes, et aux valeurs autorisées. Confirmation avant chaque envoi ; actualisation obligatoire après un ordre. Une acceptation cloud ne garantit pas l'exécution physique. Pas de commande d'absence, programmation ou modification de la protection anti-légionellose dans cette version. Aucun ordre envoyé automatiquement. Aucun identifiant appareil ni jeton dans le rapport. 

API non officielle ; commandes à valider sur matériel réel. Références : https://github.com/gduteil/cozytouch ; https://github.com/NicolasYDDER/homey-cozytouch ; https://github.com/home-assistant/core/blob/dev/homeassistant/components/overkiz/water_heater/atlantic_domestic_hot_water_production_mlb_component.py .

## Compiler
JDK 17, Android SDK 35, Gradle 8.9. Actions > Android APK > Artifacts > Guelma-Control-debug. Décompresser et installer l'APK. Android 8 minimum. Signature debug susceptible de changer entre builds ; désinstaller l'ancienne version si nécessaire. Identifiants et jetons en mémoire seulement.
