package com.stepstracker

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

fun main() {
    embeddedServer(Netty, port = (System.getenv("PORT") ?: "8080").toInt(), host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig()) {
    require(config.jwtSecret.length >= 32) { "JWT_SECRET must contain at least 32 characters" }
    val database = Database(config)
    val security = Security(config)
    val repository = Repository(database, security, config)
    if(config.seedDemoUser) repository.seedDemoUser()
    val logger = log
    environment.monitor.subscribe(ApplicationStopped) { database.close() }

    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false; explicitNulls = false }) }
    install(CallLogging) { level = Level.INFO; filter { !it.request.path().startsWith("/api/v1/auth") } }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("INVALID_REQUEST", e.message ?: "Invalid request")) }
        exception<IllegalStateException> { call, e -> call.respond(HttpStatusCode.Conflict, ErrorResponse("INVALID_STATE", e.message ?: "Invalid state")) }
        exception<SQLException> { call, e ->
            if (e.sqlState == "23505") call.respond(HttpStatusCode.Conflict, ErrorResponse("ALREADY_EXISTS", "Resource already exists"))
            else { logger.error("Database operation failed", e); call.respond(HttpStatusCode.InternalServerError, ErrorResponse("DATABASE_ERROR", "Database operation failed")) }
        }
        exception<Throwable> { call, e -> logger.error("Unhandled request failure", e); call.respond(HttpStatusCode.InternalServerError, ErrorResponse("INTERNAL_ERROR", "Unexpected server error")) }
    }
    install(Authentication) {
        jwt("auth-jwt") {
            realm = "stepstracker"
            verifier(security.verifier())
            validate { credential -> credential.payload.subject?.let { JWTPrincipal(credential.payload) } }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("UNAUTHORIZED", "Access token missing or invalid")) }
        }
    }
    install(RateLimit) {
        register(RateLimitName("auth")) { rateLimiter(limit = 20, refillPeriod = 1.minutes) }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        rateLimit(RateLimitName("auth")) { route("/api/v1/auth") {
            post("/register") {
                val input = call.receive<RegisterRequest>()
                val email = input.email.trim().lowercase()
                require(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email)) { "Invalid email" }
                require(input.password.length >= 10) { "Password must contain at least 10 characters" }
                call.respond(HttpStatusCode.Created, repository.issueTokens(repository.createUser(email, security.hashPassword(input.password)).id))
            }
            post("/login") {
                val input = call.receive<LoginRequest>()
                val user = repository.userByEmail(input.email.trim().lowercase())
                if (user == null || !security.verifyPassword(user.passwordHash, input.password)) call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_CREDENTIALS", "Email or password is invalid"))
                else call.respond(repository.issueTokens(user.id))
            }
            post("/refresh") {
                val result = repository.rotateToken(call.receive<RefreshRequest>().refreshToken)
                if (result == null) call.respond(HttpStatusCode.Unauthorized, ErrorResponse("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired")) else call.respond(result.second)
            }
            post("/logout") { repository.revoke(call.receive<LogoutRequest>().refreshToken); call.respond(HttpStatusCode.NoContent) }
        } }
        authenticate("auth-jwt") {
            route("/api/v1") {
                route("/me") {
                    get { val id=call.userId(); val user=repository.userById(id)!!; call.respond(MeResponse(id.toString(),user.email,repository.profile(id))) }
                    get("/weight-history") { call.respond(repository.weightHistory(call.userId())) }
                    put("/profile") {
                        val input=call.receive<ProfileRequest>(); validateProfile(input); repository.saveProfile(call.userId(),input); call.respond(repository.profile(call.userId())!!)
                    }
                    delete { repository.deleteUser(call.userId()); call.respond(HttpStatusCode.NoContent) }
                }
                route("/steps") {
                    post("/batch") { call.respond(repository.ingest(call.userId(),call.receive())) }
                    get {
                        val from=Instant.parse(call.request.queryParameters["from"] ?: throw IllegalArgumentException("from is required"))
                        val to=Instant.parse(call.request.queryParameters["to"] ?: throw IllegalArgumentException("to is required"))
                        require(to.isAfter(from)); require(to.epochSecond-from.epochSecond <= 366L*86400)
                        call.respond(repository.steps(call.userId(),from,to))
                    }
                }
                route("/stats") {
                    get("/daily") { val q=call.range(repository); call.respond(repository.daily(call.userId(),q.first,q.second,q.third)) }
                    get("/timeline") { val q=call.range(repository); call.respond(repository.daily(call.userId(),q.first,q.second,q.third)) }
                    get("/time-of-day") { val q=call.range(repository); call.respond(repository.timeOfDay(call.userId(),q.first,q.second,q.third)) }
                    get("/summary") {
                        val q=call.range(repository); val current=repository.daily(call.userId(),q.first,q.second,q.third)
                        val days=q.second.toEpochDay()-q.first.toEpochDay()+1
                        val previous=repository.daily(call.userId(),q.first.minusDays(days),q.first.minusDays(1),q.third)
                        val steps=current.sumOf { it.steps }; val previousSteps=previous.sumOf { it.steps }
                        call.respond(SummaryResponse(steps,current.sumOf { it.distanceMeters },current.sumOf { it.estimatedKcal },steps.toDouble()/days,if(previousSteps==0L)null else (steps-previousSteps)*100.0/previousSteps))
                    }
                }
            }
        }
    }
}

private fun ApplicationCall.userId() = UUID.fromString(principal<JWTPrincipal>()!!.payload.subject)
private fun validateProfile(p: ProfileRequest) {
    require(p.weightKg in 20.0..400.0); require(p.heightCm in 80.0..250.0)
    require(p.sex in setOf("FEMALE","MALE","OTHER")); require(LocalDate.parse(p.birthDate).isBefore(LocalDate.now()))
    ZoneId.of(p.timezone)
}
private fun ApplicationCall.range(repository: Repository): Triple<LocalDate,LocalDate,String> {
    val profile=repository.profile(userId()) ?: throw IllegalStateException("Profile required")
    val to=LocalDate.parse(request.queryParameters["to"] ?: LocalDate.now(ZoneId.of(profile.timezone)).toString())
    val from=LocalDate.parse(request.queryParameters["from"] ?: to.minusDays(6).toString())
    require(!to.isBefore(from) && to.toEpochDay()-from.toEpochDay() <= 366)
    return Triple(from,to,profile.timezone)
}
