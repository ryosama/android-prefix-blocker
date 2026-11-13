# Descriptif du projet

Le projet a pour but de de limiter les appels *entrants* sur un smartphone android via une blacklist de préfix sous la forme "0940*, 0270*".
Cette liste peut etre mis à jour (ajout/modification/suppression) par l'utilisateur sous forme de tableau avec une entrée par ligne.
Les valeurs par défaut sont :
 - 0948*
 - 0270*
 - 0424*
 - 0162*
 - 0377*

 Dans l'application on a l'historique des 50 derniers appels entrants et on peut via un bouton mettre un numéro particulier en blacklist. Au moment de mettre le numéro en blocage, on propose le numéro éditable pour que l'utilisateur puisse évenetuellement mettre un prefix s'il le souhaite.

Pour une entrée on peut écrire "0948*" ou "09 48*" ou "+33948*" (le +33 remplace le 0).

# Technique

- Le projet est versionné avec GIT
- Le projet est réalisé en kotlin et uniquement pour android.
- Le code est commentée pour qu'un humain puisse comprendre ce qui se passe. Commente les constantes, les variables importantes et les blocs de codes "obscure".
- Il sera hébergé sur github : github.com/ryosama/android-prefix-blocker
- Il lui faudra un README.md avec des captures d'écran
- Il ne faudra qu'aucune donnée personnelle de l'ordinateur ou du compte utilisateur avec lequel il a été construit n'aille sur le github public.