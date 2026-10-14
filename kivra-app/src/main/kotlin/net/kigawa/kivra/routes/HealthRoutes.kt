package net.kigawa.kivra.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import javax.sql.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/** Upper bound for one `SELECT 1` check of `/ready`. */
const val READY_TIMEOUT_MILLIS: Long = 2_000L

/** How long one check result (success or failure) is reused, so probes do not turn into DB load. */
const val READY_CACHE_MILLIS: Long = 3_000L

/** A failed check is logged at most once per this interval. */
private const val READY_LOG_INTERVAL_NANOS: Long = 60_000_000_000L

/**
 * liveness と readiness は役割が違うので分ける。
 *
 * - `GET /health`（liveness）は DB に触れず、常に 200 を返す。プロセスが生きて HTTP に応答できるかだけを見る。
 *   liveness が DB に依存すると、DB 障害のたびに kubelet がアプリを再起動し続けて悪化するため、この挙動は変えない。
 * - `GET /ready`（readiness）は DB に `SELECT 1` を発行し、成功なら 200、失敗・タイムアウトなら 503 を返す。
 *   DB が使えないときに Pod をトラフィックから外すために使う。
 *
 * 負荷対策: 結果は [READY_CACHE_MILLIS] の間キャッシュし、同時に来た要求は [Mutex] で 1 本の確認に束ねる。
 * 確認は [Dispatchers.IO] 上で行い、タイムアウトは [READY_TIMEOUT_MILLIS] で打ち切る（呼び出し側のコルーチンは
 * ブロックしない）。失敗時のログには、例外のクラス名だけを WARN で出し、同じ理由は 1 分に 1 回までにする。
 * 接続先 URL・ユーザー名・パスワード・例外メッセージは本文にもログにも出さない。認証は不要（`/health` と同じ扱い）。
 */
fun Route.configureHealthRoutes() {
    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}

/** Registers `GET /ready` (readiness, DB check). See [configureHealthRoutes] for the liveness/readiness split. */
fun Route.configureReadyRoutes(readiness: ReadinessChecker) {
    get("/ready") {
        if (readiness.isReady()) {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ready"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "not_ready"))
        }
    }
}

/**
 * Runs `SELECT 1` against [dataSource] with a timeout, caching the result. [nanoTime] and the timeouts are injectable
 * for tests. Callers never wait on a blocking JDBC call: the probe runs on [Dispatchers.IO] in [scope], and the
 * caller only suspends until [timeoutMillis] elapses.
 */
class ReadinessChecker(
    private val dataSource: DataSource,
    private val nanoTime: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = READY_TIMEOUT_MILLIS,
    private val cacheMillis: Long = READY_CACHE_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val logger = LoggerFactory.getLogger(ReadinessChecker::class.java)
    private val mutex = Mutex()

    // Guarded by [mutex].
    private var cachedReady: Boolean? = null
    private var cachedAtNanos: Long = 0L
    private var lastLogNanos: Long? = null

    /** Returns the cached result if it is fresh; otherwise one check runs while concurrent callers wait for it. */
    suspend fun isReady(): Boolean = mutex.withLock {
        val now = nanoTime()
        val cached = cachedReady
        if (cached != null && now - cachedAtNanos < cacheMillis * 1_000_000L) {
            return@withLock cached
        }
        val ready = probe()
        cachedReady = ready
        cachedAtNanos = nanoTime()
        ready
    }

    private suspend fun probe(): Boolean {
        val deferred = scope.async { select1() }
        val result = withTimeoutOrNull(timeoutMillis) { deferred.await() }
        if (result == null) {
            deferred.cancel()
            logFailure("ReadinessTimeout")
            return false
        }
        return result.fold(
            onSuccess = { true },
            onFailure = { e ->
                logFailure(e::class.simpleName ?: "Unknown")
                false
            },
        )
    }

    /** Blocking JDBC call; runs only on [Dispatchers.IO]. The statement timeout also bounds the query in the driver. */
    private fun select1(): Result<Unit> = runCatching {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = maxOf(1L, (timeoutMillis + 999L) / 1_000L).toInt()
                statement.executeQuery("SELECT 1").use { rs -> check(rs.next()) { "SELECT 1 returned no row" } }
            }
        }
    }

    /** Logs only the exception class name, at WARN, at most once per [READY_LOG_INTERVAL_NANOS]. Runs under [mutex]. */
    private fun logFailure(reason: String) {
        val now = nanoTime()
        val last = lastLogNanos
        if (last != null && now - last < READY_LOG_INTERVAL_NANOS) return
        lastLogNanos = now
        logger.warn("readiness check failed: reason={}", reason)
    }
}
