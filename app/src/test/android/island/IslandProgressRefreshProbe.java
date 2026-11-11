import android.content.Context;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings;
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts;
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot;
import com.takekazex.hypertweak.hook.rules.systemui.island.host.TemplateAreaPair;
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation;
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandResultGeometry;
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandScene;
import com.takekazex.hypertweak.hook.rules.systemui.island.model.NativeMeasureResult;
import com.takekazex.hypertweak.hook.rules.systemui.island.model.SafeInsets;
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize;
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime;
import com.takekazex.hypertweak.hook.rules.systemui.island.session.TemplateAnimation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Production compact owner and real Android Views, without opening windows or operating UI.
 * Compact geometry belongs to the content view: progress rebinds, real/fake pair replacement
 * and leaving the main slot keep it; only disabling the switch returns the view to native.
 */
public class IslandProgressRefreshProbe {
    static class Pair {
        final LinearLayout parent;
        final FrameLayout left, camera, right;
        Pair(Context context) {
            parent = new LinearLayout(context);
            left = new FrameLayout(context);
            camera = new FrameLayout(context);
            right = new FrameLayout(context);
            parent.addView(left, new LinearLayout.LayoutParams(93, 100, 1));
            parent.addView(camera, new LinearLayout.LayoutParams(72, 100));
            parent.addView(right, new LinearLayout.LayoutParams(216, 100, 1));
        }
        TemplateAreaPair area() { return new TemplateAreaPair(left, right); }
        void weight(float expected) {
            if (((LinearLayout.LayoutParams) left.getLayoutParams()).weight != expected ||
                ((LinearLayout.LayoutParams) right.getLayoutParams()).weight != expected)
                throw new AssertionError("incorrect area ownership: expected weight " + expected);
            if (camera.getLayoutParams().width != 72) throw new AssertionError("camera seat changed");
        }
    }

    static Float offset(IslandRuntime runtime, Object view) {
        Object[] args = {93, 216, 381, 0f, null, null, null, null, view, false, 0};
        TemplateAnimation animation = runtime.getPresentation().bigIslandAnimation(view, args);
        return animation instanceof TemplateAnimation.Redirect
            ? (Float) ((TemplateAnimation.Redirect) animation).getArgs()[3] : null;
    }

    public static void main(String[] args) throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        var ctor = at.getDeclaredConstructor(); ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        var create = Class.forName("android.app.ContextImpl").getDeclaredMethod("createSystemContext", at);
        create.setAccessible(true);
        Context context = (Context) create.invoke(null, thread);
        Object owner = new Object(), download = new Object(), side = new Object();
        FrameLayout root = new FrameLayout(context);
        Pair real = new Pair(context), fake = new Pair(context);
        Pair[] current = {real, fake};
        boolean[] enabled = {true};
        boolean[] main = {true};
        IslandSettings settings = (IslandSettings) Proxy.newProxyInstance(
            IslandProgressRefreshProbe.class.getClassLoader(), new Class[]{IslandSettings.class},
            (proxy, method, values) -> method.getName().equals("getRemovePlaceholder") && enabled[0]);
        IslandScene scene = new IslandScene(1200, 2600, 170, 170, 12, 72, 722, SafeInsets.Companion.getNONE(),
            IslandOrientation.PORTRAIT);
        IslandHostPorts host = (IslandHostPorts) Proxy.newProxyInstance(
            IslandProgressRefreshProbe.class.getClassLoader(), new Class[]{IslandHostPorts.class},
            (proxy, method, values) -> {
                switch (method.getName()) {
                    case "ownerOf": case "handler": return owner;
                    case "isContent": return values[0] == download || values[0] == side;
                    case "key": return values[0] == download ? "download" : "side";
                    case "orientationOf": return IslandOrientation.PORTRAIT;
                    case "smallestScreenWidthDp": return 400;
                    case "isTinyScreen": case "inConfigRebuild": return false;
                    case "showOnce": case "tempShow": return null;
                    case "current":
                        if (values[1] == IslandSlot.BIG) return main[0] ? download : null;
                        if (values[1] == IslandSlot.EXPANDED) return main[0] ? null : download;
                        return values[1] == IslandSlot.SMALL ? side : null;
                    case "queue": return new ArrayList<>();
                    case "fixedCutoutWidth": return 72;
                    case "templateAreas": return values[0] == download ?
                        List.of(current[0].area(), current[1].area()) : List.of();
                    case "bigIslandView": return values[0] == download ? root : null;
                    case "sceneOf": return scene;
                    case "measuredContentSize": return new IslandContentSize(93, 216);
                    case "measuredSmallWidth": return 0;
                    case "nativeResult": return new NativeMeasureResult(0, 0, 0, 0);
                }
                throw new AssertionError("unexpected host call " + method.getName());
            });
        IslandRuntime runtime = new IslandRuntime(host, settings, () -> false, null, null);
        var session = runtime.getSessions().getOrCreate(owner);
        Object input = new Object(), result = new Object();
        IslandResultGeometry first = runtime.getCompact().afterMeasure(download, input, result, null);
        if (first == null || first.getX() != 471 || first.getWidth() != 381)
            throw new AssertionError("compact geometry " + first);
        real.weight(0); fake.weight(0);
        for (int i = 0; i < 100; i++) {
            // The host rebinds the template on every progress update, then measures again.
            runtime.getTemplates().templateBound(download, download);
            runtime.getTemplates().templateBound(side, side);
            if (session.getHealth().getFailed() || !Float.valueOf(61.5f).equals(offset(runtime, download)))
                throw new AssertionError("ordinary progress binding reset published geometry");
            runtime.getCompact().afterMeasure(download, input, result, null);
            real.weight(0); fake.weight(0);
        }
        // Replace only the real pair; the fake pair keeps its lease and native snapshot.
        current[0] = new Pair(context);
        runtime.getCompact().afterMeasure(download, input, result, null);
        real.weight(1); fake.weight(0); current[0].weight(0);
        current[1] = new Pair(context);
        runtime.getCompact().afterMeasure(download, input, result, null);
        fake.weight(1); current[0].weight(0); current[1].weight(0);
        // Expanding (or deleting) clears the main handler before the native animation runs.
        main[0] = false;
        runtime.getPresentation().beforeStateChange(session, download);
        current[0].weight(0); current[1].weight(0);
        if (!Float.valueOf(61.5f).equals(offset(runtime, download)))
            throw new AssertionError("leaving the main slot dropped compact geometry");
        IslandResultGeometry expanded = runtime.getCompact().afterMeasure(download, input, result, null);
        if (expanded == null || expanded.getWidth() != 381) throw new AssertionError("native width written back");
        enabled[0] = false;
        if (runtime.getCompact().afterMeasure(download, input, result, null) != null || offset(runtime, download) != null)
            throw new AssertionError("disable kept compact geometry");
        current[0].weight(1); current[1].weight(1);
        enabled[0] = true;
        runtime.getCompact().afterMeasure(download, input, result, null);
        root.setTranslationX(61.5f);
        runtime.getCompact().retire();
        current[0].weight(1); current[1].weight(1);
        if (root.getTranslationX() != 0f) throw new AssertionError("retire kept compact offset");
        System.out.println("COMPACT_OWNERSHIP_OK repeats=100 translation=61.5 realFakeReplacement=true " +
            "leftMainKept=true nativeRestored=true retired=true");
        System.exit(0);
    }
}
