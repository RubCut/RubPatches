package cat.narezany.margyt;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The LSPatch modules the patcher baked in, with switches.
 *
 * LSPatch itself knows no switches: at startup its loader copies every apk it
 * finds in `assets/lspatch/modules/` into the app's cache and loads them all.
 * The copy is `<crc>.apk` under a folder named after the module, and the
 * copy is only made when no file by that name is there yet -- which is the
 * one seam a switch can hold on to. Switching a module off writes an empty file under that name before
 * the next start: the loader finds the file already there, keeps it, tries to
 * read it as a module, fails on that one alone, and goes on without it.
 * Switching back on deletes the file, and the loader makes a real copy again.
 *
 * Nothing here unhooks a module that is already running -- the switch is
 * written down, and the next start of the app is what applies it. The
 * settings screen says so next to the switches.
 *
 * The module list is not read from the assets: with LSPatch on, the app's
 * asset view is the nested origin.apk, and the modules are in the OUTER one.
 * Everything here -- the list, the crcs, the apk the details are parsed from
 * -- is read out of the installed apk file itself, which is always the outer
 * one, and the patcher leaves the names in `assets/margyt/lspatch_modules.txt`
 * exactly so this list is one read and no guessing.
 */
final class LspModules {

    private LspModules() {}

    /** Where the loader looks, inside the outer apk's assets. */
    private static final String ASSETS = "lspatch/modules";
    private static final String ASSETS_FULL = "assets/" + ASSETS + "/";

    /** The patcher's own roll of what it embedded, one file name per line. */
    private static final String ROLL = "assets/margyt/lspatch_modules.txt";

    /** One switch per module, by the file name it sits under. */
    private static final String KEY = "lspatch_module:";

    private static SharedPreferences prefs() {
        Context context = Margy.context();
        return context == null ? null
                : context.getSharedPreferences(Margy.PREFS, Context.MODE_PRIVATE);
    }

    /** What a module is, for the list: who it is, and whether it is on. */
    static final class Entry {
        String file;
        String name;
        String packageName = "";
        String version = "";
        boolean enabled;
    }

    /** The installed apk as a zip: the outer one, modules and all. */
    private static ZipFile outer(Context context) {
        try {
            return new ZipFile(context.getPackageResourcePath());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The module file names the build carries, or none. */
    static List<String> names(Context context) {
        List<String> out = new ArrayList<>();
        ZipFile apk = outer(context);
        if (apk == null) return out;
        try {
            ZipEntry roll = apk.getEntry(ROLL);
            if (roll != null) {
                InputStream in = apk.getInputStream(roll);
                String text = new String(readAll(in), "UTF-8");
                in.close();
                for (String line : text.split("\n")) {
                    String file = line.trim();
                    if (file.endsWith(".apk")) out.add(file);
                }
            }
            if (out.isEmpty()) {
                // an older patcher left no roll: fall back to the entries
                for (ZipEntry entry : java.util.Collections.list(apk.entries())) {
                    String name = entry.getName();
                    if (name.startsWith(ASSETS_FULL) && name.endsWith(".apk")) {
                        out.add(name.substring(ASSETS_FULL.length()));
                    }
                }
            }
        } catch (Throwable error) {
            Diary.note("lspatch modules: " + error);
        } finally {
            try { apk.close(); } catch (Throwable ignored) { }
        }
        return out;
    }

    static boolean hasAny(Context context) {
        return !names(context).isEmpty();
    }

    /** The list for the settings panel; each module's details are parsed once. */
    static List<Entry> list(Context context) {
        List<Entry> out = new ArrayList<>();
        for (String file : names(context)) {
            Entry entry = new Entry();
            entry.file = file;
            entry.enabled = isEnabled(file);
            Entry known = DESCRIBED.get(file);
            if (known != null) {
                entry.name = known.name;
                entry.packageName = known.packageName;
                entry.version = known.version;
            } else {
                describe(context, entry);
                DESCRIBED.put(file, entry);
            }
            out.add(entry);
        }
        return out;
    }

    private static final Map<String, Entry> DESCRIBED = new HashMap<>();

    /** The name and version out of the module apk itself, kept under the cache. */
    private static void describe(Context context, Entry entry) {
        entry.name = base(entry.file);
        File copy = new File(context.getCacheDir(), "margyt/module-info/" + entry.file);
        ZipFile apk = outer(context);
        try {
            if (!copy.exists() && apk != null) {
                ZipEntry packed = apk.getEntry(ASSETS_FULL + entry.file);
                if (packed == null) return;
                copy.getParentFile().mkdirs();
                InputStream in = apk.getInputStream(packed);
                FileOutputStream out = new FileOutputStream(copy);
                byte[] chunk = new byte[16384];
                int read;
                while ((read = in.read(chunk)) >= 0) out.write(chunk, 0, read);
                out.close();
                in.close();
            }
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(copy.getAbsolutePath(),
                    PackageManager.GET_META_DATA);
            if (info != null) {
                info.applicationInfo.sourceDir = copy.getAbsolutePath();
                info.applicationInfo.publicSourceDir = copy.getAbsolutePath();
                CharSequence label = info.applicationInfo.loadLabel(pm);
                if (label != null && label.length() > 0) entry.name = label.toString();
                if (info.packageName != null) entry.packageName = info.packageName;
                if (info.versionName != null) entry.version = info.versionName;
                if (info.applicationInfo.metaData != null) {
                    Object about = info.applicationInfo.metaData.get("xposeddescription");
                    if (about != null && entry.version.isEmpty()) {
                        entry.version = String.valueOf(about);
                    }
                }
            }
        } catch (Throwable error) {
            Diary.note("lspatch module: " + error);
        } finally {
            if (apk != null) try { apk.close(); } catch (Throwable ignored) { }
        }
    }

    static boolean isEnabled(String file) {
        SharedPreferences prefs = prefs();
        return prefs == null || prefs.getBoolean(KEY + file, true);
    }

    /**
     * Write the switch down and put the gate in place. Whether the loader
     * honours it is decided at the next start, not this one.
     */
    static void setEnabled(Context context, String file, boolean on) {
        SharedPreferences prefs = prefs();
        if (prefs != null) prefs.edit().putBoolean(KEY + file, on).apply();
        gate(context, file, on);
        Diary.note("lspatch module " + base(file) + ": " + (on ? "on" : "off"));
    }

    /** The empty file that stands where the loader would copy the module. */
    private static void gate(Context context, String file, boolean enabled) {
        try {
            File standing = placeholder(context, file);
            if (standing == null) return;
            if (enabled) {
                standing.delete();
            } else if (!standing.exists()) {
                standing.getParentFile().mkdirs();
                new FileOutputStream(standing).close();
            }
        } catch (Throwable error) {
            Diary.note("lspatch module gate: " + error);
        }
    }

    /**
     * Where the loader wants the module's copy: the cache, under the module's
     * name and the crc of the entry inside the apk -- the same arithmetic the
     * loader itself does, so the name matches byte for byte.
     */
    private static File placeholder(Context context, String file) {
        long crc = crcOf(context, ASSETS_FULL + file);
        if (crc < 0) return null;
        String base = base(file);
        return new File(context.getCacheDir(),
                "lspatch/" + base + "/" + crc + ".apk");
    }

    private static long crcOf(Context context, String entry) {
        ZipFile apk = outer(context);
        if (apk == null) return -1;
        try {
            ZipEntry found = apk.getEntry(entry);
            return found == null ? -1 : found.getCrc();
        } finally {
            try { apk.close(); } catch (Throwable ignored) { }
        }
    }

    private static String base(String file) {
        return file.endsWith(".apk") ? file.substring(0, file.length() - 4) : file;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) >= 0) all.write(chunk, 0, read);
        return all.toByteArray();
    }

    /**
     * Called once a process, from the first moment a context is known.
     *
     * The cache is not promised to survive: the system may clear it, and then
     * a switched-off module would quietly come back. Putting the gate back is
     * one existence check per module, so it is done here rather than left to
     * the next visit to the settings.
     */
    public static void apply(Context context) {
        if (applied || context == null) return;
        applied = true;
        try {
            SharedPreferences prefs = prefs();
            if (prefs == null) return;
            for (String file : names(context)) {
                if (!prefs.getBoolean(KEY + file, true)) {
                    gate(context, file, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile boolean applied;
}
