# City of Vancouver ABM (MATSim)

Lightweight repository with multiple runnable scenarios demonstrating how to set up and execute MATSim experiments for the City of Vancouver.
Different run files live in one place so you can see how each scenario is wired end-to-end.

## Repository layout (minimal)
```
CityofVancouverABM/
├─ contribs/
│  └─ shared_mobility/
│     └─ src/main/java/org/matsim/contrib/shared_mobility/examples/
│        ├─ RunScenario_*.java      # ← run these; each file = a scenario example
│        └─ *.java                  # helpers, policies, loggers, etc.
├─ configs_helpers/
│  ├─ 25config_bikeshare.xml        # sample config (edit/duplicate per scenario)
│  ├─ bikeshare_prepared/            # prepared network files
│  └─ (other helper XMLs)
├─ output/                           # simulation results go here
├─ matsim_recording_simwrapper.mp4   # demo video (optional; see Video section)
└─ README.md
```

## Quick start (IntelliJ)

1. Clone the repo.

2. Open in IntelliJ → set **Working directory** to the repo root.

3. Open `contribs/shared_mobility/src/main/java/.../examples/` and run any `RunScenario_*.java`.

**Program args (optional):**
```
--config configs_helpers/25config_bikeshare.xml
--service configs_helpers/25_mobi_service.xml
--plans <path-to-your-private-plans.xml.gz>
```

If you don't pass `--plans`, the config's own `<plans inputFile=...>` is used.

## Configs
- Keep several configs in `configs_helpers/` (copy/modify per scenario).
- Plans are not included (private) — point to your own file via the config or `--plans`.
- Network files are available in `configs_helpers/bikeshare_prepared/`

## Where are the run files?

All run files and helpers are here:
```
contribs/shared_mobility/src/main/java/org/matsim/contrib/shared_mobility/examples/
```

Each `RunScenario_*.java` shows a self-contained example of how to build and run a scenario (modules, bindings, listeners, logging).

Helpers (policies, loggers, utilities) live alongside.

## What scenarios are included?

This repo includes multiple example run files showing how to:

- Toggle pricing/credits and time windows (`RunScenario_CompassCard.java`)
- Vary free minute policies (`RunScenario_P*.java` for different time windows)
- Enable e-bike scenarios (`RunScenario_Ebike.java`) 
- Compare baseline vs. alternative configurations
- Enable/disable auxiliary listeners (e.g., logging, auditing)

Use the different `RunScenario_*.java` files as templates and switch dials in code or via config.

## Outputs & dashboards (SimWrapper)

Runs will produce a standard MATSim output folder (e.g., `output/BSS_CompassCard/`) and SimWrapper analysis files if the SimWrapper module is enabled in the run file.

To view results with SimWrapper:

1. Open the [SimWrapper website](https://simwrapper.github.io/site/).
2. Point it to your run's output folder (root with `output_config.xml` / `analysis/`).
3. A dashboard is created automatically (mode shares, distances, chains, etc.).

## Video (demo)

A short recording showing a run and SimWrapper views is included at:
```
matsim_recording_simwrapper.mp4
```

If you're pushing the video to GitHub, use Git LFS:
```bash
git lfs install
git lfs track "*.mp4"
git add .gitattributes matsim_recording_simwrapper.mp4
git commit -m "Add demo video"
git push
```

## How to add your own scenario quickly

1. Duplicate `configs_helpers/25config_bikeshare.xml` → edit parameters/paths as needed.
2. Copy any `RunScenario_*.java` → rename → adjust bindings/paths (keep them relative to the repo).
3. Run from IntelliJ (or your preferred IDE).

## Key Components

- **RepoPaths helper**: Ensures all file paths are relative to repo root
- **CLI overrides**: Support for `--config`, `--service`, and `--plans` arguments
- **Multi-module Maven structure**: Includes matsim core, shared_mobility contrib, and examples
- **Portable configuration**: No absolute paths, works across different machines

## Notes

- Java 17+ recommended.
- Keep all paths relative (avoid absolute `C:\...`).
- Large/restricted inputs (e.g., plans) are not stored here — point runs to your local files.
- The project uses Maven for building and dependency management.

## License

Code in this repository: your preferred open-source license (e.g., MIT).

Input data: use your own sources and respect their licenses.

Generated outputs and analyses: as you prefer (e.g., CC BY 4.0).
