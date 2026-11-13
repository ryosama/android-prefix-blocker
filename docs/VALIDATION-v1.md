# Validation de la version 1.0.0

Vérifications du 13 novembre 2026.

## Résultats automatisés

| Groupe | Tests réussis |
| --- | ---: |
| Formats et normalisation des numéros | 8 |
| Priorités et décisions de filtrage | 9 |
| Stockage et conservation des données | 8 |
| Lecture et rapprochement du journal système | 6 |
| Service Android de filtrage | 9 |
| Interface, édition, suppression, activation, thème et cycle de vie | 9 |
| **Total** | **49** |

Zéro échec et zéro erreur. Les tests Android utilisent Robolectric 4.14.1 avec l’API 31 (Android 12). Les captures sont des rendus natifs de cette interface avec des données fictives.

L’analyse Android Lint renvoie **No issues found** en debug et en release, avec les avertissements traités comme erreurs. Le build release est optimisé avec R8 et la réduction des ressources.

La cible API 35 est volontaire pour cette v1 distribuée directement en APK. Le conseil `OldTargetApi` est exclu uniquement sur cette déclaration : il varie selon les SDK installés sur la machine de compilation. Les autres contrôles restent actifs.

Le service est vérifié pour le rejet d’un appel correspondant, les appels autorisés, les exceptions, les contacts, la pause, les numéros indisponibles, l’exclusion des appels sortants et les erreurs du fournisseur de contacts. Une vérification trop lente déclenche une autorisation de secours après 3 secondes ; une réponse tardive ne rejette pas l’appel ensuite.

## APK distribué

- Application : `com.prefixblocker.app`, version `1.0.0`, code de version `1`.
- Android minimum : API 29 ; cible : API 35.
- Signature APK v2 validée par `apksigner`, clé RSA de 3072 bits.
- Certificat générique : `CN=Prefix Blocker, O=Prefix Blocker, C=FR`.
- Empreinte SHA-256 du certificat : `043c41a8c09d3dfd4f4eb19c3a92a9780fc03d9f3b901050b5ba4e91abceac18`.
- Alignement de l’APK vérifié avec `zipalign`.
- Deux autorisations demandées : lecture du journal d’appels et lecture des contacts. Aucune autorisation Internet.
- Vérification des fichiers suivis et du contenu de l’APK contre les identifiants privés locaux, sans détection.
- La somme de contrôle de l’APK figure dans le fichier `SHA256SUMS` joint à la version GitHub.

## Essais réels sur CUBOT P60 / Android 12

L’installation et l’interface ont été vérifiées sur le téléphone. Le rôle de filtrage et les autorisations de lecture des contacts et du journal ont été confirmés par USB.

Un appel indésirable a été laissé passer après expiration du délai Android de filtrage, sans connexion au service. DuraSpeed était activé sans exception pour prefix blocker ; l’autorisation d’arrière-plan propre à DuraSpeed a été activée, puis relue pour vérifier son application. Cette configuration est une cause possible du délai initial, sans preuve qu’elle en était l’unique cause.

Trois appels contrôlés depuis un numéro conservé dans les contacts ont permis de vérifier :

- la connexion et la réponse du service avec l’application ouverte ;
- le redémarrage du service après arrêt normal de son processus, sans arrêt forcé ;
- après activation de l’exception DuraSpeed, le redémarrage et la réponse après plus de six minutes d’écran éteint, avec le processus de l’application arrêté avant la veille. La réponse a été reçue environ 310 ms après le début du filtrage, sans dépassement de délai.

Le dernier essai a été réalisé avec l’USB branché. Il ne reproduit pas toutes les conditions d’une longue veille sur batterie. Les appels de test ont été autorisés normalement ; aucun contact n’a été supprimé et aucun numéro personnel n’est publié dans ce compte rendu.

Le rejet réel d’un numéro correspondant à une règle et absent des contacts et exceptions reste à vérifier. Les essais après redémarrage du téléphone, sur chaque SIM utilisée et en longue veille sur batterie restent également à effectuer selon le [protocole d’essai](TEST-SMARTPHONE.md).
