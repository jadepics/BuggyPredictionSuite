# Syncope PMD CSV Standalone

Questo è un progetto IntelliJ/Maven **autonomo**. Non va inserito nella repository Apache Syncope.

Quando si esegue `SyncopePmdCsvGenerator`, il programma:

1. scarica da GitHub il branch `master` del fork `jadepics/syncope`;
2. scarica automaticamente PMD 7.26.0;
3. estrae entrambi dentro la cartella locale `runtime/`;
4. individua i file Java di produzione sotto `src/main/java` in tutti i moduli;
5. esegue PMD con le categorie Best Practices, Design, Error Prone, Multithreading e Performance;
6. crea `output/syncope_classes_by_codesmell.csv`, ordinato per numero di code smell decrescente.

Non compila Syncope e non modifica il fork remoto o la copia scaricata.

La categoria Java `Security` non viene inclusa: le sue regole `HardCodedCryptoKey` e `InsecureCryptoIv` cercano vulnerabilità crittografiche, non code smell di manutenibilità, e su alcuni costrutti Java moderni possono causare un errore interno di PMD.

## Requisiti

- IntelliJ IDEA
- JDK 17 o superiore; per coerenza con Syncope va bene il JDK 25
- Connessione Internet durante il primo avvio

Non bisogna installare manualmente PMD, Git o altre librerie.

## Apertura in IntelliJ

1. Decomprimi il progetto.
2. In IntelliJ seleziona **File > Open** e apri la cartella contenente `pom.xml`.
3. Imposta un Project SDK JDK 17+.
4. Apri:

```text
src/main/java/it/university/syncopepmd/SyncopePmdCsvGenerator.java
```

5. Premi il triangolo verde accanto a `main` e scegli **Run**.

Non sono richiesti argomenti.

## Output

Il file finale viene creato qui:

```text
output/syncope_classes_by_codesmell.csv
```

Colonne principali:

- `Rank`: posizione dopo l'ordinamento;
- `ClassName`: nome pienamente qualificato ricavato da package e nome file;
- `Module`: modulo Maven nel quale si trova il sorgente;
- `RelativePath`: percorso del file nella repository;
- `CodeSmellCount`: numero totale di violazioni PMD;
- `DistinctRuleCount`: numero di regole PMD differenti violate;
- `PMDRules`: elenco delle regole;
- `PMDRuleCounts`: numero di violazioni per regola;
- `PMDAnalysisStatus`: `SUCCESS` oppure `PARTIAL_ERROR`;
- `PMDAnalysisError`: eventuale errore di parsing/analisi PMD.

Sono incluse anche le classi con `CodeSmellCount = 0`.

La granularità è una riga per file Java, cioè la stessa convenzione normalmente usata dai dataset di buggy prediction dove una classe di produzione corrisponde al relativo file `.java`. `package-info.java` e `module-info.java` non vengono considerati classi.

## Esecuzioni successive

Per impostazione predefinita Syncope viene riscaricato a ogni esecuzione, così viene analizzato lo stato corrente del branch `master`. PMD viene invece riutilizzato.

Per riutilizzare anche la copia locale di Syncope, aggiungi ai Program arguments:

```text
--reuse-repository
```

Per includere anche i test:

```text
--include-tests
```

Per cambiare il CSV:

```text
--output output/mio_file.csv
```

Per mostrare tutte le opzioni:

```text
--help
```
