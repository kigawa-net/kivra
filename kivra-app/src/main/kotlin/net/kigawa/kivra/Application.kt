package net.kigawa.kivra

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import net.kigawa.kivra.config.KivraConfig
import net.kigawa.kivra.db.connectDatabase
import net.kigawa.kivra.db.createDataSource
import net.kigawa.kivra.db.runMigrations
import net.kigawa.kivra.routes.configureHealthRoutes

fun Application.module() {
    val config = KivraConfig.from(environment.config)
    val dataSource = createDataSource(config.database)
    runMigrations(dataSource)
    connectDatabase(dataSource)

    install(ContentNegotiation) { json() }
    routing { configureHealthRoutes() }
}
