package riru;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.IBinder;
import android.os.ServiceManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

public class DaemonUtils {

    private static Boolean has32Bit = null, has64Bit = null;

    public static boolean has32Bit() {
        if (has32Bit == null) {
            has32Bit = Build.SUPPORTED_32_BIT_ABIS.length > 0;
        }
        return has32Bit;
    }

    public static boolean has64Bit() {
        if (has64Bit == null) {
            has64Bit = Build.SUPPORTED_64_BIT_ABIS.length > 0;
        }
        return has64Bit;
    }

    public static String readOriginalNativeBridge() {
        LocalSocket socket = new LocalSocket();
        InputStream is = null;
        OutputStream os = null;
        try {
            socket.connect(new LocalSocketAddress("rirud"));
            is = new BufferedInputStream(socket.getInputStream());
            os = new BufferedOutputStream(socket.getOutputStream());

            byte[] buf = new byte[4096];
            buf[0] = 3; // uint32 ACTION_READ_NATIVE_BRIDGE = 3
            os.write(buf, 0, 4);
            os.flush();

            // int32 size
            if (is.read(buf, 0, 4) != 4) {
                throw new IOException("read size");
            }
            int size = buf[0] + (buf[1] << 8) + (buf[2] << 16) + (buf[3] << 24);
            Log.d(Daemon.TAG, "read native_bridge size " + size);
            if (size < 0 || size > 4096) {
                throw new IOException("bad size");
            }

            // char[size]
            if (is.read(buf, 0, size) != size) {
                throw new IOException("read buf");
            }
            return new String(buf, 0, size);
        } catch (Throwable e) {
            Log.w(Daemon.TAG, "Can't read native_bridge.", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    public static void resetNativeBridgeProp(String value) {
        exec("resetprop", "ro.dalvik.vm.native.bridge", value);
    }

    public static void exec(String... command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        try {
            Process process = pb.start();
            int code = process.waitFor();
            Log.i(Daemon.TAG, "Exec " + command[0] + " exited with " + code);
        } catch (Throwable e) {
            Log.w(Daemon.TAG, "Exec " + command[0], e);
        }
    }

    public static IBinder waitForSystemService(String name) {
        IBinder binder;
        do {
            binder = ServiceManager.getService(name);
            if (binder != null && binder.pingBinder()) {
                return binder;
            }

            Log.i(Daemon.TAG, "Service " + name + " not found, wait 1s...");
            try {
                //noinspection BusyWait
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        } while (true);
    }

    public static String getRiruRandom() {
        String devRandom = null;
        try (BufferedReader br = new BufferedReader(new FileReader(new File("/data/adb/riru/dev_random")))) {
            char[] buf = new char[4096];
            int size;
            if ((size = br.read(buf)) > 0) {
                devRandom = new String(buf, 0, size);
            }
        } catch (IOException e) {
            Log.w(Daemon.TAG, "Can't read dev_random.", e);
        }
        return devRandom;
    }

    public static File getRiruDevFile() {
        String devRandom = getRiruRandom();
        if (devRandom == null) {
            return null;
        }

        if (has64Bit()) {
            return new File("/dev/riru64_" + devRandom);
        } else {
            return new File("/dev/riru_" + devRandom);
        }
    }

    public static boolean isRiruLoaded() {
        File file = getRiruDevFile();
        return file != null && file.exists();
    }

    private static boolean deleteDir(File file) {
        boolean res = true;
        File[] files = file.listFiles();
        if (files != null) {
            for (File f : files) {
                res &= deleteDir(f);
            }
        }
        return res & file.delete();
    }

    public static void deleteDevFolder() {
        String devRandom = getRiruRandom();
        if (devRandom == null) {
            return;
        }

        File file;

        file = new File("/dev/riru_" + devRandom);
        Log.i(Daemon.TAG, "Attempt to delete " + file + "...");
        if (!deleteDir(file)) {
            file.renameTo(new File("/dev/riru_" + devRandom + "_" + System.currentTimeMillis()));
        } else {
            Log.i(Daemon.TAG, "Deleted " + file);
        }

        file = new File("/dev/riru64_" + devRandom);
        Log.i(Daemon.TAG, "Attempt to delete " + file + "...");
        if (!deleteDir(file)) {
            file.renameTo(new File("/dev/riru_" + devRandom + "_" + System.currentTimeMillis()));
        } else {
            Log.i(Daemon.TAG, "Deleted " + file + ".");
        }
    }

    public static int findNativeDaemonPid() {
        File proc = new File("/proc");

        String[] names = proc.list();
        if (names == null) return -1;

        for (String name : names) {
            if (!TextUtils.isDigitsOnly(name)) continue;

            try (BufferedReader br = new BufferedReader(new FileReader(new File(String.format("/proc/%s/cmdline", name))))) {
                String[] args = br.readLine().split("\0");
                if (args.length >= 1 && (Objects.equals("rirud", args[0]) || args[0].endsWith("riru-core/rirud"))) {
                    Log.i(Daemon.TAG, "Found rirud " + name);
                    return Integer.parseInt(name);
                }
            } catch (Throwable ignored) {
                try (BufferedReader br = new BufferedReader(new FileReader(new File(String.format("/proc/%s/comm", name))))) {
                    String[] args = br.readLine().split("\0");
                    if (args.length >= 1 && (Objects.equals("rirud", args[0]) || args[0].endsWith("riru-core/rirud"))) {
                        Log.i(Daemon.TAG, "Found rirud " + name);
                        return Integer.parseInt(name);
                    }
                } catch (Throwable ignored2) {
                }
            }
        }

        Log.w(Daemon.TAG, "Can't find rirud.");
        return -1;
    }

    public static void startSocket(int pid) {
        if (pid != -1) {
            try {
                Os.kill(pid, OsConstants.SIGUSR2);
            } catch (ErrnoException e) {
                Log.w(Daemon.TAG, Log.getStackTraceString(e));
            }
        }
    }

    public static void stopSocket(int pid) {
        if (pid != -1) {
            try {
                Os.kill(pid, OsConstants.SIGUSR1);
            } catch (ErrnoException e) {
                Log.w(Daemon.TAG, Log.getStackTraceString(e));
            }
        }
    }
}
