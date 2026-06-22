package com.winlator.xenvironment.components;

import android.content.Context;

import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.XEnvironment;

import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * PulseAudio component with timer-based suspend strategy for efficient pause/resume management.
 *
 * Suspend Behavior Modes:
 *
 * 1. suspend-via-thread (default):
 *    Suspend: cancel timers -> set isPaused=true + updateSink(true) -> suspendProcess(SIGSTOP)
 *    Resume: cancel timers -> set isPaused=false -> resumeProcess(SIGCONT) -> updateSink(false)
 *    - Fast and lightweight, uses ProcessHelper.suspendProcess/resumeProcess
 *    - No delays, all operations execute immediately
 *
 * 2. suspend-via-pactl (power-saving):
 *    Suspend: cancel timers -> set isPaused=true + updateSink(true) -> suspend timer (120s/10s debug) -> pactl unload module
 *    Resume: cancel timers -> set isPaused=false -> check sink alive -> pactl load module OR updateSink(false)
 *    - Quick resume (< timeout): Cancels timer and resumes sink immediately (no module reload)
 *    - Long pause (≥ timeout): Module unloaded to save CPU
 *    - Resume after unload: Automatically detects missing sink and reloads module
 *    - No delay on resume for instant audio restoration
 */
public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private final String SINK_NAME = "AAudioSink";

    private final Object lock = new Object();
    private float volume = 1.0f;
    private byte performanceMode = 1;
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private boolean lowLatency = false;

    // pactl invocations fork an external process and block until it connects to the PulseAudio
    // server, which can take a long time when the server is contended. pause()/resume() are
    // called from the UI thread on game suspend, so we run the actual pactl work on a dedicated
    // single-thread executor: ordering is preserved (a rapid pause→resume can't interleave) and
    // the caller never blocks. See ProcessHelper.execWithOutput timeout for the hard backstop.
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pulse-audio-ctl");
        t.setDaemon(true);
        return t;
    });
    // Leak-guard only: reap a genuinely wedged pactl child. Since pause()/resume() run on
    // audioExecutor (not the UI thread) this does NOT need to be tight — a cold pactl invocation
    // (fork + linker64 + dlopen libpulse + socket connect) can legitimately take a couple of
    // seconds, so keep this generous to avoid killing a slow-but-responsive call mid-command.
    private static final long PACTL_TIMEOUT_MS = 8000;

    public PulseAudioComponent(UnixSocketConfig socketConfig, boolean lowLatency) {
        this.socketConfig = socketConfig;
        this.lowLatency = lowLatency;
    }

    private void killAllPulseAudioProcesses() {
        List<ProcessHelper.ProcessInfo> allProcesses = ProcessHelper.listSubProcesses();
        List<Integer> pulsePids = new ArrayList<>();

        for (ProcessHelper.ProcessInfo info : allProcesses) {
            if (info.name.contains("libpulseaudio.so")) {
                pulsePids.add(info.pid);
            }
        }

        if (!pulsePids.isEmpty()) {
            Timber.tag("PulseAudioComponent").w("Found %d pulseaudio process(es), killing: %s",
                pulsePids.size(), pulsePids.toString());

            for (int pid : pulsePids) {
                ProcessHelper.killProcess(pid);
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void start() {
        Timber.tag("PulseAudioComponent").d("Starting...");
        synchronized (lock) {
            if (!isServerRunning()) {
                killAllPulseAudioProcesses();
                startPulseAudio();
                isPaused.set(false);
            }
        }
    }

    @Override
    public void stop() {
        Timber.tag("PulseAudioComponent").d("Stopping...");
        synchronized (lock) {
            // Stop sink here
            updateSink(true);

            isPaused.set(false);

            killAllPulseAudioProcesses();

            Timber.tag("PulseAudioComponent").d("Stopped PulseAudio server");
        }
    }

    public void pause() {
        // Run off the caller's thread (the UI thread on game suspend) — pactl can block.
        audioExecutor.execute(() -> {
            synchronized (lock) {
                if (!isPaused.get()) {
                    Timber.tag("PulseAudioComponent").d("Pausing...");

                    // No isServerRunning() probe: the server stays alive across suspend (only
                    // stop() kills it) and suspend-sink is idempotent, so the extra pactl round
                    // trip buys nothing and only adds latency / a chance to stall.
                    isPaused.set(true);
                    updateSink(true);

                    Timber.tag("PulseAudioComponent").d("Audio paused");
                }
            }
        });
    }

    public void resume() {
        audioExecutor.execute(() -> {
            synchronized (lock) {
                if (isPaused.get()) {
                    Timber.tag("PulseAudioComponent").d("Resuming...");

                    isPaused.set(false);
                    // Directly un-suspend the sink. updateSink reports back whether pactl actually
                    // reached the server; only relaunch on a genuine connection failure — NOT on a
                    // slow/timed-out probe (which previously misrouted us into killing the server
                    // and left game audio permanently disconnected).
                    boolean ok = updateSink(false);
                    if (!ok) {
                        Timber.tag("PulseAudioComponent").w("Sink resume failed (server gone?), restarting PulseAudio");
                        start();
                    }

                    Timber.tag("PulseAudioComponent").d("Audio resumed");
                }
            }
        });
    }

    public boolean isServerRunning() {
        final String info = execPactlCommand("info").toLowerCase(java.util.Locale.ROOT);
        return info.contains("server name:") && !info.contains("connection failure");
    }

    public void setVolume(float volume) {
        this.volume = volume;
    }

    public void setPerformanceMode(int performanceMode) {
        this.performanceMode = (byte) performanceMode;
    }

    private void startPulseAudio() {
        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        // nativeLibraryDir = nativeLibraryDir.replace("arm64", "arm64-v8a");
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        // Clear any previous staled files, e.g. cookie
        File configDir = new File(workingDir, "/.config");
        if (configDir.exists()) {
            FileUtils.delete(configDir);
        }

        File configFile = new File(workingDir, "default.pa");
        String sinkParams = "volume=" + this.volume + " performance_mode=" + ((int) this.performanceMode);
        if (lowLatency) {
            sinkParams += " low_latency=true";
        }
        FileUtils.writeString(configFile, String.join("\n",
                "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\""+socketConfig.path+"\"",
                "load-module module-aaudio-sink " + sinkParams
        ));

        String archName = AppUtils.getArchName();
        File modulesDir = new File(workingDir, "modules");

        EnvVars envVars = new EnvVars();
        envVars.put("LD_LIBRARY_PATH", "/system/lib64:"+nativeLibraryDir+":"+modulesDir);
        envVars.put("HOME", workingDir);
        envVars.put("TMPDIR", XEnvironment.getTmpDir(context));


        String command = nativeLibraryDir+"/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=true";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        // Uncomment to enable verbose log in pulseaudio
        //command += " -vvv";

        String output = ProcessHelper.execWithOutput(command, envVars.toStringArray(), workingDir, true);
        Timber.tag("PulseAudioComponent").d("Started PulseAudio server %s", output);
    }

    private String execPactlCommand(String command) {
        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");

        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        File modulesDir = new File(workingDir, "modules");
        EnvVars envVars = new EnvVars();
        envVars.put("LD_LIBRARY_PATH", "/system/lib64:" + nativeLibraryDir + ":" + modulesDir);
        envVars.put("HOME", workingDir);
        envVars.put("TMPDIR", XEnvironment.getTmpDir(context));
        envVars.put("PULSE_SERVER", socketConfig.path);

        // Instrumentation: pactl must never run on the main thread (it forks + blocks). Time it
        // so a slow/wedged server shows up clearly in logs.
        boolean onMain = Looper.myLooper() == Looper.getMainLooper();
        long start = System.currentTimeMillis();
        String result = ProcessHelper.execWithOutput(
            workingDir + "/pactl " + command, envVars.toStringArray(), workingDir, true, PACTL_TIMEOUT_MS);
        long elapsed = System.currentTimeMillis() - start;
        if (onMain || elapsed > 500) {
            Timber.tag("PulseAudioComponent").w("pactl '%s' took %dms (mainThread=%b)", command, elapsed, onMain);
        }
        return result;
    }

    /**
     * Suspend/un-suspend the sink. Returns false only on an explicit pactl "connection failure"
     * (server genuinely gone) so callers can choose to relaunch. A successful command prints
     * nothing; a timed-out command (reaped by PACTL_TIMEOUT_MS) also returns empty — both are
     * treated as "not a connection failure" so we never relaunch the server on a mere slow call.
     */
    private boolean updateSink(boolean suspend) {
        String output = execPactlCommand("suspend-sink " + SINK_NAME + " " + (suspend ? "true" : "false"));
        return !output.toLowerCase(java.util.Locale.ROOT).contains("connection failure");
    }

}
