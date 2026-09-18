# event-exporter

`event-exporter` is one of the services in the [AosCore benchmarking](benchmark.md) stack. It does two jobs from a
single systemd service, both driven by [one config file](#config-file-format):

1. **Always**: tails journald for a fixed list of systemd units and, for each line matching a configured regex,
   pushes a `checkpoint_event` sample to VictoriaMetrics - so Grafana can overlay operation events (instance/component
   start/stop) on the same graphs as the CPU/RAM series the rest of the benchmarking stack collects.
2. **With `--report-timing`** (main node only): a background thread watches VictoriaMetrics for completed AosCore
   deployment "suites" and publishes their aggregated Operational Speed timing (Download/Install/Prepare/Total/...)
   as `benchmark_result` samples - the same job `benchmark/scripts/report_timing.py`, in the
   [demo-services](https://github.com/aosedge/demo-services) repo, used to have to be run separately for. See
   [Benchmark Execution's Operational Speed chapter](benchmark_execution.md#operational-speed) for what each metric
   means and how a test run is actually driven end to end.

Source: `recipes-support/event-exporter/files/event_exporter.py`. The script's own module docstring and comments go
into considerably more depth than this document on *why* each piece of the timing-resolution logic works the way it
does (checkpoint pairing edge cases, suite-boundary staleness, etc.) - read it directly if you're changing that code,
not just using it.

## Command-line usage

```console
event_exporter.py --victoria-url http://localhost:8428 --config event-exporter.yml \
    --node main --unit aos-cm --unit aos-sm --unit aos-iam --report-timing
```

| Option | Required | Meaning |
| --- | --- | --- |
| `--victoria-url` | yes | The main node's VictoriaMetrics base URL (e.g. `http://localhost:8428` on the main node itself, or the main node's address from a secondary node). |
| `--config` | yes | Path to the [config file](#config-file-format) - both the journald-matching patterns and (for `--report-timing`) the checkpoint-pair timing table live in this one file. |
| `--unit` | yes, repeatable | A systemd unit to tail (e.g. `--unit aos-cm --unit aos-sm --unit aos-iam`). Repeat for each unit. |
| `--node` | yes | This instance's node label (e.g. `main`, `secondary-1`) - stamped on every `checkpoint_event`/`benchmark_result` sample it pushes, and (for `--report-timing`) used to scope every VictoriaMetrics query so a multi-node deployment's checkpoints from different nodes can't be paired with each other. |
| `--since` | no (default `now`) | `journalctl --since` value the journald tail starts from. |
| `--report-timing` | no | Also run the background suite-timing thread (see above). Start this on the main node's instance only - VictoriaMetrics only runs on the main node, and a suite must be resolved exactly once. |

## Deployment

`recipes-support/event-exporter/event-exporter.bb` installs one systemd service per node, with per-node
arguments picked via the `:aos-main-node`/`:aos-secondary-node` OVERRIDES layer.conf sets from `AOS_MAIN_NODE` (the
same mechanism `aos-image.inc` uses elsewhere):

* `UNIT_ARGS` - which units to tail. Every node tails `aos-sm`/`aos-iam` (they run on every node); the main node
  additionally tails `aos-cm` (which only runs there).
* `REPORT_TIMING_ARGS` - `--report-timing`, main node only; empty everywhere else.

`VICTORIA_URL`/`NODE` come from `AOS_MAIN_NODE_HOSTNAME`/`AOS_NODE_HOSTNAME`, the same variables
`aos-communicationmanager`/`aos-servicemanager`/`aos-iamanager` already use to resolve the main node and their own
node identity.

The config file (`event-exporter.yml` in the recipe's own `files/`) is installed under that same name -
`/etc/event-exporter/event-exporter.yml` - and marked `CONFFILES`, so a customized copy on a real device survives a
package upgrade instead of being silently overwritten by a fresh default.

## Config file format

One YAML file, two independent sections - see the shipped default,
`recipes-support/event-exporter/files/event-exporter.yml`, for the actual checkpoints AosCore logs today. Nothing
about either section is AosCore-specific in principle; point both at a different service's own log/checkpoint
conventions to reuse this script without a code change.

### `patterns`

A list of regexes. Each journald line is tried against them in order; the first capture group of the first pattern
that matches becomes the `event` label of the `checkpoint_event` sample pushed to VictoriaMetrics. A line matching
no pattern is ignored.

```yaml
patterns:
  - '\[profiling\]\s*(.*)'
  - '^((?:Starting|Started|Stopping|Stopped) AosCore.*)'
```

The defaults match AosCore's own `[profiling] <text>` checkpoint lines (e.g. `[profiling] Start instance begin`)
and the generic systemd unit start/stop status lines PID1 logs for `Starting`/`Started`/`Stopping`/`Stopped AosCore
...` units.

### `checkpoint_ranges`

Only consumed with `--report-timing`. A list of entries, one per elapsed-time metric published as a `benchmark_result`
sample - each metric is the elapsed time between a "start" and an "end" `checkpoint_event` sample.

| Key | Required | Meaning |
| --- | --- | --- |
| `label` | yes | The metric's display name and the `benchmark_result` sample's `name` (lowercased, spaces to underscores, `_s` suffix - e.g. `"Start network"` → `start_network_s`). |
| `start_source` / `start_event` | yes | The `source`/`event` labels of the `checkpoint_event` sample that starts this metric's interval. |
| `end_source` / `end_event` | yes | Same, for the sample that ends it. |
| `nearest_end` | no (default `false`) | Set when `end_event` can recur many times after a *rare* `start_event` (e.g. a checkpoint that only fires on a service restart, followed by one that fires on every deployment afterward) - see below. |
| `optional_all` | no (default `false`) | Set when `start_event`/`end_event` (which must literally start with `"Stop all "`) have a routine, "all "-less variant AosCore logs instead on an ordinary run - see below. |

```yaml
checkpoint_ranges:
  - label: Start network
    start_source: aos-sm.service
    start_event: "Start networks begin"
    end_source: aos-sm.service
    end_event: "Start networks end"
  - label: Init SM
    start_source: init.scope
    start_event: "Starting AosCore Service Manager..."
    end_source: aos-sm.service
    end_event: "Update instances begin"
    nearest_end: true
  - label: Stop network
    start_source: aos-sm.service
    start_event: "Stop all networks begin"
    end_source: aos-sm.service
    end_event: "Stop all networks end"
    optional_all: true
```

Every lookup already tolerates AosCore's own `": key=value, ..."` detail suffix on a checkpoint line (e.g. `"Start
networks begin: count=13"` still matches `start_event: "Start networks begin"`), so `start_event`/`end_event` only
need the fixed prefix, not the full logged text.

**`nearest_end`**: without it, a metric is resolved as the *latest* occurrence of `start_event` paired with the
*latest* occurrence of `end_event` - fine when both sides recur at the same rate. `Init SM` is different: `"Starting
AosCore Service Manager..."` only fires when SM itself restarts (rare), but `"Update instances begin"` fires on
*every* deployment afterward (frequent). Pairing "latest" with "latest" would pair a long-ago SM restart with an
unrelated, much-later deployment. `nearest_end: true` instead resolves the end as the *earliest* occurrence at or
after the (rare) start.

**`optional_all`**: AosCore logs network/instance teardown two ways - `"Stop all networks/instances begin/end"` when
the whole SM process is shutting down, or the same text without `"all "` for a routine per-update teardown of only
what's actually being replaced. Both can appear in the same run; `optional_all: true` tries the `"all "` pair first
(preferred whenever present) and falls back to the plain pair only if no `"all "` occurrence exists at all, rather
than picking whichever is merely more recent.

**The `"Start instances"` entry is required** whenever `checkpoint_ranges` is non-empty: besides being an ordinary
metric, it also drives the per-instance `"Start instances"` breakdown (one `benchmark_result` sample per instance,
`source="Instance: <id>"`) - `event_exporter.py` validates this at startup and refuses to start the background
thread if it's missing, rather than crashing on the first suite it tries to resolve.

## Published metrics

With `--report-timing`, every resolved `checkpoint_ranges` entry (plus the always-present `Total`, resolved
separately - see the script's own docstring) is pushed as a `benchmark_result` sample only if it actually resolved;
a metric that doesn't apply to a given run (e.g. `Download` when nothing needed downloading) is never pushed at
all, not pushed as zero. The default config's own metrics:

| `name` | Meaning |
| --- | --- |
| `download_s`, `install_s`, `prepare_s` | Time to download, install, and prepare deployable items - only for a genuine "install new items" deployment. |
| `init_sm_s` | Time from Service Manager restarting to it beginning to process instance updates. |
| `start_network_s`, `start_instances_s` | Time to set up instance networking and start instances. |
| `stop_network_s`, `stop_instances_s` | Time to tear down instance networking and stop instances. |
| `release_sm_s` | Time from network teardown finishing to Service Manager itself stopping. |
| `total_s` | Time from AosCore receiving a new desired status to every instance having started. |

`start_instances_s` is additionally published once per instance (`source="Instance: <id>"`), alongside the single
aggregate value above (`source` matching that metric's own `start_source`, e.g. `aos-sm.service`).
