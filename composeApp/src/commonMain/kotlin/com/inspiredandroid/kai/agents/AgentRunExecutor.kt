package com.inspiredandroid.kai.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reference executor that enforces the run-cancellation contract documented
 * on [AgentRun].
 *
 * Why this exists: `AgentRuntime.kt` only holds the run data model — any
 * feature that executes an agent run should do it through this executor
 * (never with a raw `scope.launch { ... }`), so that:
 *
 * - `CancellationException` always propagates and the run ends up in
 *   [RunStatus.Cancelled] — never stuck in Running.
 * - Any other Throwable ends the run in [RunStatus.Failed] with a way for
 *   the caller to record an Error step, so the UI never shows a live run
 *   whose executor is already dead.
 *
 * Callers wire the persistence hooks when creating the executor and supply
 * the actual step loop as [body] — a suspending lambda that owns a
 * cancellable Job because it runs under the executor's scope.
 */
class AgentRunExecutor(
    private val scope: CoroutineScope,
    private val onStatusTransition: (run: AgentRun, status: RunStatus, finishedAt: Long) -> Unit,
    private val onStepUpdate: (run: AgentRun, step: AgentStep) -> Unit,
    private val onRunFinished: (run: AgentRun) -> Unit,
    private val onFailure: (run: AgentRun, failure: Throwable) -> Unit = { _, _ -> },
) {

    /** Handle given to the body so it can drive the run itself. */
    class RunHandle(val run: AgentRun) {

        /** Marks a step done/failed/rejected with a finishedAt timestamp. */
        fun completeStep(step: AgentStep, status: StepStatus): AgentStep =
            step.copy(
                status = status,
                finishedAt = System.currentTimeMillis(),
                durationMs = System.currentTimeMillis() - step.startedAt,
            )
    }

    /**
     * Launches [run] with [body] as the step loop. The returned [Job] is the
     * *single* object callers use to cancel the run.
     */
    fun run(run: AgentRun, body: suspend RunHandle.() -> RunStatus): Job {
        val handle = RunHandle(run)
        val job = scope.launch {
            var terminalStatus = RunStatus.Failed
            try {
                onStatusTransition(run, RunStatus.Running, System.currentTimeMillis())
                terminalStatus = handle.body()
                require(terminalStatus in TERMINAL_STATUSES) {
                    "Run body must return a terminal status, got $terminalStatus"
                }
            } catch (ce: CancellationException) {
                terminalStatus = RunStatus.Cancelled
                throw ce
            } catch (t: Throwable) {
                terminalStatus = RunStatus.Failed
                onFailure(run, t)
            } finally {
                onStatusTransition(run, terminalStatus, System.currentTimeMillis())
                onRunFinished(run)
            }
        }
        return job
    }

    companion object {
        private val TERMINAL_STATUSES = setOf(RunStatus.Completed, RunStatus.Failed, RunStatus.Cancelled)
    }
}
