package com.contextsolutions.localagent.job

/**
 * One planned initialization step shown in the Choose Job dialog (PR #100). The dialog
 * renders the full list up front (all [JobInitStepState.PENDING]) and updates each as the
 * initializer runs, so the user sees what setup the job needs and how far it's gotten.
 */
data class JobInitStepInfo(
    val id: String,
    val title: String,
    val interactive: Boolean,
    val instructions: String,
)

/** Live state of a planned step. */
enum class JobInitStepState { PENDING, RUNNING, AWAITING_USER, DONE, FAILED }

/** A progress update for the step at [index] in the plan, with optional detail [message]. */
data class JobInitProgress(val index: Int, val state: JobInitStepState, val message: String? = null)

/** Terminal outcome of [JobInitializer.initialize] (PR #100). */
sealed interface JobInitResult {
    /** The job has no init block, or it was already initialized for this manifest version. */
    data object AlreadyInitialized : JobInitResult

    /** All steps + verify succeeded; the user may Approve the job. */
    data object Succeeded : JobInitResult

    /**
     * A step or the verify check failed; the job must NOT be saved. [stepTitle] names the
     * failing step and [reason] carries the captured output / error to show the user.
     */
    data class Failed(val stepTitle: String, val reason: String) : JobInitResult
}

/**
 * Runs a job's one-time initialization before it can be saved (PR #100).
 * Desktop-only ([DesktopJobInitializer]); unbound on mobile/iOS.
 */
interface JobInitializer {
    /**
     * The ordered steps [initialize] will run for [entry] (runtime checks, setup commands,
     * interactive steps, verify), so the dialog can show the checklist before starting.
     * Empty when the job needs no initialization.
     */
    suspend fun plan(entry: JobCatalogEntry): List<JobInitStepInfo>

    /**
     * Initialize [entry], reporting per-step progress via [onProgress] (indices match [plan]).
     * Returns [JobInitResult.Succeeded] / [JobInitResult.AlreadyInitialized] (the user may then
     * Approve) or [JobInitResult.Failed] (save blocked). Cancellation-aware: cancelling the
     * caller kills the in-flight subprocess.
     */
    suspend fun initialize(entry: JobCatalogEntry, onProgress: (JobInitProgress) -> Unit): JobInitResult
}
