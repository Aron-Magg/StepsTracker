package com.stepstracker

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection

class Database(config: AppConfig) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.dbUser
        password = config.dbPassword
        maximumPoolSize = 10
        isAutoCommit = true
        validate()
    })

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    fun <T> query(block: (Connection) -> T): T = dataSource.connection.use(block)

    fun <T> transaction(block: (Connection) -> T): T = query { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = dataSource.close()
}

