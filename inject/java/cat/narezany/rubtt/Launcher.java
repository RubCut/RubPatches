package cat.narezany.rubtt;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.util.HashMap;
import java.util.Map;

/**
 * Which icon the app wears.
 *
 * Android decides that from the component the home screen opens, and there is
 * no way to change one while the app runs -- so the build ships one component
 * per icon, all pointing at the same activity, all switched off. Choosing an
 * icon turns one on and turns the others off, and because they all lead to the
 * same place the app opens exactly as it did.
 *
 * TikTok's own entry is itself one of these -- an alias in front of
 * MainActivity -- so the default is not a special case: it is just the one the
 * build found already there.
 *
 * The launcher usually notices within a second or two. Some of them want the
 * home screen redrawn before they will, which is a launcher's business and not
 * something an app is allowed to insist on.
 */
public final class Launcher {

    private Launcher() {}

    public static final String KEY = "icon";

    /** The empty name is TikTok's own, the one the build did not add. */
    public static final String DEFAULT = "";

    /**
     * The patcher's picture, when one was baked in: the build wrote it over
     * the icon TikTok came with and left a copy here for this list.
     */
    public static final String CUSTOM = "custom";
    private static final String CUSTOM_ASSET = "rubtt/icon_custom.png";
    private static Boolean customThere;

    /**
     * The patcher's name, when one was baked in: an alias of the launcher
     * entry whose label is the name the person asked for, so whether the app
     * goes by it is a switch here rather than another build.
     */
    private static final String CUSTOM_LABEL_ASSET = "rubtt/custom_label.txt";
    private static final String KEY_NAME = "icon_custom_name";
    static final String ICON_CUSTOM = "cat.narezany.rubtt.IconCustom";
    static final String ICON_CUSTOM_NAMED = "cat.narezany.rubtt.IconCustomNamed";
    private static String customLabel;
    private static boolean customLabelRead;

    /** Whether the build carries a picture the person chose in the patcher. */
    public static boolean hasCustom(Context context) {
        if (customThere != null) return customThere;
        boolean there = false;
        try {
            java.io.InputStream in = context.getAssets().open(CUSTOM_ASSET);
            in.close();
            there = true;
        } catch (Throwable ignored) {
        }
        customThere = there;
        return there;
    }

    /** Whether the build carries a name the person chose in the patcher. */
    public static boolean hasCustomName(Context context) {
        return customName(context) != null;
    }

    /** The name the patcher baked in, or null when it did not. */
    public static String customName(Context context) {
        if (customLabelRead) return customLabel;
        customLabelRead = true;
        try {
            java.io.InputStream in = context.getAssets().open(CUSTOM_LABEL_ASSET);
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) >= 0) all.write(chunk, 0, read);
            in.close();
            String text = new String(all.toByteArray(), "UTF-8").trim();
            if (text.length() > 0) customLabel = text;
        } catch (Throwable ignored) {
        }
        return customLabel;
    }

    /** The switch by the custom icon: whether the app goes by the baked name. */
    public static boolean customNameOn() {
        SharedPreferences prefs = prefs();
        return prefs == null || prefs.getBoolean(KEY_NAME, true);
    }

    public static void setCustomName(Context context, boolean on) {
        SharedPreferences prefs = prefs();
        if (prefs != null) prefs.edit().putBoolean(KEY_NAME, on).apply();
        // the same choice, pointing at the entry with the other name
        choose(context, chosen());
    }

    public static String[] all(Context context) {
        int extra = hasCustom(context) ? 2 : 1;
        String[] out = new String[Shots.KEYS.length + extra];
        out[0] = DEFAULT;
        System.arraycopy(Shots.KEYS, 0, out, 1, Shots.KEYS.length);
        if (extra == 2) out[out.length - 1] = CUSTOM;
        return out;
    }

    public static String nameOf(String which) {
        if (CUSTOM.equals(which)) return Text.ICON_CUSTOM;
        int at = indexOf(which);
        return at < 0 ? Text.ICON_DEFAULT : Local.of(Shots.LABELS[at]);
    }

    public static String chosen() {
        SharedPreferences prefs = prefs();
        String which = prefs == null ? DEFAULT : prefs.getString(KEY, DEFAULT);
        if (CUSTOM.equals(which)) return CUSTOM;
        return indexOf(which) < 0 ? DEFAULT : which;
    }

    /**
     * TikTok is about to start a screen. Given what it is starting.
     *
     * With another icon chosen the entry TikTok came with is switched off --
     * that is the only way to take its icon off the home screen -- and that
     * entry is the very screen TikTok starts by name when it restarts
     * itself: after signing in, after switching accounts, after a change of
     * theme. Starting what is switched off is refused, and the refusal was a
     * crash. The icon that was chosen is another name for the same screen,
     * so the start is sent there.
     */
    public static void route(android.content.Intent intent) {
        try {
            ComponentName wanted = intent == null ? null : intent.getComponent();
            if (wanted == null || !Shots.DEFAULT.equals(wanted.getClassName())) return;
            Context context = Margy.context();
            String live = component(context, chosen());
            if (Shots.DEFAULT.equals(live)) return;
            intent.setComponent(new ComponentName(wanted.getPackageName(), live));
        } catch (Throwable ignored) {
            // started as TikTok asked
        }
    }

    /** The component a choice resolves to, given the name switch. */
    private static String component(Context context, String which) {
        boolean named = context != null && customNameOn() && hasCustomName(context);
        if (CUSTOM.equals(which)) {
            if (context == null || !hasAlias(context)) return Shots.DEFAULT;
            return named ? ICON_CUSTOM_NAMED : ICON_CUSTOM;
        }
        int at = indexOf(which);
        if (at >= 0 && at < Shots.COMPONENTS.length) return Shots.COMPONENTS[at];
        return named && hasAlias(context) ? ICON_CUSTOM_NAMED : Shots.DEFAULT;
    }

    /** Whether this apk has the patcher's entries at all (a 1.6+ build). */
    private static boolean hasAlias(Context context) {
        if (aliasThere != null) return aliasThere;
        boolean there = false;
        try {
            context.getPackageManager().getActivityInfo(
                    new ComponentName(context.getPackageName(), ICON_CUSTOM),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            there = true;
        } catch (Throwable ignored) {
        }
        aliasThere = there;
        return there;
    }

    private static Boolean aliasThere;

    /**
     * Turn on the one that was chosen, and everything else off.
     *
     * DONT_KILL_APP matters: without it Android stops the app the moment one
     * of its components is switched, which from the inside looks like the mod
     * crashing the phone's TikTok for changing a picture.
     */
    public static void choose(Context context, String which) {
        if (context == null) return;
        if (indexOf(which) < 0 && !CUSTOM.equals(which)) which = DEFAULT;
        try {
            PackageManager packages = context.getPackageManager();
            String self = context.getPackageName();
            String live = component(context, which);

            for (int i = 0; i < Shots.COMPONENTS.length; i++) {
                set(packages, self, Shots.COMPONENTS[i],
                        Shots.COMPONENTS[i].equals(live));
            }
            set(packages, self, Shots.DEFAULT, Shots.DEFAULT.equals(live));
            // the patcher's entries: only in apks it wrote, so a missing one
            // is a name PackageManager does not know and nothing worse
            set(packages, self, ICON_CUSTOM, ICON_CUSTOM.equals(live));
            set(packages, self, ICON_CUSTOM_NAMED, ICON_CUSTOM_NAMED.equals(live));

            SharedPreferences prefs = prefs();
            if (prefs != null) prefs.edit().putString(KEY, which).apply();
            Diary.note("icon: " + (DEFAULT.equals(which) ? "back to TikTok's" : which));
        } catch (Throwable error) {
            Diary.note("icon: " + error);
        }
    }

    private static void set(PackageManager packages, String self,
                            String component, boolean on) {
        try {
            packages.setComponentEnabledSetting(
                    new ComponentName(self, component),
                    on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                       : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
            // this apk simply does not have the entry
        }
    }

    /** The picture for the settings to show, decoded once and kept. */
    public static Bitmap preview(String which) {
        return preview(null, which);
    }

    /** The same, with a context for the patcher's asset to be read through. */
    public static Bitmap preview(Context context, String which) {
        if (which == null) return null;
        if (CUSTOM.equals(which)) {
            synchronized (drawn) {
                Bitmap known = drawn.get(which);
                if (known != null) return known;
                try {
                    if (context == null) return null;
                    java.io.InputStream in = context.getAssets().open(CUSTOM_ASSET);
                    java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                    byte[] chunk = new byte[16384];
                    int read;
                    while ((read = in.read(chunk)) >= 0) all.write(chunk, 0, read);
                    in.close();
                    byte[] raw = all.toByteArray();
                    Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    if (bitmap != null) drawn.put(which, bitmap);
                    return bitmap;
                } catch (Throwable error) {
                    Diary.note("icon: " + error);
                    return null;
                }
            }
        }
        int at = indexOf(which);
        String[] png = at < 0 ? Shots.DEFAULT_PNG : Shots.PNG[at];
        synchronized (drawn) {
            Bitmap known = drawn.get(which);
            if (known != null) return known;
            try {
                StringBuilder whole = new StringBuilder();
                for (String piece : png) whole.append(piece);
                byte[] raw = Base64.decode(whole.toString(), Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (bitmap != null) drawn.put(which, bitmap);
                return bitmap;
            } catch (Throwable error) {
                Diary.note("icon: " + error);
                return null;
            }
        }
    }

    private static final Map<String, Bitmap> drawn = new HashMap<String, Bitmap>();

    private static int indexOf(String which) {
        if (which == null) return -1;
        for (int i = 0; i < Shots.KEYS.length; i++) {
            if (Shots.KEYS[i].equals(which)) return i;
        }
        return -1;
    }

    private static SharedPreferences prefs() {
        Context context = Margy.context();
        if (context == null) return null;
        return context.getSharedPreferences(Margy.PREFS, Context.MODE_PRIVATE);
    }
}
