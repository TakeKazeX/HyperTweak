import android.content.Context;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.takekazex.hypertweak.hook.rules.systemui.island.IslandSettings;
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandHostPorts;
import com.takekazex.hypertweak.hook.rules.systemui.island.host.IslandSlot;
import com.takekazex.hypertweak.hook.rules.systemui.island.host.TemplateAreaPair;
import com.takekazex.hypertweak.hook.rules.systemui.island.model.IslandOrientation;
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize;
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout;
import com.takekazex.hypertweak.hook.rules.systemui.island.session.IslandRuntime;
import java.lang.reflect.Proxy;
import java.util.List;

/** Production coordinator and real Android Views, without opening windows or operating UI. */
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
        }
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
        IslandSettings settings = (IslandSettings) Proxy.newProxyInstance(
            IslandProgressRefreshProbe.class.getClassLoader(), new Class[]{IslandSettings.class},
            (proxy, method, values) -> method.getName().equals("getRemovePlaceholder") && enabled[0]);
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
                    case "current": return values[1] == IslandSlot.BIG ? download :
                        values[1] == IslandSlot.SMALL ? side : null;
                    case "fixedCutoutWidth": return 72;
                    case "templateAreas": return values[0] == download ?
                        List.of(current[0].area(), current[1].area()) : List.of();
                    case "bigIslandView": return values[0] == download ? root : null;
                }
                throw new AssertionError("unexpected host call " + method.getName());
            });
        IslandRuntime runtime = new IslandRuntime(host, settings, () -> false, null, null);
        var session = runtime.getSessions().getOrCreate(owner);
        var content = new IslandContentSize(93, 216);
        session.getScene().setLayoutIntent(true);
        session.getScene().setLayout(new ThreeIslandLayout(471, 381, 359, 864, false, 1200));
        session.getScene().setPortraitContent(content);
        session.getPresentation().getFullOrigins().put(download, 1200);
        root.setTranslationX(61.5f);
        for (int i = 0; i < 100; i++) {
            runtime.getTemplates().templateBound(download, download);
            runtime.getTemplates().templateBound(side, side);
            if (session.getHealth().getFailed() || root.getTranslationX() != 61.5f ||
                !runtime.portraitMain(session, download) ||
                !session.getPresentation().getFullOrigins().containsKey(download))
                throw new AssertionError("ordinary progress binding reset published geometry");
            real.weight(0); fake.weight(0);
        }
        // Replace only the real pair; keep the fake pair's original native snapshot.
        current[0] = new Pair(context);
        runtime.getTemplates().templateBound(download, download);
        real.weight(1); fake.weight(0); current[0].weight(0);
        if (root.getTranslationX() != 61.5f) throw new AssertionError("replacement reset origin");
        current[1] = new Pair(context);
        runtime.getTemplates().templateBound(download, new Object());
        fake.weight(1); current[0].weight(0); current[1].weight(0);
        if (root.getTranslationX() != 61.5f) throw new AssertionError("fake replacement reset origin");
        enabled[0] = false;
        runtime.getTemplates().templateBound(download, download);
        current[0].weight(1); current[1].weight(1);
        if (root.getTranslationX() != 0 || session.getScene().getPortraitContent() != null ||
            session.getPresentation().getFullOrigins().containsKey(download))
            throw new AssertionError("disable failed to restore native position");
        System.out.println("PROGRESS_REFRESH_OK repeats=100 translation=61.5 realFakeReplacement=true nativeRestored=true");
        System.exit(0);
    }
}
