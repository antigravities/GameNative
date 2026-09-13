package com.winlator.xenvironment.components;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.TarCompressorUtils;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

/**
 * Launches Xalia (a Wine-side UI Automation accessibility daemon) as a background
 * process so it can drive gamepad/keyboard menu navigation for the lifetime of the
 * container session. Opt-in per container via {@link Container#isXaliaEnabled()}.
 *
 * Launches through the container's own {@link GuestProgramLauncherComponent}
 * (Bionic or Glibc, whichever the container uses) via
 * {@link GuestProgramLauncherComponent#execShellCommandAsync}, the same
 * environment/command construction as execShellCommand (used elsewhere for
 * wineserver -k and steam-token.exe) -- NOT the base class's static proot-based
 * exec() helper, which is dead code left over from an old chroot-based launch
 * model this fork no longer uses. This does not touch that component's own pid
 * tracking (the game process's suspend/resume/kill state); Xalia is tracked and
 * torn down independently via its own pid field below.
 */
public class XaliaComponent extends EnvironmentComponent {
    private static final String TAG = "XaliaComponent";

    // Update this alongside the bundled asset whenever xalia is upgraded, e.g. "0.5.0".
    // Mirrors the versioned-filename convention used for box64/FEXCore/wine assets, so the
    // marker file below can detect a stale extraction left over from an older app version
    // and re-extract rather than silently keeping the old binary forever.
    private static final String VERSION = "0.5.0";
    private static final String ASSET_PATH = "xalia/xalia-" + VERSION + ".tzst";
    private static final String GUEST_RELATIVE_PATH = "opt/apps/xalia.exe";
    private static final String VERSION_MARKER_RELATIVE_PATH = "opt/apps/.xalia_version";

    private final Context context;
    private final Container container;
    private final GuestProgramLauncherComponent guestProgramLauncherComponent;
    private int pid = -1;

    public XaliaComponent(Context context, Container container, GuestProgramLauncherComponent guestProgramLauncherComponent) {
        this.context = context;
        this.container = container;
        this.guestProgramLauncherComponent = guestProgramLauncherComponent;
    }

    @Override
    public void start() {
        if (container == null || !container.isXaliaEnabled()) return;

        ImageFs imageFs = ImageFs.find(context);
        File guestExecutable = new File(imageFs.getRootDir(), GUEST_RELATIVE_PATH);
        File versionMarker = new File(imageFs.getRootDir(), VERSION_MARKER_RELATIVE_PATH);

        boolean upToDate = guestExecutable.isFile() && versionMarker.isFile()
                && VERSION.equals(FileUtils.readString(versionMarker).trim());

        if (!upToDate) {
            File extractDir = guestExecutable.getParentFile();
            extractDir.mkdirs();
            boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), ASSET_PATH, extractDir);
            if (!extracted || !guestExecutable.isFile()) {
                Log.e(TAG, "Failed to extract xalia.exe from " + ASSET_PATH + ", skipping Xalia launch");
                return;
            }
            FileUtils.writeString(versionMarker, VERSION);
        }

        // No surrounding quotes: ProcessHelper.exec() builds argv directly (no shell involved),
        // so quotes here would end up as literal characters in the path Wine tries to open
        // rather than being stripped as shell-style grouping. Safe since this path (under the
        // app's own data dir) never contains spaces.
        String command = "wine " + guestExecutable.getAbsolutePath();
        pid = guestProgramLauncherComponent.execShellCommandAsync(command, (status) -> {
            Log.d(TAG, "Xalia process " + pid + " exited with status " + status);
            pid = -1;
        });
        Log.d(TAG, "Xalia process " + pid + " started");
    }

    @Override
    public void stop() {
        if (pid != -1) {
            Process.killProcess(pid);
            Log.d(TAG, "Stopped Xalia process " + pid);
            pid = -1;
        }
    }
}
