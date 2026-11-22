package dev.raggedlib.cancel

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.RowMapper
import java.util.concurrent.CancellationException

/** Work stopped because its [Cancellation] was cancelled: the client gave up on the request it was for. */
class RequestCancelledException : CancellationException("cancelled: the client gave up on the request")

/**
 * Lets work that takes a while stop once nobody wants its result any more (see [RequestCancellations]). Cooperative:
 * long loops call [throwIfCancelled] between steps, and blocking calls that can be stopped from outside, such as a
 * running Postgres statement ([cancellableQuery]), register [onCancel] for as long as they run.
 *
 * The one that applies is [current]: the request's own while a request that can be cancelled is handled, or the one
 * [bind] set for a computation, else [NONE].
 */
class Cancellation private constructor(private val cancellable: Boolean) {
    constructor() : this(true)

    @Volatile
    var isCancelled = false
        private set

    // guarded by this; run in the order registered
    private val callbacks = LinkedHashSet<Registration>()

    private inner class Registration(val callback: () -> Unit) : AutoCloseable {
        override fun close() {
            synchronized(this@Cancellation) { callbacks.remove(this) }
        }
    }

    /** From now on [isCancelled], and every [onCancel] callback still registered runs, once, on this thread. */
    fun cancel() {
        check(cancellable) { "Cancellation.NONE can't be cancelled" }
        val registered = synchronized(this) {
            if (isCancelled) return
            isCancelled = true
            callbacks.toList().also { callbacks.clear() }
        }
        for (r in registered) {
            try {
                r.callback()
            } catch (e: Exception) {
                log.warn("A cancellation callback failed", e)
            }
        }
    }

    /** @throws RequestCancelledException once [cancel]led */
    fun throwIfCancelled() {
        if (isCancelled) throw RequestCancelledException()
    }

    /**
     * Runs [callback] when this is cancelled, or right away if it already is, unless the returned registration has
     * been closed by then. Close it once whatever [callback] would stop is over.
     */
    fun onCancel(callback: () -> Unit): AutoCloseable {
        if (!cancellable) return NOTHING
        synchronized(this) {
            if (!isCancelled) return Registration(callback).also { callbacks += it }
        }
        callback()
        return NOTHING
    }

    /** Runs [block] with this as the [current] cancellation (not on threads it hands work to: see [Parallel]). */
    fun <T> bind(block: () -> T): T = ScopedValue.where(CURRENT, this).call(ScopedValue.CallableOp<T, RuntimeException> { block() })

    companion object {
        private val log = LoggerFactory.getLogger(Cancellation::class.java)
        private val CURRENT: ScopedValue<Cancellation> = ScopedValue.newInstance()
        private val NOTHING = AutoCloseable {}

        /** Never cancelled: for work no request can give up on. */
        val NONE = Cancellation(cancellable = false)

        /** The cancellation of the work this thread is doing (see [bind]), or [NONE]. */
        fun current(): Cancellation = if (CURRENT.isBound) CURRENT.get() else NONE
    }
}

/**
 * [JdbcTemplate.query] for a query a request may give up on: if [Cancellation.current] is cancelled while it runs,
 * Postgres is told to stop it (`Statement.cancel`), rather than finishing it for no one while it holds a connection.
 *
 * @throws RequestCancelledException if it was cancelled before or while it ran
 */
fun <T> JdbcTemplate.cancellableQuery(sql: String, mapper: RowMapper<T>, vararg args: Any?): List<T> =
    cancellable(sql, args) { query(it, mapper) }

/** [cancellableQuery], handing each row to [handler]. */
fun JdbcTemplate.cancellableQueryEach(sql: String, handler: RowCallbackHandler, vararg args: Any?): Unit =
    cancellable(sql, args) { query(it, handler) }

private fun <T> cancellable(sql: String, args: Array<out Any?>, run: (PreparedStatementCreator) -> T): T {
    val cancellation = Cancellation.current()
    cancellation.throwIfCancelled()
    var registration: AutoCloseable? = null
    try {
        val statement = PreparedStatementCreator { con ->
            // cancelled while waiting for a connection: the callback below would come too early to stop anything
            cancellation.throwIfCancelled()
            con.prepareStatement(sql).also { ps ->
                ArgumentPreparedStatementSetter(args).setValues(ps)
                // the driver cancels only while the statement runs, never one run later on the same connection
                registration = cancellation.onCancel { ps.cancel() }
            }
        }
        return run(statement).also { cancellation.throwIfCancelled() }
    } catch (e: DataAccessException) {
        cancellation.throwIfCancelled() // Postgres stopped it because it was cancelled: say that, not "query failed"
        throw e
    } finally {
        registration?.close()
    }
}
