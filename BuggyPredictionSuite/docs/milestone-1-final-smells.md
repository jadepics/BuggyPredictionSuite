# Definizione finale delle feature PMD

La Milestone 1 usa PMD come unico motore di rilevamento degli smell. Il risultato è riproducibile perché il progetto congela:

- PMD `7.26.0`;
- versione linguaggio Java PMD `1.8`;
- ruleset `milestone-1/src/main/resources/pmd/milestone1-smells.xml`;
- soglie di ogni regola;
- politica temporale e gestione degli errori.

## NSmells

`NSmells` è il numero totale di violazioni non soppresse riportate dal ruleset sul file Java della release selezionata precedente.

Non è il numero delle categorie presenti: più violazioni della stessa regola vengono contate separatamente.

## NPMDRuleTypes

`NPMDRuleTypes` è la cardinalità dell'insieme dei nomi delle regole che hanno prodotto almeno una violazione sul file.

```text
NPMDRuleTypes = |{ ruleName : occurrenceCount(ruleName) > 0 }|
```

## Colonne di audit

- `SmellSourceRelease`: release dalla quale provengono i risultati PMD;
- `PMDRules`: elenco ordinato `Regola(conteggio)`;
- `PMDAnalysisStatus`: `OK`, `NO_PREVIOUS_SOURCE` oppure `ERROR`;
- `PMDAnalysisWarning`: dettaglio dell'eventuale errore.

Lo zero indica un'analisi completata senza violazioni oppure l'assenza deliberata di una sorgente precedente, distinguibile mediante lo stato. Un errore non viene mai trasformato in zero: i conteggi rimangono vuoti.
