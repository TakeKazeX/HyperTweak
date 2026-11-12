import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Icon;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Production stacked renderer and holder publication with detached host-shaped controller. */
public final class StackedArtworkReloadProbe {
    private static final String H = "com.takekazex.hypertweak.hook.rules.systemui.icon.";
    private static final String P = "com.takekazex.hypertweak.presentation.icon.";
    private static final String C = "com.takekazex.hypertweak.settings.contract.";
    public static final class SlotList {
        final Map<String, Object> holders = new HashMap<>();
        public Object getIconHolder(int tag, String slot) { return holders.get(slot); }
    }
    public static final class Controller {
        public final SlotList mStatusBarIconList = new SlotList();
        private final Context context;
        int publications;
        Bitmap bitmap;
        Controller(Context context) { this.context = context; }
        public void setIcon(String slot, Object holder) throws Exception {
            mStatusBarIconList.holders.put(slot, holder);
            Object status = field(holder, "icon").get(holder);
            Icon icon = (Icon) field(status, "icon").get(status);
            bitmap = ((BitmapDrawable) icon.loadDrawable(context)).getBitmap();
            publications++;
        }
        public void setIconVisibility(String slot, boolean visible) throws Exception {
            Object status = field(mStatusBarIconList.holders.get(slot), "icon").get(mStatusBarIconList.holders.get(slot));
            field(status, "visible").set(status, visible);
        }
        public void removeAllIconsForSlot(String slot, boolean external) { mStatusBarIconList.holders.remove(slot); }
    }
    private static Field field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object privateCall(Object owner, String name, Class<?>[] params, Object... args) throws Exception {
        Method m = owner.getClass().getDeclaredMethod(name, params); m.setAccessible(true); return m.invoke(owner, args);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        android.os.Looper.prepareMainLooper();
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        run(context, new dalvik.system.PathClassLoader(args[0], ClassLoader.getSystemClassLoader()));
    }
    public static void run(Context context, ClassLoader module) throws Exception {
        Context hostContext = context.createPackageContext("com.android.systemui", Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
        ClassLoader host = hostContext.getClassLoader();
        Class<?> flows = module.loadClass(H + "IconTunerFlows");
        flows.getMethod("init", ClassLoader.class).invoke(flows.getField("INSTANCE").get(null), host);
        Object renderer = module.loadClass(H + "StackedRenderer").getField("INSTANCE").get(null);
        Controller controller = new Controller(hostContext);
        Object bridge = module.loadClass(H + "HostIconBridge").getConstructor(Object.class, ClassLoader.class).newInstance(controller, host);
        privateCall(renderer, "setIconBridge", new Class<?>[]{bridge.getClass()}, bridge);
        privateCall(renderer, "setHostContext", new Class<?>[]{Context.class}, hostContext);
        privateCall(renderer, "setEnabled", new Class<?>[]{boolean.class}, true);
        privateCall(renderer, "setRenderStacked", new Class<?>[]{boolean.class}, true);
        Object policy = module.loadClass(C + "IconSlotPolicyConfig").getConstructor().newInstance();
        Class<?> optionsType = module.loadClass(C + "IconTunerSnapshot");
        Object options = optionsType.getConstructor(policy.getClass(), boolean.class, boolean.class, boolean.class, int.class)
            .newInstance(policy, true, false, false, 0);
        privateCall(renderer, "setOptions", new Class<?>[]{optionsType}, options);
        Context assetsContext = context.createPackageContext("com.takekazex.hypertweak", Context.CONTEXT_IGNORE_SECURITY);
        Class<?> repositoryType = module.loadClass(P + "IconSvgRepository");
        Object repository = repositoryType.getConstructor(Context.class).newInstance(assetsContext);
        Object loads = field(renderer, "assetLoads").get(renderer);
        Class<?> styleType = module.loadClass(C + "SignalSvgStyle");
        Class<?> ticketType = module.loadClass(H + "SignalAssetLoadState$Ticket");
        Class<?> stateType = module.loadClass(P + "MobileSignalState"), eventType = module.loadClass(P + "MobileSignalEvent");
        Method reduce = stateType.getMethod("reduce", eventType);
        Object modelFactory = module.loadClass(P + "MobileSignalModel").getField("Companion").get(null);
        Method cellular = modelFactory.getClass().getMethod("cellular", int.class, int.class, boolean.class, boolean.class);
        int cases = 0;
        for (List<Integer> ids : List.of(List.of(11), List.of(11, 22))) {
            Object state = stateType.getConstructor().newInstance();
            state = reduce.invoke(state, module.loadClass(P + "MobileSignalEvent$Subscriptions").getConstructor(List.class).newInstance(ids));
            for (int id : ids) {
                Object model = cellular.invoke(modelFactory, id == 11 ? 3 : 1, 5, false, false);
                Object event = module.loadClass(P + "MobileSignalEvent$SignalModel")
                    .getConstructor(int.class, model.getClass(), Integer.class).newInstance(id, model, null);
                state = reduce.invoke(state, event);
            }
            Bitmap first = null;
            for (String name : List.of("HYPEROS3", "IOS27", "HYPEROS3")) {
                Object style = Enum.valueOf((Class) styleType, name);
                long generation = (Long) loads.getClass().getMethod("advance", styleType).invoke(loads, style);
                int previous = controller.publications;
                Bitmap retained = controller.bitmap;
                // Hold the old drawable while the selected artwork is still loading.
                if (retained != null) {
                    Object pending = privateCall(renderer, "renderStackedSlot", new Class<?>[]{stateType, boolean.class}, state, true);
                    check(pending.toString().equals("KEEP") && controller.publications == previous && controller.bitmap == retained,
                        "loading new artwork retains the published holder");
                }
                Object ticket = loads.getClass().getMethod("begin", long.class, long.class).invoke(loads, generation, 0L);
                Object assets = privateCall(renderer, "loadSignalAssets", new Class<?>[]{repositoryType, ticketType}, repository, ticket);
                check(assets != null, "bundled selected artwork loads");
                check((Boolean) loads.getClass().getMethod("complete", ticketType, Object.class, long.class).invoke(loads, ticket, assets, 1L), "selected assets accepted");
                Object outcome = privateCall(renderer, "renderStackedSlot", new Class<?>[]{stateType, boolean.class}, state, true);
                check(outcome.toString().equals("PUBLISHED"), "selected artwork is published");
                check(controller.publications == previous + 1, "artwork switch at unchanged levels must republish " + name + " SIMs=" + ids.size());
                if (name.equals("IOS27")) check(!controller.bitmap.sameAs(first), "different artwork changes real pixels");
                else if (first == null) first = controller.bitmap;
                else check(controller.bitmap.sameAs(first), "reverse artwork switch restores original pixels");
                int stable = controller.publications;
                privateCall(renderer, "renderStackedSlot", new Class<?>[]{stateType, boolean.class}, state, true);
                check(controller.publications == stable, "unchanged artwork and levels reuse the existing bitmap");
                cases++;
            }
        }
        System.out.println("PASS stacked_artwork_reload fixed_levels=6 single_and_dual=true retained_while_loading=true idle_republish=0");
    }
}
