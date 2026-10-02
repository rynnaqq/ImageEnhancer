package dev.localphoto.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class NativeModelGateTest {
    @Test
    fun secondLeaseWaitsUntilFirstLeaseCloses() {
        val gate = NativeModelGate()
        val first = gate.acquire()
        val waiterStarted = CountDownLatch(1)
        val waiterAcquired = CountDownLatch(1)
        val waiter = Thread {
            waiterStarted.countDown()
            gate.acquire().use { waiterAcquired.countDown() }
        }

        waiter.start()
        try {
            assertTrue(waiterStarted.await(1, TimeUnit.SECONDS))
            assertFalse("second lease must remain queued", waiterAcquired.await(200, TimeUnit.MILLISECONDS))
            first.close()
            assertTrue("second lease must acquire after release", waiterAcquired.await(1, TimeUnit.SECONDS))
        } finally {
            first.close()
            waiter.join(1_000)
        }
        assertFalse("waiter thread must finish", waiter.isAlive)
    }

    @Test
    fun cancelledWaiterStopsWithoutReleasingCurrentOwnersLease() {
        val gate = NativeModelGate()
        val first = gate.acquire()
        val cancelled = AtomicBoolean(false)
        val waiterChecked = CountDownLatch(1)
        val waiterFinished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val expected = GateCancelled()
        val waiter = Thread {
            try {
                gate.acquire {
                    waiterChecked.countDown()
                    if (cancelled.get()) throw expected
                }
            } catch (caught: Throwable) {
                failure.set(caught)
            } finally {
                waiterFinished.countDown()
            }
        }
        val probeAcquired = CountDownLatch(1)
        val probe = Thread { gate.acquire().use { probeAcquired.countDown() } }

        waiter.start()
        try {
            assertTrue(waiterChecked.await(1, TimeUnit.SECONDS))
            cancelled.set(true)
            assertTrue("cancelled waiter must stop after the bounded poll", waiterFinished.await(500, TimeUnit.MILLISECONDS))
            assertSame(expected, failure.get())

            probe.start()
            assertFalse("cancellation must not release another owner's permit", probeAcquired.await(200, TimeUnit.MILLISECONDS))
            first.close()
            assertTrue(probeAcquired.await(1, TimeUnit.SECONDS))
        } finally {
            first.close()
            waiter.join(1_000)
            if (probe.state != Thread.State.NEW) probe.join(1_000)
        }
        assertFalse(waiter.isAlive)
        assertFalse(probe.isAlive)
    }

    @Test
    fun closingLeaseTwiceCannotAdmitTwoConcurrentOwners() {
        val gate = NativeModelGate()
        val first = gate.acquire()
        first.close()
        first.close()

        val currentOwner = gate.acquire()
        val nextAcquired = CountDownLatch(1)
        val nextOwner = Thread { gate.acquire().use { nextAcquired.countDown() } }
        nextOwner.start()
        try {
            assertFalse("double-close must not add a second permit", nextAcquired.await(200, TimeUnit.MILLISECONDS))
            currentOwner.close()
            assertTrue(nextAcquired.await(1, TimeUnit.SECONDS))
        } finally {
            currentOwner.close()
            nextOwner.join(1_000)
        }
        assertFalse(nextOwner.isAlive)
    }

    private class GateCancelled : RuntimeException()
}
