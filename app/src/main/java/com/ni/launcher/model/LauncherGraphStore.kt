package com.ni.launcher.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * App-scoped single source of truth for the launcher graph.
 *
 * - [graph] is the published immutable snapshot (StateFlow).
 * - [dispatch] is main-safe and non-suspending; commands go through an unlimited FIFO channel and
 *   are reduced one at a time on [model] (single-threaded, so no locks around the snapshot).
 * - Nothing is reduced before the persister's initial load: commands dispatched earlier stay
 *   queued and are applied, in order, right after [Command.Loaded].
 * - After each effective change: publish first, then `persister.markDirty` (if `cmd.persist`).
 */
class LauncherGraphStore(
    scope: CoroutineScope,
    private val persister: GraphPersister,
    private val model: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** Called on the model thread when reducing a command or the initial load throws. */
    private val onError: (Command?, Throwable) -> Unit = { _, _ -> },
) {
    private sealed interface Msg {
        class Cmd(val cmd: Command) : Msg
        class Barrier(val done: CompletableDeferred<Long>) : Msg
    }

    private val _graph = MutableStateFlow(LauncherGraph.EMPTY)
    val graph: StateFlow<LauncherGraph> = _graph.asStateFlow()

    private val inbox = Channel<Msg>(Channel.UNLIMITED)

    /** -1 until seeded by the first Loaded. Only ever moves up. */
    private val nextId = AtomicLong(-1)

    init {
        scope.launch(model) {
            apply(Command.Loaded(loadWithRetry()))
            for (msg in inbox) {
                when (msg) {
                    is Msg.Cmd -> apply(msg.cmd)
                    is Msg.Barrier -> msg.done.complete(_graph.value.version)
                }
            }
        }
    }

    /** Main-safe, non-suspending. Returns false only if the inbox is closed (never, in practice). */
    fun dispatch(cmd: Command): Boolean = inbox.trySend(Msg.Cmd(cmd)).isSuccess

    /**
     * Allocates a tag id in memory (Launcher3 `generateNewItemId` style), for [Command.Create].
     * Never below 3 and never reused in-process. Thread-safe.
     * @throws IllegalStateException before the graph is loaded (the UI shows nothing until then).
     */
    fun newTagId(): Long {
        while (true) {
            val cur = nextId.get()
            check(cur >= FIRST_USER_TAG_ID) { "newTagId() called before the graph was loaded" }
            if (nextId.compareAndSet(cur, cur + 1)) return cur
        }
    }

    /**
     * Suspends until every command dispatched before this call has been reduced and the resulting
     * snapshot is durable. (A plain `persister.flush(graph.value.version)` would miss commands
     * still queued in the channel, e.g. dispatch(...) immediately followed by flush().)
     */
    suspend fun flush() {
        val done = CompletableDeferred<Long>()
        if (!inbox.trySend(Msg.Barrier(done)).isSuccess) return
        persister.flush(done.await())
    }

    private suspend fun loadWithRetry(): UserData {
        var backoff = 500L
        while (true) {
            try {
                return persister.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(null, e)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun apply(cmd: Command) {
        val old = _graph.value
        val new = try {
            reduce(old, cmd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(cmd, e)
            return
        }
        if (cmd is Command.Loaded || cmd is Command.Create) {
            val seed = new.user.safeNextTagId()
            nextId.accumulateAndGet(seed) { a, b -> maxOf(a, b) }
        }
        if (new === old) return
        _graph.value = new // 1) memory first
        if (cmd.persist) persister.markDirty(old.user, new.user, new.version) // 2) disk later
    }
}
