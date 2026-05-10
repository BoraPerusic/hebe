package com.hebe.memory.db

import java.nio.file.Path
import javax.sql.DataSource
import org.flywaydb.core.Flyway

class Db(
    val dataSource: DataSource,
    val isInMemory: Boolean,
)

data class MigrationResult(
    val version: String?,
    val applied: Int,
)

object DbFactory {
    fun open(
        path: Path,
        observer: com.hebe.api.Observer? = null,
    ): Db {
        val ds = org.sqlite.SQLiteDataSource()
        ds.url = "jdbc:sqlite:$path?journal_mode=WAL&busy_timeout=5000&foreign_keys=on"

        ds.connection.use { conn ->
            tryLoadVecExtension(conn)
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA synchronous=NORMAL")
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA temp_store=MEMORY")
                st.execute("PRAGMA mmap_size=268435456")
            }
        }

        val result = migrate(ds)
        observer?.event(
            com.hebe.api.ObserverEvent
                .MemoryDbReady(result.version, result.applied),
        )

        return Db(ds, isInMemory = false)
    }

    fun openInMemory(): Db {
        val ds = org.sqlite.SQLiteDataSource()
        ds.url = "jdbc:sqlite::memory:"

        ds.connection.use { conn ->
            tryLoadVecExtension(conn)
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA journal_mode=WAL")
            }
        }

        migrate(ds)
        return Db(ds, isInMemory = true)
    }

    @Suppress("detekt:TooGenericExceptionCaught")
    private fun tryLoadVecExtension(conn: java.sql.Connection) {
        try {
            SqliteVecExtension.load(conn)
        } catch (e: Exception) {
            System.err.println(
                "Warning: failed to load sqlite-vec extension: ${e.message}. Vector search will be disabled.",
            )
        }
    }
}

fun migrate(ds: DataSource): MigrationResult {
    val flyway =
        Flyway
            .configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .load()
    val result = flyway.migrate()
    val flywayVersion = result.targetSchemaVersion

    @Suppress("RedundantCallOfConversionMethod")
    val version: String? = flywayVersion?.toString()
    return MigrationResult(
        version = version,
        applied = result.migrationsExecuted,
    )
}
