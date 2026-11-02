# Smart Electricity Outage Detection and Restoration – Progress Report

## Current Stage
The project is at an integrated prototype stage and is being prepared for the
Review 2 evaluation. The Java simulation, Swing interface, HTTP API, and browser
dashboard are implemented. The browser dashboard presents live network state,
grid operations, an outage queue, repair teams, and recent events. Its summary
metric cards have been removed to keep the dashboard focused on the live
network and operational views.

The core workflow is demonstrable end to end: simulate or edit a fault, detect
affected nodes, queue and prioritize repairs, use standby links where possible,
dispatch repair teams, restore service, and record history. This is an
evaluation-ready local prototype, not a production utility control system.

## Project Overview
This project is a Java-based simulation of a smart electricity distribution
network designed to detect outages, calculate their impact, and restore service
using standby ring backups and geographically dispatched repair teams. It
includes both a desktop Swing interface and a browser dashboard backed by an
HTTP API.

## What Has Been Implemented So Far

### 1. Distribution Network Model
A working network model has been built with the following classes:
- [DistributionNetwork.java](DistributionNetwork.java): creates and manages the network graph, nodes, and lines.
- [NetworkNode.java](NetworkNode.java): abstract base class for all network entities.
- [NetworkLine.java](NetworkLine.java): models line status, failure, repair, and backup-link behavior.
- [Substation.java](Substation.java), [Feeder.java](Feeder.java),
  [GeneralArea.java](GeneralArea.java), [Hospital.java](Hospital.java), and
  [EmergencyCentre.java](EmergencyCentre.java): represent substations, feeders,
  consumer areas, and critical facilities.

The demo network includes:
- 2 substations, 4 feeders, 11 consumer areas, and 3 critical facilities
- 20 total nodes connected by 18 primary lines and 3 standby backup links
- industrial, commercial, and residential consumer zones with priority weights of 50, 30, and 10
- geographically based repair-team dispatch and repair-time estimates

### 2. Outage Detection Logic
A reachability-based outage detection system is implemented in [OutageDetector.java](OutageDetector.java). It performs a breadth-first traversal from powered substations and marks all nodes that are disconnected from the source as affected.

Calling `findAffectedNodes` also updates the powered flag of each feeder and consumer
to reflect the current BFS result. Substation powered flags remain the configured
source availability, so an offline substation is not accidentally re-enabled by
outage detection. Standby backup links are excluded from reachability until activated.

This logic is used to:
- identify affected nodes after a line fails
- rank outage impact
- support restoration verification

### 3. Sensor Monitoring and Anomaly Detection
Sensor-driven fault detection has been implemented in [SensorMonitor.java](SensorMonitor.java) and [SensorReading.java](SensorReading.java).

Current features include:
- normal vs. observed voltage/current comparison
- abnormal reading detection using threshold percentages
- explanation of abnormal readings (voltage drop or current deviation)
- suspicious line detection across the network
- automatic line failure when a working line's sensor reading exceeds the configured voltage-drop threshold
- storm, peak-load overload, and random aging events producing simultaneous residential tears, safety trips, and brownouts
- outage diagnostics that identify affected consumer areas and all critical facilities

The simulation currently uses threshold values of:
- voltage drop threshold: 20%
- current deviation threshold: 30%

### 4. Fault Reporting and Priority Scoring
A structured fault report model is implemented in [FaultReport.java](FaultReport.java).

It records:
- failed line
- list of affected nodes
- priority score based on consumer impact
- assigned team and team status
- estimated repair duration, including line repair and depot-to-fault travel time
- restoration status

The restoration queue ranks critical infrastructure first, then industrial/commercial economic impact, then residential households affected, and finally report order.
Priority changes remove and reinsert queued reports so the heap remains correctly ordered.

### 5. Restoration Workflow
Restoration logic is implemented in [RestorationPolicy.java](RestorationPolicy.java). The current workflow includes:
- deterministic priority queue management and enqueue-time assignment from North Depot, South Depot, and Central Depot teams
- repair-team dispatch to the nearest available depot for each fault district
- 45-minute base repair time plus district travel time of 15-40 minutes
- candidate-plan search restricted to approved standby backup links
- plan scoring using critical facilities restored, consumers restored, and estimated repair time
- activation of standby backup links
- BFS verification of backup-plan reachability and final repaired-grid connectivity

### 6. Interactive Console Application
The project has an interactive menu-driven Java application in [Main.java](Main.java). It supports:
1. Displaying the network layout
2. Simulating a manual line fault
3. Running sensor-based automatic fault detection
4. Showing affected nodes
5. Restoring the network
6. Resetting the network state
7. Exiting the program

This provides a clear demonstration of the outage detection and restoration process in a user-friendly command-line interface.

### 7. Interactive Swing Dashboard
The Java Swing frontend is implemented in [MainDashboard.java](MainDashboard.java). It
renders the 20-node, 21-line distribution network, supports clicking nodes and
lines to edit sensor readings and line states, and provides controls for city
events, outage detection, automatic restoration, and grid reset. Its repair
queue shows the line ID, fault zone, priority, dispatched team, and ETA.

Launch the desktop interface from the project root with:
`javac *.java` followed by `java MainDashboard`.

### 8. Browser Dashboard and HTTP API
[MainDashboard.java](MainDashboard.java) also starts the local HTTP API on
port 8081. It exposes network, outage, history, and team data, along with
operations for simulating faults, running detection, restoring the grid,
resetting it, and applying manual line edits. [app.js](app.js) consumes these
endpoints and renders live network state, the dynamic grid-status badge, outage
queue, repair-team table, and recent events in [index.html](index.html) and the
related browser pages. The Grid operations panel provides event simulation,
automatic restoration, and reset controls. The API also exposes a separate
outage-detection operation. The layout is responsive, and the dashboard no
longer includes the six summary metric cards.

Serve the HTML/CSS/JavaScript pages from a local static web server and launch
`MainDashboard` to provide their API. Both processes must be running for the
browser dashboard to load live data and perform operations.

### 9. Phase 4: History Persistence and Scenario Validation
[HistoryManager.java](HistoryManager.java) appends fault detection, activated backup-plan,
and repair-completion events to `outage_history.csv` in the working directory. It writes
the six-column CSV header on first use, quotes CSV fields safely, and loads event rows for
the dashboard's History table. `SensorMonitor` and `RestorationPolicy` receive the same
manager from `MainDashboard`; persistence errors are surfaced rather than ignored.

[ScenarioTestRunner.java](ScenarioTestRunner.java) runs 16 reproducible normal, line,
multi-line, sensor-trip, and substation scenarios on the 20-node demonstration network.
It asserts expected disconnected nodes, affected critical facilities, and backup paths,
and measures BFS and restoration-path search with `System.nanoTime()`. Run it from the
project root with:
`javac *.java` followed by `java ScenarioTestRunner`.

Standalone verification programs are also present:
[Phase1StabilizationTest.java](Phase1StabilizationTest.java),
[Phase2CoreAlgorithmTest.java](Phase2CoreAlgorithmTest.java), and
[Phase4PersistenceTest.java](Phase4PersistenceTest.java),
[RepairTimeIntegrationTest.java](RepairTimeIntegrationTest.java),
[BackupApprovalTest.java](BackupApprovalTest.java), and
[FinalHardeningTest.java](FinalHardeningTest.java). Run them from the project root after
compiling with `javac *.java`.

## Strengths of the Current Implementation
- Clear separation of concerns between network, outage detection, sensors, and restoration
- Graph-based network modeling for connectivity simulation
- Realistic handling of backup links and repair operations
- Demonstration mode with predefined critical infrastructure and consumer areas
- Priority-based restoration workflow
- Desktop and browser interfaces backed by the same Java network simulation
- Local event-history persistence and repeatable scenario/test runners

## Limitations / Areas for Improvement
- The network is static and predefined rather than configurable from input files.
- Event history is persisted in a local CSV file rather than a database or external data source.
- The HTTP API and dashboard are intended for local demonstration; authentication,
  deployment hardening, and multi-user operation are not implemented.
- Browser-level integration checks and a consolidated automated test/build command
  would make evaluation and regression testing more repeatable.
- Restoration logic would benefit from broader validation across simultaneous
  and extended outage scenarios.

## Next Recommended Steps
1. Add browser/API integration checks for live refresh and dashboard operations.
2. Improve network configuration flexibility with file-based or GUI-based input.
3. Extend damage and restoration analytics and evaluate restoration across
   broader multi-outage scenarios.

## Conclusion
The project has reached an integrated, review-ready prototype stage. Its
network simulation, outage detection, sensor checks, restoration workflow,
repair-team assignment, history persistence, Swing interface, and browser
dashboard are in place. The final hardening regression programs pass, and the
next priority is browser/API integration coverage before expanding configuration
or production capabilities.

## Final Hardening Results

The console, Swing dashboard, and every browser page now show the same electrical
safety notice. It identifies this project as a software simulation, limits BFS
to possible graph paths, states that electrical quantities and protection
coordination are not calculated, and prohibits use for real equipment operation.

Restoration status is tracked in three separate fields: line repair completed,
the report's affected consumers restored, and the entire network restored. The
shared restoration policy recalculates report status after repair, backup-link
activation, and new faults. Whole-network restoration requires every graph node
to be reachable and no failed line to remain. Standby, proposed, approved, and
rejected backup links remain inactive during BFS; only an explicitly approved
and activated link participates in connectivity checks.

The documented scenario runs distinguish scenarios from fault/update events:

- `ScenarioTestRunner`: 16 scenarios and 22 fault/update events. It checks
  expected disconnected node IDs, critical facilities, and backup-link paths.
- `FinalHardeningTest`: 16 scenarios and 33 fault/update events. It checks
  priority, team assignment, operator repair time and ETA, approval and
  rejection, backup activation, sequential repairs, restoration states, and
  CSV history persistence.

Final Java regression results:

| Program | Result |
| --- | --- |
| `javac *.java` | PASS |
| `Phase1StabilizationTest` | PASS (5/5 checks) |
| `Phase2CoreAlgorithmTest` | PASS (6/6 checks) |
| `Phase4PersistenceTest` | PASS |
| `RepairTimeIntegrationTest` | PASS (3/3 checks) |
| `BackupApprovalTest` | PASS (13/13 checks) |
| `ScenarioTestRunner` | PASS (16/16 scenarios, 22 events) |
| `FinalHardeningTest` | PASS (16/16 scenarios, 33 events) |
| `git diff --check` | PASS |

The network remains a graph-connectivity simulation. It does not perform real
voltage, current, load-flow, line-capacity, or protection-coordination analysis.

## Online Deployment Preparation

The HTTP API has two existing entry points: `MainDashboard` starts its own API
alongside the Swing interface, while `WebServer` runs the API without opening a
desktop window. `java MainDashboard --headless` delegates to `WebServer`, and
the Docker image uses this headless path. Both servers bind to `0.0.0.0` and
use the `PORT` environment variable, falling back to port 8081.

The browser pages load `config.js` before `app.js`. Set
`window.SMART_GRID_API_BASE` there to the deployed backend URL ending in `/api`;
the default remains `http://localhost:8081/api`. For separate frontend and
backend domains, configure the backend's `FRONTEND_ORIGIN` to the exact
frontend origin (scheme and host, with a port if applicable, and no path or
trailing slash). When configured, only that origin receives CORS permission.
Without it, the backend allows only the documented local development origins.

History remains in `outage_history.csv` in the working directory unless
`DATA_DIR` is configured, in which case that directory contains
`outage_history.csv`. For Render, create and mount a persistent disk at
`/data`, then set `DATA_DIR=/data`. The Docker image defaults to `/data` and
does not copy or initialize the repository's existing CSV into the container.

Build and run locally from the repository root:

```powershell
docker build -t smart-grid-api .
docker run --rm -p 8081:8081 -e PORT=8081 -e DATA_DIR=/data `
  -v smart-grid-history:/data smart-grid-api
```

Render backend configuration:

- Create a **Web Service** with **Docker** as the runtime.
- Use the repository root as the Root Directory and `./Dockerfile` as the
  Dockerfile Path. Leave custom build and start commands empty.
- Set the health check path to `/api/health`.
- Add a persistent disk mounted at `/data`.
- Set `DATA_DIR=/data` and
  `FRONTEND_ORIGIN=https://<your-frontend-service>.onrender.com`.
- Let Render provide `PORT`; do not hardcode a production port.

Render frontend configuration:

- Create a **Static Site** using the same repository root.
- Leave the build command empty and set the Publish Directory to `.`.
- In `config.js`, set
  `window.SMART_GRID_API_BASE` to
  `https://<your-backend-service>.onrender.com/api`.
