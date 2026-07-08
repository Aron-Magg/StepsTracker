package com.stepstracker

data class AppConfig(
    val jdbcUrl: String = env("JDBC_URL", "jdbc:postgresql://localhost:5432/stepstracker"),
    val dbUser: String = env("POSTGRES_USER", "stepstracker"),
    val dbPassword: String = env("POSTGRES_PASSWORD", "stepstracker"),
    val jwtSecret: String = env("JWT_SECRET", "development-secret-change-me-32-bytes"),
    val accessMinutes: Long = env("ACCESS_TOKEN_MINUTES", "15").toLong(),
    val refreshDays: Long = env("REFRESH_TOKEN_DAYS", "30").toLong(),
    val seedDemoUser: Boolean = env("SEED_DEMO_USER", "true").toBooleanStrictOrNull() ?: true,
)

private fun env(name: String, fallback: String) = System.getenv(name) ?: fallback
