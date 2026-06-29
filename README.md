# ExHunter

This repository contains the implementation and evaluation artifact for a
three-stage approach to detecting and repairing uncaught runtime exceptions in
Java methods.

The artifact includes the core implementation, a 1,594-sample benchmark,
ablation scripts, normalized baseline results, and evaluation scripts. The
prebuilt Java API exception knowledge base is distributed as a GitHub Release
asset. Large upstream project clones are not stored in this repository; they
can be downloaded from their public Git repositories with the provided script.

## Method Overview

The pipeline processes each benchmark method in three stages:

1. **Triage** combines static call-path analysis with API exception knowledge,
   scores explicit API/throw candidates, and discovers implicit runtime
   exceptions.
2. **Analyzer** reviews the surviving candidates in context and decides whether
   the target method needs exception handling. If so, it produces a repair plan.
3. **Repairer** follows the plan and generates a repaired Java method.

The supporting static analysis:

- locates the method in the parent revision of the target commit;
- builds an interprocedural call graph;
- propagates runtime exceptions along call paths;
- removes exceptions already caught on the propagation path; and
- queries the bundled API knowledge base for documented and explicit runtime
  exceptions.

## Repository Layout

```text
.
|-- java-scanner/                 Core Java implementation
|   |-- config.example.properties Configuration template
|   |-- pom.xml                   Maven project
|   `-- src/
|-- data/
|   |-- 1594_input_data.json      Method-level benchmark
|   |-- repository_index.json     Repository clone metadata
|   |-- eval_all.py               Main metrics
|   |-- eval_ablation.py          Ablation metrics
|   |-- acrs_eval.py              LLM-judge ACRS metric
|   `-- *stats.py                 Cost, usage, and timing utilities
|-- ablations/                    Component-removal experiments
|-- baselines/                    Normalized baseline outputs
`-- scripts/
    |-- download_repos.sh         Repository downloader
    `-- repo_manifest.tsv         Benchmark repository manifest
```

The main Java packages are:

| Package | Responsibility |
|---|---|
| `org.jdkAnalyzer` | Builds and queries the Java API exception knowledge base. |
| `org.callTreeGenerator` | Parses projects, constructs call paths, and propagates uncaught exceptions. |
| `org.LLMAdvisers` | Builds prompts, calls the configured LLM service, and caches responses. |
| `org.ExperimentExecutor` | Runs static intermediate generation, Analyzer, and Repairer stages. |
| `org.ASTAnalyzer` | Extracts catch types and try-body fingerprints for evaluation. |

## Included Data

### Benchmark

`data/1594_input_data.json` contains 1,594 method-level samples from 45 public
Java repositories:

- 552 positive samples, where the developer added a `try-catch`;
- 1,042 negative samples, where no such handling was added.

Each sample identifies the repository, commit, file, method, pre-change method,
developer revision, and ground-truth label. The repository manifest contains
only public clone URLs; full Git repositories remain governed by their upstream
licenses.

### API Knowledge Base

`java-scanner/analyzeData1.db` is the prebuilt SQLite knowledge base used by the
method. It contains Java classes, methods, exception types, inheritance
relations, call relations, Javadoc, explicit throws, and throw conditions.

The database is required for the full method and is distributed as the
`analyzeData1.db` asset in the latest GitHub Release. Its released SHA-256
digest is recorded in `java-scanner/analyzeData1.db.sha256`.

### Baselines

`baselines/` contains normalized final outputs:

| Method | Result file |
|---|---|
| Seeker | `baselines/seeker/seeker_gpt-4o_output.json` |
| Neurex | `baselines/Neurex/neurex_output.json` |
| KPC | `baselines/KPC/kpc_gpt-3.5-turbo_output.json` |
| FuzzyCatch | `baselines/exassist_repo/exassist_output.json` |
| NexGen | `baselines/nexgen/nexgen_output.json` |
| Direct Prompt | `data/zero-shot-prompt/output.json` |

The upstream implementations, model checkpoints, and raw execution logs are not
duplicated here. `baselines/SHA256SUMS` records the exact released files.

## Requirements

Recommended environment:

- Linux or macOS
- JDK 17
- Maven 3.9 or later
- Python 3.10 or later
- Git
- Bash
- an OpenAI-compatible chat-completions endpoint for the three LLM stages

The Maven compiler target is Java 11, but this artifact has been validated with
JDK 17.

## Clone and Verify

```bash
git clone <repository-url>
cd <repository-directory>
curl -L \
  -o java-scanner/analyzeData1.db \
  https://github.com/taffy-lcx/Exhunter/releases/latest/download/analyzeData1.db
```

Verify the knowledge base from the repository root:

```bash
sha256sum -c java-scanner/analyzeData1.db.sha256
```

The download is valid only when the checksum reports `OK`.

## Download Benchmark Repositories

The full upstream repositories are intentionally excluded because of their
size. Download all 45 repositories with:

```bash
bash scripts/download_repos.sh
```

They are cloned into `repos/`. Existing clones are skipped. Network access and
sufficient disk space are required.

## Configuration

Create a local configuration:

```bash
cp java-scanner/config.example.properties java-scanner/config.properties
```

The important properties are:

| Property | Meaning |
|---|---|
| `TMP_PROJECT_PATH` | Temporary checkout directory. |
| `EXPERIMENT_INTERMEDIATE_PROCESS_DATA_PATH` | Per-sample LLM response cache. |
| `REPOSITORY_INDEX_FILE` | Repository metadata JSON. |
| `REPOSITORY_DATA_PATH` | Directory containing cloned repositories. |
| `EXPERIMENT_MARK` | Unique name for this run. |
| `EXPERIMENT_SOURCE` | Input benchmark JSON. |
| `EXPERIMENT_TARGET` | Requested final output path for compatible runners. |
| `TRIAGE_API_THRESHOLD` | API/implicit candidate threshold. |
| `TRIAGE_THROW_THRESHOLD` | Explicit throw candidate threshold. |
| `STATIC_CANDIDATE_CAP` | Maximum static candidates retained per method. |
| `REPO_FILTER` | Optional comma-separated repository filter. |
| `SAMPLE_LIMIT` | Optional sample limit; `0` means all samples. |
| `LLM_API_URL` | OpenAI-compatible chat-completions endpoint. |
| `LLM_API_KEY` | Provider API key. |
| `LLM_MODEL_NAME` | Model identifier used by the pipeline. |

Paths in the example configuration are relative to `java-scanner/` because the
Java commands below run from that directory.

Do not commit `config.properties`, API keys, access tokens, or generated LLM
response caches.

## Build

```bash
cd java-scanner
mvn -q -DskipTests package dependency:copy-dependencies
```

The runtime classpath used below is:

```text
target/classes:target/dependency/*
```

## Run the Full Method

The three stages are separate so each stage can resume from its JSON output.
Run the following commands from `java-scanner/` after configuring the API and
downloading the repositories:

```bash
export CONFIG_FILE="$PWD/config.properties"
CP='target/classes:target/dependency/*'

java -cp "$CP" org.ExperimentExecutor.BuildStaticIntermediate \
  ../data/example-run/intermediate.json fresh

java -cp "$CP" org.ExperimentExecutor.RunAnalyzer \
  ../data/example-run/intermediate.json \
  ../data/example-run/analyzed_0.2.json

rm -f ../data/example-run/output_0.2.json
java -cp "$CP" org.ExperimentExecutor.RunRepairer \
  ../data/example-run/analyzed_0.2.json
```

Set `EXPERIMENT_MARK=example-run` and both triage thresholds to `0.2` in
`config.properties` to match these paths.

Generated files:

| File | Contents |
|---|---|
| `intermediate.json` | Static call-path candidates and Triage scores. |
| `analyzed_0.2.json` | Analyzer decision, reasoning, and repair plan. |
| `output_0.2.json` | Final detection and repaired method for all samples. |

`BuildStaticIntermediate` resumes by default. Passing `fresh` disables resume.
`RunAnalyzer` and `RunRepairer` also reuse existing output records, so remove
their output files before a fully fresh run.

The repository includes the inputs needed to reproduce the run, but does not
include official full-method intermediate or final output files. LLM results
can vary with provider, model version, and service availability.

## Ablation Experiments

The available component-removal scripts are:

| Script | Removed input or stage | Behavior |
|---|---|---|
| `ablations/run_no_triage.sh` | Triage | Removes triage-discovered implicit candidates and assigns score 1.0 to static candidates before Analyzer and Repairer. |
| `ablations/run_no_analyzer.sh` | Analyzer | Uses surviving Triage candidates directly as the detection decision; this variant is detection-only. |
| `ablations/run_no_call_path.sh` | Call-path context | Re-runs Triage without callee source or path metadata, then runs Analyzer and Repairer. |
| `ablations/run_no_api_knowledge.sh` | API knowledge | Retains call paths and API exception types but removes API Javadoc and throw conditions before re-running Triage, Analyzer, and Repairer. |

The ablations use a completed full-method `intermediate.json` as their starting
point. By default they expect:

```text
data/deepseek-v4-flash-3stage/intermediate.json
```

Override this path and concurrency settings when necessary:

```bash
BASE_INTERMEDIATE="$PWD/data/example-run/intermediate.json" \
ANALYZER_WORKERS=3 \
TRIAGE_WORKERS=5 \
THRESHOLD=0.2 \
bash ablations/run_no_api_knowledge.sh
```

Strict re-triage variants read `LLM_API_URL`, `LLM_API_KEY`, and
`LLM_MODEL_NAME` from environment variables.

## Evaluation

### Main metrics

`data/eval_all.py` reports:

1. **Detection, micro:** Precision, Recall, F1, and Accuracy for deciding whether
   a method needs new exception handling.
2. **Catch type, macro:** Precision, Recall, and F1 between developer-added and
   generated catch types.
3. **Repair localization, macro:** Precision, Coverage, F1, and IoU over
   try-body statement fingerprints.

Catch-type and repair-localization metrics are calculated on each method's own
true-positive subset. The localization metric compares the raw statements
wrapped by the generated and developer `try` blocks.

Registered output paths are listed in the `METHODS` mapping at the top of
`data/eval_all.py`. After placing an output at the corresponding path, run:

```bash
python3 data/eval_all.py --ours ours-deepseek-3stage
```

Write the report to Markdown with:

```bash
python3 data/eval_all.py \
  --ours ours-deepseek-3stage \
  --md data/example-run/evaluation.md
```

### Ablation metrics

After generating the ablation outputs:

```bash
python3 data/eval_ablation.py --md data/example-run/ablation.md
```

`w/o analyzer` is detection-only because the removed stage is responsible for
producing the repair plan.

### ACRS

`data/acrs_eval.py` uses Claude Haiku 4.5
(`claude-haiku-4-5-20251001`) as the default judge model:

```bash
export ACRS_API_URL="https://provider.example/v1/chat/completions"
export ACRS_API_KEY="..."
export ACRS_MODEL="claude-haiku-4-5-20251001"

python3 data/acrs_eval.py --all --md-out data/example-run/acrs.md
```

ACRS is `good / (good + bad)`. API errors or responses outside these two labels
are reported separately and excluded from the denominator.

### Cost and timing

The auxiliary scripts operate on JSONL usage records:

```bash
python3 data/usage_stats.py --help
python3 data/cost_analysis.py --help
python3 data/phase_timing_stats.py --help
```

Provider prices are not hard-coded; pass the applicable input and output token
prices to `cost_analysis.py`.

## Reproducibility Notes

- Run Java entry points from `java-scanner/` so the SQLite database and relative
  paths resolve correctly.
- Use a unique `EXPERIMENT_MARK` for every independent run. LLM response caches
  are namespaced by this mark.
- Keep the model identifier, endpoint, prompts, threshold, and temperature fixed
  when comparing variants.
- A failed static analysis sample can carry a negative label in generated
  intermediate results. The evaluation scripts report or exclude such samples
  according to their documented metric implementation.
- Baseline result files are normalized to the same 1,594-sample schema. Verify
  them with `baselines/SHA256SUMS` before evaluation.

## Excluded Artifacts

The following are intentionally not included:

- full clones of the 45 upstream repositories;
- model checkpoints;
- API credentials and local configuration;
- LLM response caches and execution logs;
- temporary project checkouts;
- official full-method intermediate and generated output files.

## Citation

Citation metadata will be added with the corresponding publication. Downloaded
benchmark repositories and baseline implementations remain subject to their
respective upstream terms.
