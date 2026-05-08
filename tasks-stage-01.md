# M1 Phase 1: SQLite + Flyway (M1.T1 + M1.T2)

## Tasks

- [ ] M1.T1: SQLite open + Flyway runner (Db.kt, Migrations.kt)
- [ ] M1.T2: Migration files V1–V5 (Flyway SQL migrations)
- [ ] M1.T3: sqlite-vec extension loader

## Dependencies
- M0.T8 (config module with HebeConfig)

## Notes
- M1.T1 requires observability module for Observer events
- M1.T2 depends on M1.T1 and requires sqlite-vec loaded before Flyway (M1.T3)