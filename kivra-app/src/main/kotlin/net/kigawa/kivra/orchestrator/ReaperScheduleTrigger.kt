package net.kigawa.kivra.orchestrator

import net.kigawa.kivra.reaper.Reaper
import net.kigawa.kivra.scheduler.ScheduleTrigger
import net.kigawa.kivra.scheduler.ScheduleTriggerSink
import org.slf4j.LoggerFactory

/**
 * Wraps Reaper for ai-scheduler wake-ups (design #3 section 11, Phase C5).
 *
 * ai-scheduler's cron Job triggers the Reaper to scan for orphaned workspaces.
 * The Reaper itself is idempotent and safe to run concurrently with the internal
 * Reaper loop (if configured).
 */
class ReaperScheduleTrigger(
    private val reaper: Reaper?,
) : ScheduleTriggerSink {

    private val logger = LoggerFactory.getLogger(ReaperScheduleTrigger::class.java)

    /**
     * Handle external wake-up from ai-scheduler.
     * Runs a single Reaper scan cycle.
     */
    override suspend fun onTrigger(trigger: ScheduleTrigger) {
        val reaper = reaper ?: run {
            logger.debug("reaper trigger: no Reaper configured, skipping")
            return
        }
        try {
            logger.info("reaper trigger: job={}, scheduledAt={}", trigger.job.id, trigger.scheduledAt)
            reaper.runOnce()
        } catch (e: Exception) {
            logger.warn("reaper trigger: scan failed, job={} exception={}",
                trigger.job.id, e.javaClass.simpleName)
        }
    }
}
