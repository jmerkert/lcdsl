/*
 * Copyright (c) SSI Schaefer IT Solutions
 */
package com.wamas.ide.launching.generator;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;

import com.wamas.ide.launching.Activator;
import com.wamas.ide.launching.lcDsl.LaunchConfig;

public class StandaloneLaunchConfigExecutor {

    /**
     * Starts a launch configuration. The return value is only valid if wait is <code>true</code>
     *
     * @param launchConf
     *            the launch configuration
     * @param build
     *            whether to perform a build before launch
     * @param wait
     *            whether to wait for completion
     * @param logFile
     *            an optional {@link File} to write console output to. May be <code>null</code>.
     * @return process exit value if wait is <code>true</code>, always 0 if wait is
     *         <code>false</code>. -1 in case waiting was interrupted.
     */
    public static int launchProcess(ILaunchConfiguration launchConf, boolean debug, boolean build, boolean wait, File logFile) {
        StandaloneLauncherJob launch = new StandaloneLauncherJob(launchConf, debug, build, wait, logFile);

        launch.setPriority(Job.SHORT);
        launch.schedule();
        if (wait) {
            try {
                launch.join();
            } catch (InterruptedException e) {
                return -1;
            }
        }

        // when waiting this is the real result, when not it's initialized to 0
        return launch.getProcessResult();
    }

    /**
     * Launches the specified configuration and optionally waits until the end of execution.
     *
     * @param launchConfig
     *            launch configuration
     * @param mode
     *            the mode in which to launch
     * @param monitor
     *            progress monitor (only for cancellation)
     * @param timeout
     *            timeout in milliseconds (optional; {@code <=0} means no timeout)
     * @param logFile
     *            log file where console output is redirected (optional; {@code null} means no log
     *            file)
     * @return the resulting launch
     */
    private static ILaunch launch(final ILaunchConfiguration launchConfig, final String mode, final IProgressMonitor monitor,
            final long timeout, final File logFile, boolean build, boolean wait) throws Exception {
        final FileLogger logger;
        if (logFile != null) {
            logger = new FileLogger(logFile);
        } else {
            logger = null;
        }

        Object lock = new Object();
        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        launchManager.addLaunchListener(new SpecificLaunchListener(launchConfig) {

            private final Set<IProcess> attached = new HashSet<>();

            @Override
            public void launchChanged(ILaunch launch) {
                if (logger == null) {
                    return;
                }

                for (IProcess p : launch.getProcesses()) {
                    if (!attached.contains(p)) {
                        p.getStreamsProxy().getOutputStreamMonitor().addListener(logger);
                        p.getStreamsProxy().getErrorStreamMonitor().addListener(logger);

                        attached.add(p);
                    }
                }
            }

            @Override
            public void launchTerminated(ILaunch l) {
                // found it. make sure that the streams are closed.
                StreamHelper.closeQuietly(logger);
                launchManager.removeLaunchListener(this);

                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });

        final ILaunch launch = launchConfig.launch(mode, monitor, build, true);
        monitor.subTask("waiting for processes...");

        if (wait) {
            long timeRunning = 0;
            while (launch.hasChildren() && !launch.isTerminated()) {
                if (monitor.isCanceled()) {
                    launch.terminate();
                }
                if (timeout > 0 && timeRunning > timeout) {
                    launch.terminate();
                    throw new InterruptedException("execution exceeded maximum time of " + timeout + "ms");
                }
                synchronized (lock) {
                    lock.wait(500);
                }
                timeRunning += 500;
            }
        }

        return launch;
    }

    /**
     * Job that launches a {@link LaunchConfig} in the background.
     */
    private static class StandaloneLauncherJob extends Job {

        private final ILaunchConfiguration config;

        private int result = 0;
        private final boolean debug;
        private final boolean build;
        private final boolean wait;

        private final File logFile;

        /**
         * Creates a new {@link StandaloneLauncherJob} to monitor an await launching of
         * {@link ILaunchConfiguration}s
         *
         * @param config
         *            the {@link ILaunchConfiguration} to start
         * @param debug
         *            whether to attach a debugger
         * @param build
         *            whether to build before launch
         * @param wait
         *            whether to keep the job alive until the associated {@link ILaunch} terminates.
         * @param logFile
         *            an optional {@link File} to write console output to. May be <code>null</code>.
         */
        StandaloneLauncherJob(ILaunchConfiguration config, boolean debug, boolean build, boolean wait, File logFile) {
            super("run " + config.getName());
            this.config = config;
            this.debug = debug;
            this.build = build;
            this.wait = wait;
            this.logFile = logFile;
        }

        int getProcessResult() {
            return result;
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            try {
                monitor.beginTask("Launching " + config.getName(), IProgressMonitor.UNKNOWN);
                String mode = debug ? ILaunchManager.DEBUG_MODE : ILaunchManager.RUN_MODE;

                ILaunch launch = launch(config, mode, monitor, 0, logFile, build, wait);

                if (wait) {
                    IProcess[] ps = launch.getProcesses();

                    // in our scenarios it NEVER happens that there is more than one process
                    for (IProcess p : ps) {
                        if (p.getExitValue() != 0) {
                            result = p.getExitValue();
                        }
                    }
                }
            } catch (Exception e) {
                Activator.log(IStatus.ERROR, "Failed launching " + config.getName(), e);
            } finally {
                monitor.done();
            }

            // always return OK, to avoid error messages
            return Status.OK_STATUS;
        }
    }

}
