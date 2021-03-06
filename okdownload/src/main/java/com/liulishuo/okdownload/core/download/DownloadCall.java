/*
 * Copyright (c) 2017 LingoChamp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.liulishuo.okdownload.core.download;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.OkDownload;
import com.liulishuo.okdownload.core.NamedRunnable;
import com.liulishuo.okdownload.core.Util;
import com.liulishuo.okdownload.core.breakpoint.BlockInfo;
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo;
import com.liulishuo.okdownload.core.breakpoint.BreakpointStore;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;
import com.liulishuo.okdownload.core.exception.RetryException;
import com.liulishuo.okdownload.core.file.MultiPointOutputStream;
import com.liulishuo.okdownload.core.file.ProcessFileStrategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DownloadCall extends NamedRunnable implements Comparable<DownloadCall> {
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
            60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
            Util.threadFactory("OkDownload Block", false));

    private static final String TAG = "DownloadCall";

    static final int MAX_COUNT_RETRY_FOR_PRECONDITION_FAILED = 1;
    public final DownloadTask task;
    public final boolean asyncExecuted;
    @NonNull private final ArrayList<DownloadChain> blockChainList;

    @Nullable private volatile DownloadCache cache;
    volatile boolean canceled;
    volatile boolean finishing;


    private DownloadCall(DownloadTask task, boolean asyncExecuted) {
        this(task, asyncExecuted, new ArrayList<DownloadChain>());
    }

    DownloadCall(DownloadTask task, boolean asyncExecuted,
                 @NonNull ArrayList<DownloadChain> runningBlockList) {
        super("download call: " + task.getId());
        this.task = task;
        this.asyncExecuted = asyncExecuted;
        this.blockChainList = runningBlockList;
    }

    public static DownloadCall create(DownloadTask task, boolean asyncExecuted) {
        return new DownloadCall(task, asyncExecuted);
    }

    public boolean cancel() {
        synchronized (this) {
            if (canceled) return false;
            if (finishing) return false;
            this.canceled = true;
        }

        OkDownload.with().downloadDispatcher().flyingCanceled(this);

        final DownloadCache cache = this.cache;
        if (cache != null) cache.setUserCanceled();

        final List<DownloadChain> chains = (List<DownloadChain>) blockChainList.clone();
        for (DownloadChain chain : chains) {
            chain.cancel();
        }

        return true;
    }

    public boolean isCanceled() { return canceled; }

    public boolean isFinishing() { return finishing; }

    @Override
    public void execute() throws InterruptedException {
        boolean retry;
        int retryCount = 0;

        // ready param
        final OkDownload okDownload = OkDownload.with();
        final BreakpointStore store = okDownload.breakpointStore();
        final ProcessFileStrategy fileStrategy = okDownload.processFileStrategy();

        // inspect task start
        inspectTaskStart();
        do {
            if (canceled) break;

            // 1. create basic info if not exist
            @NonNull final BreakpointInfo info;
            try {
                BreakpointInfo infoOnStore = store.get(task.getId());
                if (infoOnStore == null) {
                    info = store.createAndInsert(task);
                } else {
                    info = infoOnStore;
                }
            } catch (IOException e) {
                this.cache = new DownloadCache.PreError(e);
                break;
            }
            if (canceled) break;

            // ready cache.
            @NonNull final DownloadCache cache = createCache(info);
            this.cache = cache;

            // 2. remote check.
            final BreakpointRemoteCheck remoteCheck = createRemoteCheck(info);
            try {
                remoteCheck.check();
            } catch (IOException e) {
                cache.catchException(e);
                break;
            }

            // 3. reuse another info if another info is idle and available for reuse.
            try {
                OkDownload.with().downloadStrategy()
                        .inspectAnotherSameInfo(task, info, remoteCheck.getInstanceLength());
            } catch (RetryException e) {
                cache.catchException(e);
                break;
            }

            if (remoteCheck.isResumable()) {
                //4. local check
                final BreakpointLocalCheck localCheck = createLocalCheck(info);
                localCheck.check();
                if (localCheck.isDirty()) {
                    // 5. assemble block data
                    assembleBlockAndCallbackFromBeginning(info, remoteCheck,
                            localCheck.getCauseOrThrow());
                } else {
                    okDownload.callbackDispatcher().dispatch()
                            .downloadFromBreakpoint(task, info);
                }
            } else {
                // 5. assemble block data
                assembleBlockAndCallbackFromBeginning(info, remoteCheck,
                        remoteCheck.getCauseOrThrow());
            }

            // 6. start with cache and info.
            start(cache, info);

            if (canceled) break;

            // 7. retry if precondition failed.
            if (cache.isPreconditionFailed()
                    && retryCount++ < MAX_COUNT_RETRY_FOR_PRECONDITION_FAILED) {
                store.discard(task.getId());
                try {
                    fileStrategy.discardProcess(task);
                } catch (IOException e) {
                    cache.setUnknownError(e);
                    break;
                }
                retry = true;
            } else {
                retry = false;
            }
        } while (retry);

        // finish
        finishing = true;
        blockChainList.clear();

        final DownloadCache cache = this.cache;
        if (canceled || cache == null) return;

        final EndCause cause;
        Exception realCause = null;
        if (cache.isServerCanceled() || cache.isUnknownError()
                || cache.isPreconditionFailed()) {
            // error
            cause = EndCause.ERROR;
            realCause = cache.getRealCause();
        } else if (cache.isFileBusyAfterRun()) {
            cause = EndCause.FILE_BUSY;
        } else if (cache.isPreAllocateFailed()) {
            cause = EndCause.PRE_ALLOCATE_FAILED;
            realCause = cache.getRealCause();
        } else {
            cause = EndCause.COMPLETED;
        }
        inspectTaskEnd(cache, cause, realCause);
    }

    private void inspectTaskStart() {
        OkDownload.with().breakpointStore().onTaskStart(task.getId());
        OkDownload.with().callbackDispatcher().dispatch().taskStart(task);
    }

    private void inspectTaskEnd(DownloadCache cache, @NonNull EndCause cause,
                                @Nullable Exception realCause) {
        // non-cancel handled on here
        if (cause == EndCause.CANCELED) {
            throw new IllegalAccessError("can't recognize cancelled on here");
        }

        synchronized (this) {
            if (canceled) return;
            finishing = true;
        }

        OkDownload.with().breakpointStore().onTaskEnd(task.getId(), cause, realCause);
        if (cause == EndCause.COMPLETED) {
            OkDownload.with().processFileStrategy()
                    .completeProcessStream(cache.getOutputStream(), task);
        }

        OkDownload.with().callbackDispatcher().dispatch().taskEnd(task, cause, realCause);
    }

    // this method is convenient for unit-test.
    DownloadCache createCache(@NonNull BreakpointInfo info) {
        final MultiPointOutputStream outputStream = OkDownload.with().processFileStrategy()
                .createProcessStream(task, info);
        return new DownloadCache(outputStream);
    }

    // this method is convenient for unit-test.
    int getPriority() {
        return task.getPriority();
    }

    void start(final DownloadCache cache, BreakpointInfo info) throws InterruptedException {
        final int blockCount = info.getBlockCount();
        final List<DownloadChain> blockChainList = new ArrayList<>(info.getBlockCount());
        final long totalLength = info.getTotalLength();
        for (int i = 0; i < blockCount; i++) {
            final BlockInfo blockInfo = info.getBlock(i);
            if (Util.isCorrectFull(blockInfo.getCurrentOffset(), blockInfo.getContentLength())) {
                continue;
            }

            Util.resetBlockIfDirty(blockInfo);
            blockChainList.add(DownloadChain.createChain(i, task, info, cache));
        }

        if (canceled) {
            return;
        }

        startBlocks(blockChainList);
    }

    @Override
    protected void canceled(InterruptedException e) {
    }

    @Override
    protected void finished() {
        OkDownload.with().downloadDispatcher().finish(this);
    }

    void startBlocks(List<DownloadChain> tasks) throws InterruptedException {
        ArrayList<Future> futures = new ArrayList<>(tasks.size());
        try {
            for (DownloadChain chain : tasks) {
                futures.add(submitChain(chain));
            }

            blockChainList.addAll(tasks);

            for (Future future : futures) {
                if (!future.isDone()) {
                    try {
                        future.get();
                    } catch (CancellationException | ExecutionException ignore) { }
                }
            }
        } catch (Throwable t) {
            for (Future future : futures) {
                future.cancel(true);
            }
            throw t;
        } finally {
            blockChainList.removeAll(tasks);
        }
    }

    // convenient for unit-test
    @NonNull BreakpointLocalCheck createLocalCheck(@NonNull BreakpointInfo info) {
        return new BreakpointLocalCheck(task, info);
    }

    // convenient for unit-test
    @NonNull BreakpointRemoteCheck createRemoteCheck(@NonNull BreakpointInfo info) {
        return new BreakpointRemoteCheck(task, info);
    }

    void assembleBlockAndCallbackFromBeginning(@NonNull BreakpointInfo info,
                                               @NonNull BreakpointRemoteCheck remoteCheck,
                                               @NonNull ResumeFailedCause failedCause) {
        Util.assembleBlock(task, info, remoteCheck.getInstanceLength(),
                remoteCheck.isAcceptRange());
        OkDownload.with().callbackDispatcher().dispatch()
                .downloadFromBeginning(task, info, failedCause);
    }

    Future<?> submitChain(DownloadChain chain) {
        return EXECUTOR.submit(chain);
    }

    @Override
    public int compareTo(@NonNull DownloadCall o) {
        return o.getPriority() - getPriority();
    }
}
