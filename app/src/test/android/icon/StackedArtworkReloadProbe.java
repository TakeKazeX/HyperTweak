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
        boolean failNext;
        Bitmap bitmap;
        Controller(Context context) { this.context = context; }
        public void setIcon(String slot, Object holder) throws Exception {
            if (failNext) { failNext = false; throw new IllegalStateException("injected publication failure"); }
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
    private static void checkOwnership(Context context, ClassLoader host, ClassLoader module) throws Exception {
        Class<?> type = module.loadClass(H + "HostIconBridge");
        Controller controller = new Controller(context);
        Object old = type.getConstructor(Object.class, ClassLoader.class).newInstance(controller, host);
        Object next = type.getConstructor(Object.class, ClassLoader.class).newInstance(controller, host);
        Method publish = type.getMethod("publish", String.class, Bitmap.class, CharSequence.class);
        Method remove = type.getMethod("removeOwned", String.class);
        Method cleanup = type.getMethod("removeRetiredModuleHolder", String.class);
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        String own = "hypertweak_stacked_mobile_icon", shared = "stacked_mobile_icon";
        check((Boolean) publish.invoke(old, own, bitmap, "signal"), "first generation owns reserved slot");
        check((Boolean) publish.invoke(next, own, bitmap, "signal"), "next generation adopts reserved slot");
        check(!(Boolean) remove.invoke(old, own), "old generation cannot delete the new icon identity");
        Object holder = controller.mStatusBarIconList.holders.get(own);
        Object status = field(holder, "icon").get(holder);
        field(status, "pkg").set(status, "other.module");
        controller.failNext = true;
        check(!(Boolean) publish.invoke(next, own, bitmap, "signal"), "publication failure rejects update");
        check("other.module".equals(field(field(holder, "icon").get(holder), "pkg").get(field(holder, "icon").get(holder))),
            "failed reserved-slot takeover restores the previous foreign payload");
        check((Boolean) publish.invoke(next, own, bitmap, "signal"), "reserved slot does not yield to a foreign ordinary holder");
        check("com.takekazex.hypertweak".equals(field(field(holder, "icon").get(holder), "pkg").get(field(holder, "icon").get(holder))),
            "reserved-slot takeover verifies module package");
        check((Boolean) publish.invoke(old, shared, bitmap, "old signal"), "seed retired module holder");
        check((Boolean) cleanup.invoke(next, shared), "upgrade removes own retired holder without generation ownership");
        check((Boolean) cleanup.invoke(next, shared), "upgrade cleanup is idempotent");
        check((Boolean) publish.invoke(old, shared, bitmap, "foreign signal"), "seed shared holder");
        Object foreign = controller.mStatusBarIconList.holders.get(shared);
        Object foreignStatus = field(foreign, "icon").get(foreign);
        field(foreignStatus, "pkg").set(foreignStatus, "other.module");
        check((Boolean) cleanup.invoke(next, shared) && controller.mStatusBarIconList.holders.get(shared) == foreign,
            "retired cleanup leaves foreign shared-name holders intact");
        check(!(Boolean) publish.invoke(next, shared, bitmap, "signal"), "shared foreign names remain unmanaged");
        field(holder, "type").setInt(holder, 3);
        check(!(Boolean) publish.invoke(next, own, bitmap, "signal"), "unknown native holder shape is rejected");
        System.out.println("PASS independent_slot_ownership=true retired_cleanup=true foreign_shared_untouched=true rollback=true generation_identity=true");
    }
    public static void run(Context context, ClassLoader module) throws Exception {
        Context hostContext = context.createPackageContext("com.android.systemui", Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
        hostContext = new android.content.ContextWrapper(hostContext) {
            @Override public Context getApplicationContext() { return this; }
        };
        ClassLoader host = hostContext.getClassLoader();
        Class<?> flows = module.loadClass(H + "IconTunerFlows");
        flows.getMethod("init", ClassLoader.class).invoke(flows.getField("INSTANCE").get(null), host);
        checkOwnership(hostContext, host, module);
        checkDuoMorphCache(hostContext, module);
        Object renderer = module.loadClass(H + "StackedRenderer").getField("INSTANCE").get(null);
        Controller controller = new Controller(hostContext);
        Object bridge = module.loadClass(H + "HostIconBridge").getConstructor(Object.class, ClassLoader.class).newInstance(controller, host);
        privateCall(renderer, "setIconBridge", new Class<?>[]{bridge.getClass()}, bridge);
        privateCall(renderer, "setHostContext", new Class<?>[]{Context.class}, hostContext);
        privateCall(renderer, "setEnabled", new Class<?>[]{boolean.class}, true);
        privateCall(renderer, "setRenderStacked", new Class<?>[]{boolean.class}, true);
        Object policy = module.loadClass(C + "IconSlotPolicyConfig").getConstructor().newInstance();
        Class<?> optionsType = module.loadClass(C + "IconTunerSnapshot");
        Object options = optionsType.getConstructor(policy.getClass(), boolean.class, boolean.class, boolean.class, int.class, int.class)
            .newInstance(policy, true, false, false, 0, 0);
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

    private static void checkDuoMorphCache(Context context, ClassLoader module) throws Exception {
        Object artwork = module.loadClass(H + "duo.DuoSignalArtwork").getConstructor().newInstance();
        Method morph = artwork.getClass().getMethod("rowMorph", Context.class, int.class, int.class);
        Object single = morph.invoke(artwork, context, 0, 1);
        Object dual = morph.invoke(artwork, context, 0, 2);
        check(single != null && dual != null, "single and dual signal contours load");
        long start = System.nanoTime();
        boolean reused = true;
        for (int i = 0; i < 100; i++) {
            reused &= morph.invoke(artwork, context, 0, 1) == single;
            reused &= morph.invoke(artwork, context, 0, 2) == dual;
        }
        long elapsed = System.nanoTime() - start;
        System.out.println("DUO_MORPH_ALTERNATION calls=200 reused=" + reused + " elapsed_ns=" + elapsed);
        check(reused, "Wi-Fi dots and dual-SIM bars must retain separate contour caches through every frame");
        Field settingField = module.loadClass(H + "duo.DuoSignalArtworkKt")
            .getDeclaredField("settingIconStackedWeight$delegate");
        settingField.setAccessible(true);
        Object setting = settingField.get(null);
        Field value = field(setting, "value");
        Object originalWeight = value.get(setting);
        try {
            value.set(setting, ((Number) originalWeight).floatValue() == .5f ? .75f : .5f);
            Object weightedSingle = morph.invoke(artwork, context, 0, 1);
            Object weightedDual = morph.invoke(artwork, context, 0, 2);
            check(weightedSingle != single && weightedDual != dual, "weight changes invalidate both row families");
            check(morph.invoke(artwork, context, 0, 1) == weightedSingle, "weighted single contours are reused");
            check(morph.invoke(artwork, context, 0, 2) == weightedDual, "weighted dual contours are reused");
        } finally { value.set(setting, originalWeight); }
        single = morph.invoke(artwork, context, 0, 1);
        artwork.getClass().getMethod("retire").invoke(artwork);
        check(morph.invoke(artwork, context, 0, 1) != single, "retirement releases cached contours");
        System.out.println("PASS duo_morph_cache single_and_dual=true alternating_frames=100 weight_invalidation=true retirement=true");
    }
}
