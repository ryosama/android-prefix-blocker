# Essai sur Android 12

Appareil prévu : CUBOT P60. Les vérifications ci-dessous restent à réaliser sur le téléphone ; les tests automatisés ne prouvent pas le comportement de son opérateur ou de sa version constructeur.

## Installation et activation

1. Brancher le téléphone déverrouillé, accepter l’autorisation USB, puis lancer `./scripts/install.sh`.
2. Dans prefix blocker, sélectionner l’application comme service de filtrage et vérifier **Filtrage actif**.
3. Accorder la lecture du journal d’appels et vérifier la présence des appels antérieurs à l’installation.
4. Accorder éventuellement la lecture des contacts dans les réglages.
5. Sur le CUBOT P60, dans **Paramètres → DuraSpeed**, autoriser **prefix blocker** en arrière-plan. Vérifier séparément les restrictions de batterie Android ; ces deux réglages sont distincts.

## Blocage réel

Utiliser un second téléphone dont vous contrôlez le numéro. Ne pas ajouter de préfixe large pour cet essai.

1. Sans règle correspondant au second téléphone, appeler : le CUBOT doit sonner normalement.
2. Dans l’historique, ajouter ce numéro à la liste de blocage **sans `*`**. S’il est dans les contacts du CUBOT, le retirer temporairement des contacts pour cet essai de blocage.
3. Rappeler : le CUBOT ne doit pas sonner ; l’appel doit apparaître bloqué dans l’historique après actualisation. Observer séparément ce que fait le répondeur de l’opérateur.
4. Autoriser explicitement ce numéro dans **Autorisés**, puis rappeler : l’appel doit sonner.
5. Supprimer l’exception, enregistrer le numéro dans les contacts et rappeler : l’appel doit sonner malgré la règle.
6. Retirer la règle d’essai et remettre les contacts dans leur état initial.

## Préfixes et conservation

- Dans **Tester un numéro**, vérifier que `0948000001` correspond à `0948*`, tandis que `0947000001` ne correspond pas.
- Modifier la règle en `+33948*` : même résultat. L’ajout de `09 48*` en doublon doit être refusé.
- Vérifier qu’un numéro exact n’affecte pas le numéro voisin.
- Mettre le filtre en pause, vérifier que les appels passent, puis le réactiver.
- Fermer la fenêtre de l’application puis refaire un appel : le service doit fonctionner sans interface ouverte.
- Redémarrer le téléphone, le déverrouiller puis vérifier que le rôle et les règles sont conservés et refaire l’essai.
- Si deux SIM sont utilisées, effectuer un essai sur chacune.
- Refuser ou retirer la permission du journal : l’historique local doit être clairement indiqué et le filtrage doit rester disponible.
- Retirer le rôle de filtrage : l’accueil doit afficher **Activation nécessaire**.
- Vérifier le thème sombre, la lisibilité et le défilement des écrans.
