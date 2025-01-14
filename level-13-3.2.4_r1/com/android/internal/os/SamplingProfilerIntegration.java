/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;
import dalvik.system.SamplingProfiler;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import libcore.io.IoUtils;

/**
 * Integrates the framework with Dalvik's sampling profiler.
 */
public class SamplingProfilerIntegration {

    private static final String TAG = "SamplingProfilerIntegration";

    public static final String SNAPSHOT_DIR = "/data/snapshots";

    private static final boolean enabled;
    private static final Executor snapshotWriter;
    private static final int samplingProfilerMilliseconds;
    private static final int samplingProfilerDepth;

    /** Whether or not a snapshot is being persisted. */
    private static final AtomicBoolean pending = new AtomicBoolean(false);

    static {
        samplingProfilerMilliseconds = SystemProperties.getInt("persist.sys.profiler_ms", 0);
        samplingProfilerDepth = SystemProperties.getInt("persist.sys.profiler_depth", 4);
        if (samplingProfilerMilliseconds > 0) {
            File dir = new File(SNAPSHOT_DIR);
            dir.mkdirs();
            // the directory needs to be writable to anybody to allow file writing
            dir.setWritable(true, false);
            // the directory needs to be executable to anybody to allow file creation
            dir.setExecutable(true, false);
            if (dir.isDirectory()) {
                snapshotWriter = Executors.newSingleThreadExecutor(new ThreadFactory() {
                        public Thread newThread(Runnable r) {
                            return new Thread(r, TAG);
                        }
                    });
                enabled = true;
                Log.i(TAG, "Profiling enabled. Sampling interval ms: "
                      + samplingProfilerMilliseconds);
            } else {
                snapshotWriter = null;
                enabled = true;
                Log.w(TAG, "Profiling setup failed. Could not create " + SNAPSHOT_DIR);
            }
        } else {
            snapshotWriter = null;
            enabled = false;
            Log.i(TAG, "Profiling disabled.");
        }
    }

    private static SamplingProfiler INSTANCE;

    /**
     * Is profiling enabled?
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Starts the profiler if profiling is enabled.
     */
    public static void start() {
        if (!enabled) {
            return;
        }
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        SamplingProfiler.ThreadSet threadSet = SamplingProfiler.newThreadGroupTheadSet(group);
        INSTANCE = new SamplingProfiler(samplingProfilerDepth, threadSet);
        INSTANCE.start(samplingProfilerMilliseconds);
    }

    /**
     * Writes a snapshot if profiling is enabled.
     */
    public static void writeSnapshot(final String processName, final PackageInfo packageInfo) {
        if (!enabled) {
            return;
        }

        /*
         * If we're already writing a snapshot, don't bother enqueueing another
         * request right now. This will reduce the number of individual
         * snapshots and in turn the total amount of memory consumed (one big
         * snapshot is smaller than N subset snapshots).
         */
        if (pending.compareAndSet(false, true)) {
            snapshotWriter.execute(new Runnable() {
                public void run() {
                    try {
                        writeSnapshotFile(processName, packageInfo);
                    } finally {
                        pending.set(false);
                    }
                }
            });
        }
    }

    /**
     * Writes the zygote's snapshot to internal storage if profiling is enabled.
     */
    public static void writeZygoteSnapshot() {
        if (!enabled) {
            return;
        }
        writeSnapshotFile("zygote", null);
        INSTANCE.shutdown();
        INSTANCE = null;
    }

    /**
     * pass in PackageInfo to retrieve various values for snapshot header
     */
    private static void writeSnapshotFile(String processName, PackageInfo packageInfo) {
        if (!enabled) {
            return;
        }
        INSTANCE.stop();

        /*
         * We use the current time as a unique ID. We can't use a counter
         * because processes restart. This could result in some overlap if
         * we capture two snapshots in rapid succession.
         */
        long start = System.currentTimeMillis();
        String name = processName.replaceAll(":", ".");
        String path = SNAPSHOT_DIR + "/" + name + "-" +System.currentTimeMillis() + ".snapshot";
        PrintStream out = null;
        try {
            out = new PrintStream(new BufferedOutputStream(new FileOutputStream(path)));
            generateSnapshotHeader(name, packageInfo, out);
            new SamplingProfiler.AsciiHprofWriter(INSTANCE.getHprofData(), out).write();
            if (out.checkError()) {
                throw new IOException();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error writing snapshot to " + path, e);
            return;
        } finally {
            IoUtils.closeQuietly(out);
        }
        // set file readable to the world so that SamplingProfilerService
        // can put it to dropbox
        new File(path).setReadable(true, false);

        long elapsed = System.currentTimeMillis() - start;
        Log.i(TAG, "Wrote snapshot for " + name + " in " + elapsed + "ms.");
    }

    /**
     * generate header for snapshots, with the following format (like http header):
     *
     * Version: <version number of profiler>\n
     * Process: <process name>\n
     * Package: <package name, if exists>\n
     * Package-Version: <version number of the package, if exists>\n
     * Build: <fingerprint>\n
     * \n
     * <the actual snapshot content begins here...>
     */
    private static void generateSnapshotHeader(String processName, PackageInfo packageInfo,
            PrintStream out) {
        // profiler version
        out.println("Version: 2");
        out.println("Process: " + processName);
        if (packageInfo != null) {
            out.println("Package: " + packageInfo.packageName);
            out.println("Package-Version: " + packageInfo.versionCode);
        }
        out.println("Build: " + Build.FINGERPRINT);
        // single blank line means the end of snapshot header.
        out.println();
    }
}
