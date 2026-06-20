package com.contextsolutions.localagent.job

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.contextsolutions.localagent.conversation.SqlDelightConversationRepository
import com.contextsolutions.localagent.db.LocalAgentDatabase
import com.contextsolutions.localagent.sync.LocalChangeBus
import com.contextsolutions.localagent.telemetry.NoOpTelemetryCounters
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cancel-running-job (this PR): [JobService.cancel] interrupts the in-flight run
 * tracked in its registry, which kills the subprocess and records the run
 * CANCELLED; cancelling an id with no in-flight run returns false. Uses `sleep`,
 * so it's POSIX-only (skips on Windows).
 */
class JobServiceCancelTest {

    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test
    fun cancelStopsRunningJobAndRecordsCancelled() = runBlocking {
        if (isWindows) return@runBlocking // `sleep`/`sh` argv form is POSIX-only.

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LocalAgentDatabase.Schema.create(driver)
        val db = LocalAgentDatabase(driver)
        val jobs = SqlDelightJobRepository(db.jobsQueries, LocalChangeBus())
        val conversations = SqlDelightConversationRepository(db.conversationsQueries, NoOpTelemetryCounters)
        val executor = JobExecutor(jobs = jobs, conversations = conversations)

        lateinit var service: JobService
        val scheduler = DesktopJobScheduler(jobServiceProvider = { service })
        service = JobService(repository = jobs, scheduler = scheduler, executor = executor)

        // Cancelling when nothing is in flight is a no-op (false).
        assertFalse(service.cancel("nope"), "cancel of an unknown id returns false")

        val job = Job(
            id = "job-sleep", name = "sleeper", command = "sleep", prompt = "30",
            workingDir = null, scheduleType = JobScheduleType.ONE_SHOT, cronExpression = null,
            fireAtEpochMs = 1, paused = false, createdAtEpochMs = 1, updatedAtEpochMs = 1,
            deletedAtEpochMs = null, lastRunStatus = null, lastRunAtEpochMs = null,
            lastRunSummary = null, lastRunConversationId = null,
        )
        jobs.create(job)

        service.runNow("job-sleep")
        awaitStatus(jobs, "job-sleep", JobRunStatus.RUNNING)

        assertTrue(service.cancel("job-sleep"), "cancel of a running id returns true")
        awaitStatus(jobs, "job-sleep", JobRunStatus.CANCELLED)
        assertEquals(JobRunStatus.CANCELLED, jobs.runsForJob("job-sleep").single().status)
    }

    private suspend fun awaitStatus(jobs: JobRepository, id: String, want: JobRunStatus) {
        repeat(100) { // up to ~5s, polling every 50ms
            if (jobs.get(id)?.lastRunStatus == want) return
            delay(50)
        }
        assertEquals(want, jobs.get(id)?.lastRunStatus, "timed out waiting for $want")
    }
}
