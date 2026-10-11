# Smart Electricity Outage Detection and Restoration – Progress Report

## Project Overview
This project is a Java-based simulation of a smart electricity distribution network designed to detect outages, calculate their impact, and restore service using backup paths and repair teams. The current implementation models a demonstration network with substations, feeders, consumer areas, hospitals, and emergency centers.

## What Has Been Implemented So Far

### 1. Distribution Network Model
A working network model has been built with the following classes:
- [DistributionNetwork.java](DistributionNetwork.java): creates and manages the network graph, nodes, and lines.
- [NetworkNode.java](NetworkNode.java): abstract base class for all network entities.
- [NetworkLine.java](NetworkLine.java): models line status, failure, repair, and backup-link behavior.
- [Substation.java](Substation.java), [Feeder.java](Feeder.java), [GeneralArea.java](GeneralArea.java), [Hospital.java](Hospital.java), and [EmergencyCentre.java](EmergencyCentre.java): represent the network components.

The demo network includes:
- 2 substations
- 4 feeders
- 5 general areas
- 1 hospital
- 1 emergency center
- 13 total nodes and 14 lines

### 2. Outage Detection Logic
A reachability-based outage detection system is implemented in [OutageDetector.java](OutageDetector.java). It performs a breadth-first traversal from powered substations and marks all nodes that are disconnected from the source as affected.

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

The simulation currently uses threshold values of:
- voltage drop threshold: 20%
- current deviation threshold: 30%

### 4. Fault Reporting and Priority Scoring
A structured fault report model is implemented in [FaultReport.java](FaultReport.java).

It records:
- failed line
- list of affected nodes
- priority score based on consumer impact
- assigned repair team
- restoration status

Priority is influenced by consumer area severity and critical infrastructure importance.

### 5. Restoration Workflow
Restoration logic is implemented in [RestorationPolicy.java](RestorationPolicy.java). The current workflow includes:
- fault queue management using priority ordering
- repair team assignment
- backup-path search for affected nodes
- activation of standby backup links
- temporary restoration assessment
- final repair and verification of network reachability

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

## Current Project Status
The project is currently functioning as a working prototype and simulation environment. Core logic for network modeling, outage detection, sensor anomaly detection, and restoration strategy has been developed and is integrated into the main program.

## Strengths of the Current Implementation
- Clear separation of concerns between network, outage detection, sensors, and restoration
- Graph-based network modeling suited for electrical network analysis
- Realistic handling of backup links and repair operations
- Demonstration mode with predefined critical infrastructure and consumer areas
- Priority-based restoration workflow

## Limitations / Areas for Improvement
- There is no persisted database or external data source yet
- No formal test suite has been created for validation
- No GUI or web interface is present yet
- The network is static and predefined rather than configurable from input files
- Some restoration logic would benefit from more extensive validation under multiple outage scenarios

## Next Recommended Steps
1. Add automated unit tests for outage detection and restoration behavior
2. Improve network configuration flexibility with file-based or GUI-based input
3. Extend damage and restoration analytics for more realistic utility operations
4. Add reporting/export features for outage logs and restoration history
5. Consider a more advanced optimization strategy for prioritizing multiple faults

## Conclusion
The project has reached a solid prototype stage: the main simulation of an electricity outage detection and restoration system is implemented, integrated, and runnable. The system demonstrates the fundamental decision flow expected in a smart grid environment and provides a strong base for future expansion into a more realistic, scalable utility management tool.
