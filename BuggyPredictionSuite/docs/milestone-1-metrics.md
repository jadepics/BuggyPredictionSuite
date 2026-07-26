# Metriche della Milestone 1

Il foglio `Dataset` ha granularità `(release, file Java di produzione)`.

## Metriche di prodotto

- `LOC`: linee non vuote contenenti codice nello snapshot della release corrente.
- `CLOC`: linee non vuote contenenti commenti.
- `WMC`: somma della complessità ciclomatica di metodi e costruttori.
- `NPM`: numero di metodi dichiarati `public`.

## Metriche di processo

Per ogni release vengono analizzati i commit raggiungibili dal relativo tag e non successivi alla data effettiva della release.

- `LOCTouched`: somma di LOC aggiunte e cancellate.
- `NR`: numero di revisioni distinte del file.
- `Nfix`: revisioni che coincidono con fix commit JIRA temporalmente validi.
- `Nauth`: autori distinti, identificati tramite e-mail Git.
- `LOCAdded`, `MaxLOCAdded`, `AverageLOCAdded`: LOC aggiunte cumulative, massime e medie per revisione.
- `MaxChurn`, `AverageChurn`: massimo e media del churn (`LOC aggiunte + LOC cancellate`) delle singole revisioni.
- `ChangeSetSize`, `MaxChangeSet`, `AverageChangeSet`: somma, massimo e media del numero di file modificati insieme al file osservato.
- `Age`: età del file in giorni, dalla prima revisione alla release.
- `WeightedAge`: media dell'età delle revisioni pesata per LOC toccate.
- `AGE`: intervallo medio in giorni tra revisioni consecutive.

`Churn` è omessa perché coincide con `LOCTouched`. `NUC` è omessa perché coincide con `NR` alla granularità file-release. `MaxChurn` e `AverageChurn` restano perché contengono informazione non duplicata.

La cronologia è associata tramite path esatta. Una rinomina interrompe la continuità con il vecchio path.

## Feature PMD

La pipeline esegue programmaticamente PMD sui file Java di produzione di ciascuno snapshot. Versione, linguaggio e regole sono congelati nel progetto:

```text
PMD: 7.26.0
Java analizzato da PMD: 1.8
Ruleset: pmd/milestone1-smells.xml
```

Il ruleset contiene esclusivamente regole strutturali e di complessità:

```text
GodClass
DataClass
CyclomaticComplexity
CognitiveComplexity
NPathComplexity
NcssCount
ExcessiveParameterList
TooManyFields
TooManyMethods
CouplingBetweenObjects
ExcessivePublicCount
AvoidDeeplyNestedIfStmts
SwitchDensity
```

Non vengono incluse regole di naming, stile, documentazione o import inutilizzati, perché `NSmells` deve rappresentare problemi strutturali e non la generica quantità di warning statici.

### NSmells

```text
NSmells = numero totale di violazioni PMD non soppresse
```

Più occorrenze della stessa regola contribuiscono più volte. Per esempio, tre metodi segnalati da `CyclomaticComplexity` contribuiscono con 3.

### NPMDRuleTypes

```text
NPMDRuleTypes = numero di nomi distinti di regole PMD violate
```

Esempio:

```text
CyclomaticComplexity(3) | GodClass(1) | TooManyFields(1)
NSmells = 5
NPMDRuleTypes = 3
```

`PMDRules` conserva questa decomposizione per audit, ma è una colonna testuale e non una feature numerica da passare direttamente al classificatore.

### Politica temporale

Per una riga della release `R`, `NSmells` e `NPMDRuleTypes` provengono dalla stessa path nella release selezionata precedente. Questa scelta evita di usare, per la previsione di `R`, informazioni strutturali calcolate sul sorgente di `R`.

- prima release o file appena introdotto: `NSmells=0`, `NPMDRuleTypes=0`, stato `NO_PREVIOUS_SOURCE`;
- analisi completata senza violazioni: conteggi uguali a 0, stato `OK`;
- errore PMD: conteggi vuoti, stato `ERROR`, dettaglio in `PMDAnalysisWarning`.

Le soppressioni standard presenti nel sorgente, come `NOPMD` e `@SuppressWarnings`, sono rispettate: si contano solo le violazioni effettivamente riportate da PMD.
