# Graph Report - .  (2026-07-08)

## Corpus Check
- Corpus is ~10,404 words - fits in a single context window. You may not need a graph.

## Summary
- 280 nodes · 404 edges · 29 communities (20 shown, 9 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 27 edges (avg confidence: 0.83)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Backend Models
- API Client
- Session & Auth
- Backend Services
- Android Main Activity
- Data Repositories
- Step Tracking
- Deployment & Docs
- Local Storage
- Widget System
- Database Layer
- Unit Tests
- Architecture Docs
- System Overview
- Gradle Scripts
- Backend Tests
- MVP & Health Connect
- App Entry
- Backup Docs
- VPS Deployment
- Stats API
- Privacy Policy
- Auth Security
- Demo Account

## God Nodes (most connected - your core abstractions)
1. `Repository` - 19 edges
2. `ApiClient` - 16 edges
3. `module()` - 13 edges
4. `AppViewModel` - 12 edges
5. `StepIntervalDao` - 12 edges
6. `StepIntervalEntity` - 10 edges
7. `StepsTrackerApp` - 9 edges
8. `Security` - 9 edges
9. `UiState` - 8 edges
10. `StepTrackingManager` - 7 edges

## Surprising Connections (you probably didn't know these)
- `StepsTracker Logo` --conceptually_related_to--> `StepsTracker`  [INFERRED]
  android/app/src/main/res/drawable-nodpi/stepstracker_logo.png → README.md
- `Flyway Migrations` --conceptually_related_to--> `Ktor API Component`  [INFERRED]
  docs/deployment.md → README.md
- `Health Connect` --rationale_for--> `MVP Limitations`  [INFERRED]
  README.md → docs/architecture.md
- `TYPE_STEP_COUNTER` --rationale_for--> `MVP Limitations`  [INFERRED]
  README.md → docs/architecture.md
- `Login Endpoint` --implements--> `JWT Authentication`  [INFERRED]
  docs/openapi.yaml → README.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Authentication Flow Components** — readme_jwt_authentication, readme_argon2id, docs_openapi_auth_register, docs_openapi_auth_login [INFERRED 0.85]
- **Deployment Infrastructure** — infra_compose_postgres_service, infra_compose_api_service, infra_compose_caddy_service, docs_deployment_vps_deployment [EXTRACTED 1.00]
- **Profile-Based Calculations** — docs_architecture_stride_calculation, docs_architecture_energy_expenditure, docs_architecture_weight_history, docs_openapi_me_profile [INFERRED 0.75]

## Communities (29 total, 9 thin omitted)

### Community 0 - "Backend Models"
Cohesion: 0.08
Nodes (28): DailyPoint, LoginRequest, LogoutRequest, ProfileRequest, ProfileResponse, RefreshRequest, RegisterRequest, RejectedInterval (+20 more)

### Community 1 - "API Client"
Cohesion: 0.10
Nodes (18): ApiClient, BatchResult, Credentials, DailyPoint, Boolean, String, T, LogoutRequest (+10 more)

### Community 2 - "Session & Auth"
Cohesion: 0.08
Nodes (19): Tokens, Boolean, String, ServerSettings, SessionStore, Flow, Instant, Int (+11 more)

### Community 3 - "Backend Services"
Cohesion: 0.09
Nodes (19): LocalDate, ProfileRequest, String, module(), range(), userId(), validateProfile(), AppConfig (+11 more)

### Community 4 - "Android Main Activity"
Cohesion: 0.14
Nodes (16): AppViewModel, AuthScreen(), Home(), Boolean, Double, Int, String, MainActivity (+8 more)

### Community 5 - "Data Repositories"
Cohesion: 0.14
Nodes (13): create(), Context, Flow, Int, List, Long, String, migrate() (+5 more)

### Community 6 - "Step Tracking"
Cohesion: 0.20
Nodes (11): healthPermissions(), Int, String, onAccuracyChanged(), onSensorChanged(), start(), stopSensor(), TrackingSource (+3 more)

### Community 7 - "Deployment & Docs"
Cohesion: 0.22
Nodes (11): Flyway Migrations, Login Endpoint, Register Endpoint, Account Deletion Cascade, API Service, Caddy Service, PostgreSQL Service, Caddy (+3 more)

### Community 8 - "Local Storage"
Cohesion: 0.24
Nodes (6): IntervalMath, Boolean, Instant, Int, Long, String

### Community 9 - "Widget System"
Cohesion: 0.29
Nodes (6): Context, requestUpdate(), StepWidgetProvider, AppWidgetManager, AppWidgetProvider, IntArray

### Community 10 - "Database Layer"
Cohesion: 0.38
Nodes (3): AutoCloseable, Database, T

### Community 12 - "Architecture Docs"
Cohesion: 0.40
Nodes (5): Energy Expenditure Formula, Stride Length Calculation, Weight History Tracking, Profile Endpoint, Weight History Endpoint

### Community 13 - "System Overview"
Cohesion: 0.40
Nodes (5): Steps Batch Endpoint, 15-minute UTC Intervals, Android App Component, Room Cache, WorkManager

### Community 14 - "Gradle Scripts"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 16 - "MVP & Health Connect"
Cohesion: 1.00
Nodes (3): MVP Limitations, Health Connect, TYPE_STEP_COUNTER

## Knowledge Gaps
- **32 isolated node(s):** `Profile`, `WeightEntry`, `Me`, `Rejection`, `BatchResult` (+27 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **9 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `module()` connect `Backend Services` to `Backend Models`, `API Client`, `Database Layer`, `Android Main Activity`?**
  _High betweenness centrality (0.283) - this node is a cross-community bridge._
- **Why does `Repository` connect `Backend Models` to `Backend Services`?**
  _High betweenness centrality (0.161) - this node is a cross-community bridge._
- **Why does `StepsTrackerApp` connect `Session & Auth` to `API Client`, `Data Repositories`?**
  _High betweenness centrality (0.129) - this node is a cross-community bridge._
- **Are the 8 inferred relationships involving `module()` (e.g. with `.register()` and `.delete()`) actually correct?**
  _`module()` has 8 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Profile`, `WeightEntry`, `Me` to the rest of the system?**
  _32 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Backend Models` be split into smaller, more focused modules?**
  _Cohesion score 0.07632850241545894 - nodes in this community are weakly interconnected._
- **Should `API Client` be split into smaller, more focused modules?**
  _Cohesion score 0.10037878787878787 - nodes in this community are weakly interconnected._