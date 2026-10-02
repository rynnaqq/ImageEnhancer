package dev.localphoto.core

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NativeModelGate {
    private val semaphore = Semaphore(1, true)

    fun acquire(check: () -> Unit = {}): AutoCloseable {
        while (true) {
            check()
            val acquired = try {
                semaphore.tryAcquire(POLL_MILLISECONDS, TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw failure
            }
            if (!acquired) continue
            try {
                check()
            } catch (failure: Throwable) {
                semaphore.release()
                throw failure
            }
            return Lease(semaphore)
        }
    }

    private class Lease(private val semaphore: Semaphore) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) semaphore.release()
        }
    }

    private companion object {
        const val POLL_MILLISECONDS = 100L
    }
}
