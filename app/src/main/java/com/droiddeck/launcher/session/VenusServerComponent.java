package com.droiddeck.launcher.session;

import android.os.Process;
import android.util.Log;

import com.droiddeck.launcher.core.HostProcess;
import com.droiddeck.launcher.core.SessionPart;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * The virglrenderer server that runs the guest's Vulkan on the device's own driver, for a GPU the
 * runtime's Turnip cannot open ({@link com.droiddeck.launcher.gpu.MaliVenus}).
 *
 * <p>A glibc process cannot load the vendor's bionic driver, so the guest speaks Venus over a unix
 * socket and this server, a bionic program under the app's uid, replays the calls on
 * {@code libvulkan.so}. It forks one child per client: gamescope, the client and each game get their
 * own. Shipped as {@code libvirgl_test_server.so} because Android executes nothing outside the
 * native library directory; its two libraries sit beside it and it finds them by RUNPATH.
 *
 * <p>Started before the guest, which tries the socket once when it creates its first Vulkan instance.
 */
public class VenusServerComponent extends SessionPart {
    private static final String TAG = "VenusServer";
    public static final String BINARY = "libvirgl_test_server.so";

    private final File socketPath;
    private final File logFile;
    private volatile int pid = -1;

    public VenusServerComponent(File socketPath, File logFile) {
        this.socketPath = socketPath;
        this.logFile = logFile;
    }

    @Override
    public void start() {
        stop();
        File binary = new File(app().getApplicationInfo().nativeLibraryDir, BINARY);
        if (!binary.isFile()) {
            Log.w(TAG, "server missing at " + binary + "; the session will have no Vulkan");
            return;
        }
        File parent = socketPath.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        // A socket left by a session that did not shut down cleanly would fail the bind.
        //noinspection ResultOfMethodCallIgnored
        socketPath.delete();

        String command = binary.getAbsolutePath().replace(" ", "\\ ")
                + " --venus --socket-path " + socketPath.getAbsolutePath().replace(" ", "\\ ");
        ArrayList<String> env = new ArrayList<>();
        env.add("HOME=" + app().getFilesDir());
        env.add("TMPDIR=" + app().getCacheDir());
        PrintWriter out = null;
        if (logFile != null) {
            try {
                out = new PrintWriter(new FileWriter(logFile, true));
                out.println("== Venus server starting: " + command);
                out.flush();
            } catch (Exception e) {
                Log.w(TAG, "could not open " + logFile, e);
            }
        }
        final PrintWriter log = out;
        pid = HostProcess.start(command, env.toArray(new String[0]), app().getFilesDir(),
                status -> {
                    Log.w(TAG, "server exited with " + status);
                    if (log != null) synchronized (log) { log.println("== Venus server exited with " + status); log.flush(); }
                },
                line -> {
                    Log.i(TAG, line);
                    if (log != null) synchronized (log) { log.println(line); log.flush(); }
                });
        // The guest connects as soon as gamescope starts; give the server the moment it needs to bind.
        long deadline = System.currentTimeMillis() + 3000L;
        while (pid != -1 && !socketPath.exists() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.i(TAG, "started pid=" + pid + " socket=" + socketPath + (socketPath.exists() ? "" : " (not bound yet)"));
    }

    @Override
    public void stop() {
        if (pid != -1) {
            // Its per-client children are in the same process group only by accident of fork; each
            // exits when its client's socket closes, which the guest's teardown does.
            Process.killProcess(pid);
            pid = -1;
        }
    }

    @Override
    public int suspendPid() {
        return pid;
    }
}
