package org.apache.openejb.threads.future;

import jakarta.enterprise.concurrent.SkippedException;
import org.apache.openejb.threads.task.TriggerTask;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Delegates isDone calls to TriggerTask and throws SkippedExceptions in get methods if task execution has been skipped
 * @param <V>
 */
public class CUTriggerScheduledFuture<V> extends CUScheduledFuture<V> {
    public CUTriggerScheduledFuture(ScheduledFuture<V> delegate, TriggerTask<V> task) {
        super(delegate, task);
    }

    @Override
    public boolean isDone() {
        return super.isDone() && ((TriggerTask<V>) listener).isDone();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        ((TriggerTask<V>) listener).cancelScheduling();
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
        V result = super.get();
        if (((TriggerTask<V>) listener).isSkipped()) {
            throw new SkippedException();
        }

        return result;
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        V result = super.get(timeout, unit);
        if (((TriggerTask<V>) listener).isSkipped()) {
            throw new SkippedException();
        }

        return result;
    }
}
