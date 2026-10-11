# Smart Electricity Outage Detection and Restoration – Progress Report
   
## Project Overview
This project is a Java-based simulation of a smart electricity distribution network designed to detect outages, calculate their impact, and restore service using standby ring backups and geographically dispatched repair teams. The city-scale demonstration models three urban districts, substations, feeders, consumer zones, and critical facilities.

## What Has Been Implemented So Far

### 1. Distribution Network Model
A working network model has been built with the following classes:
- [DistributionNetwork.java](DistributionNetwork.java): creates and manages the network graph, nodes, and lines.
- [NetworkNode.java](NetworkNode.java): abstract base class for all network entities.
- [NetworkLine.java](NetworkLine.java): models line status, failure, repair, and backup-link behavior.
- [Substation.java](Substation.java), [Feeder.java](Feeder.java), [ZonedArea.java](ZonedArea.java), and [CriticalFacility.java](CriticalFacility.java): represent the network components, including city districts and weighted consumer zones.

The demo network includes:
- 3 substations, 9 feeders, 14 consumer areas, and 4 critical facilities
- 30 total nodes connected by 27 radial primary lines and 8 standby ring links
- industrial, commercial, and residential consumer zones with priority weights of 50, 30, and 10
- North, South, and Downtown districts for dashboard layout and repair dispatch

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
renders the 20-node, 21-line distribution network, supports clicking nodes and lines
to edit sensor readings and line states, and provides controls for realistic city events,
multi-source BFS detection, automatic restoration, and grid reset. Its repair queue
shows the line ID, fault zone, priority, dispatched team, and ETA.

Launch the desktop interface from the project root with:
`javac *.java` followed by `java MainDashboard`.

### 8. Phase 4: History Persistence and Scenario Validation
[HistoryManager.java](HistoryManager.java) appends fault detection, activated backup-plan,
and repair-completion events to `outage_history.csv` in the working directory. It writes
the six-column CSV header on first use, quotes CSV fields safely, and loads event rows for
the dashboard's History table. `SensorMonitor` and `RestorationPolicy` receive the same
manager from `MainDashboard`; persistence errors are surfaced rather than ignored.

[ScenarioTestRunner.java](ScenarioTestRunner.java) runs 15 reproducible line and
substation-failure scenarios on the 20-node demonstration network. It measures outage
detection BFS and restoration backup-path search with `System.nanoTime()` and prints
their combined latency in milliseconds. Run it from the project root with:
`javac *.java` followed by `java ScenarioTestRunner`.

## Current Project Status
The project is currently functioning as a working prototype and simulation environment. Core logic for network modeling, outage detection, sensor anomaly detection, and restoration strategy has been developed and is integrated into the main program.

## Strengths of the Current Implementation
- Clear separation of concerns between network, outage detection, sensors, and restoration
- Graph-based network modeling suited for electrical network analysis
- Realistic handling of backup links and repair operations
- Demonstration mode with predefined critical infrastructure and consumer areas
- Priority-based restoration workflow

## Limitations / Areas for Improvement
- Event history is persisted in a local CSV file rather than a database or external data source.
- A standalone `Phase1StabilizationTest.java` verification runner covers baseline
  reachability, single and multiple line failures, and restoration.
  Run it from the project root with `javac *.java` followed by
  `java Phase1StabilizationTest`.
- The network is static and predefined rather than configurable from input files
- Some restoration logic would benefit from more extensive validation under multiple outage scenarios

## Next Recommended Steps
1. Add automated unit tests for outage detection and restoration behavior
2. Improve network configuration flexibility with file-based or GUI-based input
3. Extend damage and restoration analytics for more realistic utility operations
4. Consider a more advanced optimization strategy for prioritizing multiple faults

## Conclusion
The project has reached a solid prototype stage: the main simulation of an electricity outage detection and restoration system is implemented, integrated, and runnable. The system demonstrates the fundamental decision flow expected in a smart grid environment and provides a strong base for future expansion into a more realistic, scalable utility management tool.
