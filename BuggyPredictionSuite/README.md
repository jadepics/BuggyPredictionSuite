# Buggy Prediction Suite

Progetto Maven multi-modulo destinato a contenere tutte le milestone del progetto di Buggy Prediction. La cartella `milestone-1` è il primo modulo, non l'intero progetto.

## Struttura della Milestone 1

La Milestone 1 è una pipeline di macro-fasi autonome:

1. `ReleaseStep` — release JIRA, tag GitHub e anomalie;
2. `JiraTicketStep` — ticket Bug JIRA;
3. `CommitStep` — fix commit e file modificati;
4. `TicketLifecycleStep` — OV, AF, FV, IV, Proportion e consistenza;
5. `SourceMetricsStep` — sorgenti Java, metriche di prodotto, metriche di processo Git e analisi PMD;
6. `LabelingStep` — propagazione `[IV,FV)` e labeling;
7. `WorkbookStep` — workbook Excel finale.

Ogni fase implementa `MilestoneStep` ed è coordinata da `Milestone1Pipeline`. Le classi tecniche strettamente collegate a una fase sono raccolte nello stesso file sorgente, evitando sia la God Class sia la frammentazione in decine di file microscopici.

## Feature del Dataset

Il foglio `Dataset` contiene:

```text
LOC, LOCTouched, NR, Nfix, Nauth, LOCAdded, MaxLOCAdded,
AverageLOCAdded, CLOC, WMC, MaxChurn, AverageChurn,
ChangeSetSize, NPM, MaxChangeSet, AverageChangeSet, Age,
WeightedAge, AGE, NSmells, NPMDRuleTypes
```

Le metriche di processo sono ricostruite dalla cronologia Git raggiungibile dal tag della release. `Nfix` considera esclusivamente i fix commit JIRA validati temporalmente.

Gli smell sono calcolati con **PMD 7.26.0** usando il ruleset fisso:

```text
milestone-1/src/main/resources/pmd/milestone1-smells.xml
```

Per una riga della release `R`, le feature PMD derivano dalla stessa path nella release selezionata precedente:

- `NSmells`: numero totale di violazioni PMD non soppresse;
- `NPMDRuleTypes`: numero di nomi distinti di regole PMD violate;
- `PMDRules`: colonna di audit con regola e numero di occorrenze;
- `PMDAnalysisStatus` e `PMDAnalysisWarning`: distinguono analisi corretta, sorgente precedente assente ed errore.

`Churn` non è esposta perché coincide con `LOCTouched`. `NUC` non è esposta perché coincide con `NR` alla granularità file-release. `MaxChurn` e `AverageChurn` restano presenti perché descrivono la distribuzione del churn tra le revisioni.

## Esecuzione completa

```powershell
mvn -pl milestone-1 clean compile exec:java
```

Output:

```text
milestone-1/output/milestone_1_dataset.xlsx
```

## Esecuzione progressiva

La proprietà `milestone.step` esegue automaticamente la pipeline fino alla fase richiesta:

```powershell
mvn -pl milestone-1 clean compile exec:java `
  "-Dmilestone.step=tickets"
```

Valori ammessi:

```text
release, tickets, commits, lifecycle, metrics, labeling, workbook
```

Esempio di prova rapida fino alle metriche, limitata a due release:

```powershell
mvn -pl milestone-1 clean compile exec:java `
  "-Dmilestone.step=metrics" `
  "-Dmilestone.maxReleases=2"
```

## Proprietà principali

- `milestone.releaseFraction` — frazione di release analizzate, default `0.33`;
- `milestone.maxReleases` — limite opzionale per test rapidi;
- `milestone.githubRefresh` — aggiorna il mirror GitHub, default `true`;
- `milestone.proportionTrace` — log di ogni Proportion, default `true`;
- `milestone.root` — percorso esplicito del modulo `milestone-1`.

Il codice usa `java.util.logging`. Il binding `slf4j-jdk14` inoltra allo stesso sistema anche i messaggi interni di PMD. Il codice di produzione non usa `System.out`, `System.err` o `printStackTrace`.
