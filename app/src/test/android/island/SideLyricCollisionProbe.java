import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.List;
import kotlin.Unit;
import com.takekazex.hypertweak.hook.rules.systemui.island.spectrum.IslandLyricGuard;

/** Uses the installed lyric module's real wrapper and alpha cache, in a detached test tree. */
public class SideLyricCollisionProbe {
    public static void main(String[] args) throws Exception {
        if (Looper.myLooper()==null) Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        var constructor=at.getDeclaredConstructor(); constructor.setAccessible(true);
        Object thread=constructor.newInstance();
        var create=Class.forName("android.app.ContextImpl").getDeclaredMethod("createSystemContext",at);
        create.setAccessible(true); Context context=(Context)create.invoke(null,thread);
        ClassLoader lyrics=new dalvik.system.PathClassLoader(args[0],Object.class.getClassLoader());
        Class<?> wrapperClass=Class.forName("com.juren233.hyperlyricsenhanced.root.island.view.MaxWidthFrameLayout",true,lyrics);
        FrameLayout wrapper=(FrameLayout)wrapperClass.getConstructor(Context.class).newInstance(context);
        wrapperClass.getMethod("setKeepVisible",boolean.class).invoke(wrapper,true);
        wrapper.setTag("HYPERLYRIC_LEFT_VIEW_WRAPPER"); wrapper.setAlpha(0.75f);
        TextView text=new TextView(context); text.setTag("HYPERLYRIC_LEFT_VIEW"); wrapper.addView(text);
        FrameLayout parent=new FrameLayout(context); parent.addView(wrapper);
        View nativeSibling=new View(context); parent.addView(nativeSibling);
        Class<?> collision=Class.forName("com.juren233.hyperlyricsenhanced.root.island.IslandNativeTextCollisionGuard",true,lyrics);
        Class<?> stateClass=Class.forName(collision.getName()+"$State",true,lyrics);
        Object instance=collision.getField("INSTANCE").get(null);
        var stateConstructor=stateClass.getDeclaredConstructor(ViewGroup.class); stateConstructor.setAccessible(true);
        Object state=stateConstructor.newInstance(parent);
        Method track=collision.getDeclaredMethod("updateTrackedWrappers",stateClass,List.class); track.setAccessible(true);
        Method restore=collision.getDeclaredMethod("restoreWrapperAlpha",stateClass); restore.setAccessible(true);
        boolean[] side={true};
        IslandLyricGuard guard=new IslandLyricGuard(List.of(parent),()->side[0],()->Unit.INSTANCE);
        guard.applyNow();
        track.invoke(instance,state,List.of(wrapper)); // Another module samples while suppression is active.
        float captured=wrapper.getAlpha();
        side[0]=false; guard.applyNow(); guard.release();
        restore.invoke(instance,state); // Its next callback must not make the full island transparent again.
        System.out.println("LYRIC_CACHE_SAMPLE suppressedAlpha="+captured+" afterExternalRestore="+wrapper.getAlpha());
        if (captured!=0.75f || wrapper.getAlpha()!=0.75f)
            throw new AssertionError("compact suppression poisoned the real lyric module's originalAlpha cache");
        if (text.getVisibility()!=View.VISIBLE || nativeSibling.getVisibility()!=View.VISIBLE)
            throw new AssertionError("content/native sibling visibility was not restored");
        side[0]=true;
        IslandLyricGuard next=new IslandLyricGuard(List.of(parent),()->side[0],()->Unit.INSTANCE);
        next.applyNow();
        if (text.getVisibility()!=View.GONE || nativeSibling.getVisibility()!=View.VISIBLE)
            throw new AssertionError("keepVisible fallback failed to suppress only lyric content");
        wrapper.setAlpha(0.5f); next.release();
        if (wrapper.getAlpha()!=0.5f)throw new AssertionError("release overwrote another module's alpha update");
        System.out.println("LYRIC_REAL_CACHE_OK original=0.75 restored=0.75 externalAlphaPreserved=0.5 nativeSibling=true");
    }
}
