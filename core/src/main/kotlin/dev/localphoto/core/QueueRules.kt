package dev.localphoto.core

object QueueRules {
    fun canTransition(from: ProjectStatus, to: ProjectStatus): Boolean = when (from) {
        ProjectStatus.DRAFT -> to == ProjectStatus.QUEUED || to == ProjectStatus.CANCELLED
        ProjectStatus.QUEUED -> to == ProjectStatus.PROCESSING || to == ProjectStatus.CANCELLED
        ProjectStatus.PROCESSING -> to == ProjectStatus.COMPLETED || to == ProjectStatus.CANCELLED ||
            to == ProjectStatus.FAILED || to == ProjectStatus.INTERRUPTED
        ProjectStatus.FAILED -> to == ProjectStatus.QUEUED
        ProjectStatus.CANCELLED -> to == ProjectStatus.QUEUED
        ProjectStatus.INTERRUPTED -> to == ProjectStatus.QUEUED || to == ProjectStatus.PROCESSING ||
            to == ProjectStatus.CANCELLED || to == ProjectStatus.FAILED
        ProjectStatus.COMPLETED -> false
    }

    fun nextRunnable(statuses: List<ProjectStatus>): Int? {
        val index = statuses.indexOfFirst { it == ProjectStatus.QUEUED }
        return index.takeIf { it >= 0 }
    }
}
