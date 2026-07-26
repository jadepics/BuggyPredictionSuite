# Architettura della Milestone 1

La pipeline segue i prodotti metodologici della Milestone 1 anziché creare una classe per ogni
funzione tecnica.

```text
Milestone1Pipeline
  -> ReleaseStep
  -> JiraTicketStep
  -> CommitStep
  -> TicketLifecycleStep
  -> SourceMetricsStep
  -> LabelingStep
  -> WorkbookStep
```

`PipelineContext` conserva i risultati tra una fase e la successiva. Le fasi producono dati di dominio;
soltanto `WorkbookStep` e `WorkbookWriter` dipendono dalla serializzazione Excel.

Le implementazioni tecniche sono raccolte vicino alla fase di appartenenza:

- `GitHubService.java`: mirror Git, cronologia, commit e archivi dei tag;
- `ReleaseStep.java`: catalogo e controlli sulle release;
- `TicketLifecycleStep.java`: costruzione lifecycle, Proportion e validazione;
- `SourceMetricsStep.java`: parsing AST per LOC/CLOC/WMC/NPM, esecuzione programmatica di PMD e propagazione temporale dei risultati PMD;
- `GitHubService.java`: oltre a mirror, tag e commit, ricostruisce per ogni file le revisioni raggiungibili dal tag e calcola LOC toccate, churn massimo/medio, autori, fix, change set e metriche temporali;
- `WorkbookWriter.java`: tutti gli otto fogli Excel e gli stili;
- `Models.java`: modelli ed utility condivise.

La fase richiesta si seleziona con `-Dmilestone.step=<id>`. La pipeline esegue automaticamente tutti
i prerequisiti fino a quella fase.


## Politica temporale delle feature

Per ogni riga `(release, file Java)`:

- `LOC`, `CLOC`, `WMC` e `NPM` derivano dallo snapshot sorgente della release corrente;
- le metriche di processo derivano dai commit raggiungibili dal tag e non successivi alla data effettiva della release;
- `NSmells`, `NPMDRuleTypes` e le colonne di audit PMD derivano dalla stessa path nella release selezionata precedente;
- il labeling `Buggy` resta propagato nell'intervallo `[IV,FV)`.

La storia Git è associata tramite path esatta; le rinomine non vengono inseguite attraverso path precedenti.
