/*
 * Copyright 2016-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.experimental.rx1

import kotlinx.coroutines.experimental.*
import rx.Scheduler
import rx.Subscription
import rx.functions.Action0
import rx.subscriptions.Subscriptions
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.CoroutineContext
import kotlin.coroutines.experimental.EmptyCoroutineContext

/**
 * Converts an instance of [Scheduler] to an implementation of [CoroutineDispatcher]
 * and provides native [delay][Delay.delay] support.
 */
public fun Scheduler.asCoroutineDispatcher() = SchedulerCoroutineDispatcher(this)

/**
 * Implements [CoroutineDispatcher] on top of an arbitrary [Scheduler].
 * @param scheduler a scheduler.
 */
public class SchedulerCoroutineDispatcher(private val scheduler: Scheduler) : CoroutineDispatcher(), Delay {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        scheduler.createWorker().schedule { block.run() }
    }

    override fun scheduleResumeAfterDelay(time: Long, unit: TimeUnit, continuation: CancellableContinuation<Unit>) =
            scheduler.createWorker()
                    .schedule({
                        with(continuation) { resumeUndispatched(Unit) }
                    }, time, unit)
                    .let { subscription ->
                        continuation.unsubscribeOnCancellation(subscription)
                    }

    override fun invokeOnTimeout(time: Long, unit: TimeUnit, block: Runnable): DisposableHandle =
            scheduler.createWorker().schedule({ block.run() }, time, unit).asDisposableHandle()

    private fun Subscription.asDisposableHandle(): DisposableHandle = object : DisposableHandle {
        override fun dispose() = unsubscribe()
    }

    override fun toString(): String = scheduler.toString()
    override fun equals(other: Any?): Boolean = other is SchedulerCoroutineDispatcher && other.scheduler === scheduler
    override fun hashCode(): Int = System.identityHashCode(scheduler)
}

public fun CoroutineDispatcher.asRxScheduler() = CoroutineDispatherScheduler(this)

public class CoroutineDispatherScheduler(private val dispatcher: CoroutineDispatcher) : Scheduler {

    override fun createWorker(): Worker = CoroutineDispatchedWorker(dispatcher)

    internal inner class CoroutineDispatchedWorker(private val dispatcher: CoroutineDispatcher) : Scheduler.Worker() {

        @Volatile
        private var _isUnsubscribed = false

        override fun schedule(action: Action0?): Subscription {
            dispatcher.dispatch(EmptyCoroutineContext, Runnable { action?.call() })
            return this
        }

        override fun schedule(action: Action0?, delayTime: Long, unit: TimeUnit?): Subscription {
            if (isUnsubscribed) {
                return Subscriptions.unsubscribed()
            }

            if (dispatcher is Delay) {
                dispatcher.delay(delayTime, unit)
            } else{
                Timer().schedule(object : TimerTask() {schedule(action)}, unit.toMillis(delayTime))

                Thread.sleep(unit.toMillis(delayTime))
            }
                dispatcher.dispatch(EmptyCoroutineContext, Runnable { action?.call() })
                return this

        }

        override fun isUnsubscribed(): Boolean = _isUnsubscribed

        override fun unsubscribe() {
            dispatcher.cancel()
            _isUnsubscribed = true
        }
    }
}

