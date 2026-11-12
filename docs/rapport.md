# Rapport — Module 1 : capteurs IoT

**Projet Aeria — plateforme ouverte de surveillance de la qualité de l'air**
Novembre 2026

---

## 1. Objectif

Construire la couche « terrain » de la plateforme : des capteurs de qualité de l'air qui envoient leurs
mesures en temps réel, et un service qui les reçoit, les vérifie et les stocke. Le module devait
fonctionner sans matériel (capteurs simulés) tout en restant directement compatible avec un vrai
capteur ESP32.

Compétences mises en œuvre : protocole **MQTT**, développement **Java / Spring Boot**,
environnement **Linux / Docker**.

## 2. Architecture

```
 ESP32 + SDS011  ─┐
                  ├─ MQTT QoS 1 ─▶ Mosquitto ─▶ service d'ingestion Spring Boot ─▶ PostgreSQL
 Simulateur ×6   ─┘                                 │ validation, dédoublonnage
                                                    └─ API REST (tableau de bord, pipeline)
```

| Composant | Technologie | Rôle |
|---|---|---|
| Broker | Eclipse Mosquitto 2.0 (Docker) | reçoit et distribue les messages |
| Ingestion | Spring Boot 3.5, Java 21, Eclipse Paho | abonnement, validation, stockage, API |
| Simulateur | même application, profil différent | 6 capteurs virtuels au Havre |
| Base | PostgreSQL 17, migrations Flyway | stockage opérationnel des mesures brutes |
| Matériel | ESP32 + SDS011 + BME280 | capteur réel (firmware Arduino fourni) |

Le simulateur et le service d'ingestion sont **la même application** lancée avec deux configurations
(`SIMULATOR_ENABLED`, `INGEST_ENABLED`). Le simulateur passe vraiment par le broker MQTT, comme un
capteur physique : on teste donc toute la chaîne, pas un raccourci.

## 3. Choix techniques

**MQTT plutôt que HTTP.** Les capteurs ont peu d'énergie et une connexion instable. MQTT garde une
connexion ouverte légère, les messages font quelques centaines d'octets, et le broker découple les
capteurs des consommateurs : on peut ajouter le pipeline ou une alerte sans toucher aux capteurs.
Une route HTTP `POST /api/measurements` existe tout de même pour les appareils sans client MQTT ; elle
passe par la même validation.

**QoS 1 + idempotence.** QoS 1 garantit qu'un message arrive « au moins une fois », donc il peut
arriver deux fois. La contrainte d'unicité `(sensor_id, measured_at)` et un test avant insertion
rendent l'ingestion idempotente : un message rejoué est compté comme doublon et ignoré.

**Hiérarchie des topics.** `aeria/sensors/{id}/measurements`. L'identifiant dans le topic permet une
ACL par capteur (un capteur ne peut écrire que sur son topic), et le service vérifie que l'identifiant
du message correspond au topic.

**Validation minimale à l'entrée.** Le service ne rejette que l'impossible : valeur négative, hors
plage physique, PM2,5 > PM10, horodatage invalide ou trop ancien. Le nettoyage statistique (pics,
capteur figé, trous) est volontairement laissé au module 2, qui a besoin des valeurs brutes et de
l'historique pour le faire correctement.

**Reconnexion automatique.** Le client Paho se reconnecte seul et le service se réabonne dans
`connectComplete`. Une exception dans le traitement d'un message est attrapée pour ne jamais couper la
connexion.

## 4. Simulation réaliste

Chaque capteur suit un modèle (`ReadingModel`) :

- un **cycle journalier** du trafic avec deux pointes (8 h et 18 h, heure de Paris), réduit le week-end ;
- un **facteur d'environnement** : trafic ×1,6, industriel ×1,35, urbain ×1, rural ×0,55 ;
- un **épisode météo** lent (marche aléatoire) qui fait monter ou baisser la pollution sur plusieurs jours ;
- l'**ozone** qui monte l'après-midi avec le soleil et baisse quand le NO₂ est fort (titration) ;
- le rapport PM2,5/PM10 qui augmente avec l'humidité ;
- des **défauts** avec une probabilité réglable (2 % par défaut) : pic ×6 à ×12, valeur figée, valeur
  négative.

Les six capteurs sont placés au Havre : centre-ville, boulevard de Strasbourg (trafic), port, zone
industrielle, Sainte-Adresse (résidentiel) et Montivilliers (rural). Le Havre a été choisi pour son
port et sa zone industrielle, qui donnent des profils de pollution contrastés.

## 5. Capteur réel (ESP32)

Le dossier `firmware/esp32-sds011` contient un programme Arduino pour un capteur réel :

| Pièce | Rôle | Prix indicatif |
|---|---|---|
| ESP32 DevKit v1 | microcontrôleur Wi-Fi | ~8 € |
| Nova SDS011 | PM2,5 et PM10 (diffusion laser) | ~20 € |
| BME280 | température, humidité | ~5 € |

Le programme lit la trame série du SDS011 (avec vérification de la somme de contrôle), laisse le
ventilateur tourner 30 s avant chaque mesure, horodate en UTC via NTP et publie **exactement le même
JSON** que le simulateur. Il se connecte au broker avec un identifiant égal à son `sensorId`, ce qui
permet l'ACL par capteur.

## 6. Résultats

**Tests automatisés** : 16 tests, tous verts (`mvn verify`, exécutés aussi par GitHub Actions).

| Groupe | Tests | Ce qui est vérifié |
|---|---|---|
| Validation | 7 | chaque règle de rejet, y compris une tentative d'injection dans l'identifiant |
| Simulateur | 4 | pointes aux heures de trafic, 2 000 mesures sans défaut toutes valides, reproductibilité, taux de défauts |
| Ingestion + API | 5 | stockage, enregistrement du capteur, doublon QoS 1, rejet, message illisible, routes REST |

**Test de bout en bout** (Mosquitto et PostgreSQL dans Docker, simulateur à 1 mesure/s par capteur) :
en 30 secondes, 180 messages publiés, 180 stockés, 0 doublon, 0 perte ; les 6 capteurs apparaissent
« en ligne » dans l'API.

**Historique** : le mode *backfill* a généré 120 jours de mesures (une toutes les 15 min) :
68 642 mesures retenues, les mesures défectueuses impossibles ayant été rejetées comme en direct.
Moyennes obtenues : le boulevard (trafic) est le plus pollué en NO₂ (17,5 µg/m³), Montivilliers (rural)
le moins (6,5 µg/m³) — cohérent avec le modèle.

## 7. Difficultés rencontrées

- **Ports déjà pris sur le poste de développement** (5432 réservé par Windows, 5433 utilisé par un
  autre projet) : le port PostgreSQL publié est configurable (`PG_PORT`, 5440 par défaut).
- **Montages de fichiers Docker depuis un disque exFAT** : Docker Desktop voyait le fichier de
  configuration comme un dossier. La configuration Mosquitto est donc intégrée dans une petite image
  (`mosquitto/Dockerfile`), ce qui est de toute façon plus propre pour un déploiement.

## 8. Limites et suites

- En développement, le broker accepte les connexions anonymes (mais n'écoute que sur `127.0.0.1`).
  En production : TLS sur le port 8883, mot de passe par capteur, ACL par capteur — repris dans
  l'analyse de risques du module 5.
- Pas encore d'étalonnage des capteurs à bas coût par rapport aux stations officielles : c'est
  précisément ce que permet la comparaison faite dans le module 2.
- Prochaine étape : monter le capteur réel et le laisser tourner une semaine à côté des simulés.

## 9. Lien avec les autres modules

Les mesures stockées ici sont extraites par le pipeline (module 2) via `GET /api/measurements/export`
ou directement en base ; le parc de capteurs est géré dans le back-office (module 4) ; le tableau de
bord (module 6) affiche leur état en ligne/hors ligne.
