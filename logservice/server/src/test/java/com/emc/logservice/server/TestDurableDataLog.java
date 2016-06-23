package com.emc.logservice.server;

import com.emc.logservice.common.CloseableIterator;
import com.emc.logservice.storageabstraction.DurableDataLog;
import com.emc.logservice.storageabstraction.DurableDataLogException;
import com.emc.logservice.storageabstraction.mocks.InMemoryDurableDataLogFactory;
import com.emc.nautilus.testcommon.ErrorInjector;
import com.google.common.base.Preconditions;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.emc.nautilus.testcommon.ErrorInjector.throwAsyncExceptionIfNeeded;

/**
 * Test DurableDataLog. Wraps around an existing DurableDataLog, and allows controlling behavior for each method, such
 * as injecting errors, simulating non-availability, etc.
 */
public class TestDurableDataLog implements DurableDataLog {
    //region Members

    private final DurableDataLog wrappedLog;
    private ErrorInjector<Exception> appendSyncErrorInjector;
    private ErrorInjector<Exception> appendAsyncErrorInjector;
    private ErrorInjector<Exception> getReaderInitialErrorInjector;
    private ErrorInjector<Exception> readSyncErrorInjector;

    //endregion

    //region Constructor

    private TestDurableDataLog(DurableDataLog wrappedLog) {
        Preconditions.checkNotNull(wrappedLog, "wrappedLog");
        this.wrappedLog = wrappedLog;
    }

    //endregion

    //region AutoCloseable Implementation

    @Override
    public void close() {
        this.wrappedLog.close();
    }

    //endregion

    //region DurableDataLog Implementation

    @Override
    public CompletableFuture<Void> initialize(Duration timeout) {
        return this.wrappedLog.initialize(timeout);
    }

    @Override
    public CompletableFuture<Long> append(InputStream data, Duration timeout) {
        ErrorInjector.throwSyncExceptionIfNeeded(this.appendSyncErrorInjector);
        return throwAsyncExceptionIfNeeded(this.appendAsyncErrorInjector)
                .thenCompose(v -> this.wrappedLog.append(data, timeout));
    }

    @Override
    public CompletableFuture<Void> truncate(long upToSequence, Duration timeout) {
        return this.wrappedLog.truncate(upToSequence, timeout);
    }

    @Override
    public CloseableIterator<ReadItem, DurableDataLogException> getReader(long afterSequence) throws DurableDataLogException {
        ErrorInjector.throwSyncExceptionIfNeeded(this.getReaderInitialErrorInjector);
        return new CloseableIteratorWrapper(this.wrappedLog.getReader(afterSequence), this.readSyncErrorInjector);
    }

    @Override
    public int getMaxAppendLength() {
        return this.wrappedLog.getMaxAppendLength();
    }

    @Override
    public long getLastAppendSequence() {
        return this.wrappedLog.getLastAppendSequence();
    }

    //endregion

    //region Test Helper Methods

    /**
     * Sets the ErrorInjectors for append exceptions.
     *
     * @param syncInjector  An ErrorInjector to throw sync exceptions. If null, no sync exceptions will be thrown.
     * @param asyncInjector An ErrorInjector to throw async exceptions (wrapped in CompletableFutures). If null, no async
     *                      exceptions will be thrown (from this wrapper).
     */
    public void setAppendErrorInjectors(ErrorInjector<Exception> syncInjector, ErrorInjector<Exception> asyncInjector) {
        this.appendSyncErrorInjector = syncInjector;
        this.appendAsyncErrorInjector = asyncInjector;
    }

    /**
     * Sets the ErrorInjectors for the read operation.
     *
     * @param getReaderInjector An ErrorInjector to throw sync exceptions during calls to getReader. If null, no exceptions
     *                          will be thrown when calling getReader.
     * @param readErrorInjector An ErrorInjector to throw sync exceptions during calls to getNext() from the iterator
     *                          returned by getReader. If null, no sync exceptions will be thrown.
     */
    public void setReadErrorInjectors(ErrorInjector<Exception> getReaderInjector, ErrorInjector<Exception> readErrorInjector) {
        this.getReaderInitialErrorInjector = getReaderInjector;
        this.readSyncErrorInjector = readErrorInjector;
    }

    /**
     * Retrieves all the entries from the DurableDataLog and converts them to the desired type.
     *
     * @param converter
     * @param <T>
     * @return
     * @throws DurableDataLogException
     */
    public <T> List<T> getAllEntries(FunctionWithException<ReadItem, T> converter) throws Exception {
        ArrayList<T> result = new ArrayList<>();
        CloseableIterator<ReadItem, DurableDataLogException> reader = this.wrappedLog.getReader(-1);
        while (true) {
            DurableDataLog.ReadItem readItem = reader.getNext();
            if (readItem == null) {
                break;
            }

            result.add(converter.apply(readItem));
        }

        return result;
    }

    //endregion

    //region Factory

    /**
     * Creates a new TestDurableDataLog backed by an InMemoryDurableDataLog.
     *
     * @param containerId   The Id of the container.
     * @param maxAppendSize The maximum append size for the log.
     * @return The newly created log.
     */
    public static TestDurableDataLog create(String containerId, int maxAppendSize) {
        try (InMemoryDurableDataLogFactory factory = new InMemoryDurableDataLogFactory(maxAppendSize)) {
            DurableDataLog log = factory.createDurableDataLog(containerId);
            return new TestDurableDataLog(log);
        }
    }

    //endregion

    public interface FunctionWithException<T, R> {
        R apply(T var1) throws Exception;
    }

    private static class CloseableIteratorWrapper implements CloseableIterator<ReadItem, DurableDataLogException> {
        private final CloseableIterator<ReadItem, DurableDataLogException> innerIterator;
        private final ErrorInjector<Exception> syncErrorInjector;

        public CloseableIteratorWrapper(CloseableIterator<ReadItem, DurableDataLogException> innerIterator, ErrorInjector<Exception> syncErrorInjector) {
            assert innerIterator != null;
            this.innerIterator = innerIterator;
            this.syncErrorInjector = syncErrorInjector;
        }

        @Override
        public ReadItem getNext() throws DurableDataLogException {
            ErrorInjector.throwSyncExceptionIfNeeded(syncErrorInjector);
            return this.innerIterator.getNext();
        }

        @Override
        public void close() {
            this.innerIterator.close();
        }
    }
}
