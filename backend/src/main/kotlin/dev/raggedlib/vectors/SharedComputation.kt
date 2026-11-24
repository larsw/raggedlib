package dev.raggedlib.vectors

import dev.raggedlib.cancel.Cancellation
import dev.raggedlib.cancel.RequestCancelledException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * One computation that several requests wait for: the request that starts it [run]s it, the others that [share] it
 * [await] it. A request that is cancelled ([Cancellation.current]) stops waiting, and once every request waiting has
 * been, the computation is cancelled too: it runs with a [Cancellation] of its own, current, so the work it does
 * (queries included) stops once nobody wants it. Nested ones (a layout waiting for a sample) count the same way:
 * the inner one sees the outer one's [Cancellation] as its request's.
 */
internal class SharedComputation<T> {
    private val result = CompletableFuture<T>()

    /** The computation's own, cancelled when the last request waiting for it is. */
    private val cancellation = Cancellation()

    // guarded by this: requests waiting for the result, the one computing it included
    private var waiting = 1
    private var abandoned = false

    /** Finished, successfully or not. */
    val isDone: Boolean get() = result.isDone

    /** Its result if it finished successfully, else null. */
    fun valueNow(): T? = if (result.isDone && !result.isCompletedExceptionally) result.join() else null

    /** Whether a request can have this: done, or still being computed (then it waits for it too, see [leave]). */
    @Synchronized
    fun share(): Boolean = when {
        result.isCompletedExceptionally -> false
        result.isDone -> true
        abandoned -> false
        else -> {
            waiting++
            true
        }
    }

    /** A request waiting for it was cancelled; the last one to go cancels the computation. */
    private fun leave() {
        val last = synchronized(this) {
            waiting--
            (waiting == 0 && !result.isDone).also { if (it) abandoned = true }
        }
        if (last) cancellation.cancel()
    }

    /**
     * Computes it, by the request that created it, for every request that comes to wait for it too: so under its own
     * cancellation, not this request's; this request being cancelled only means one fewer waiting. A failure is
     * passed on to whoever waits, and thrown.
     */
    fun run(compute: () -> T): T {
        Cancellation.current().onCancel(::leave).use {
            try {
                return cancellation.bind {
                    cancellation.throwIfCancelled() // this request already was, and no other waits yet
                    compute()
                }.also { result.complete(it) }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
                throw e
            }
        }
    }

    /**
     * The result, for a request that [share]d it, once computed; given up on with [RequestCancelledException] if
     * this request is cancelled.
     */
    fun await(): T {
        // a copy to stop waiting on, without stopping the computation itself for the others
        val copy = result.copy()
        val registration = Cancellation.current().onCancel {
            if (copy.cancel(false)) leave()
        }
        registration.use {
            try {
                return copy.join()
            } catch (_: CancellationException) {
                throw RequestCancelledException() // the copy was cancelled, by this request (a failure comes as below)
            } catch (e: CompletionException) {
                throw e.cause ?: e
            }
        }
    }
}
