import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.List;
import kotlin.Unit;
import com.takekazex.hypertweak.hook.rules.systemui.island.spectrum.IslandLyricGuard;

/** Detached real/fake return templates are suppressed before their first visible frame. */
public class SideLyricGuardProbe {
    public static void main(String[] args) throws Exception {
        if(Looper.myLooper()==null) Looper.prepareMainLooper();
        Class<?> at=Class.forName("android.app.ActivityThread");
        var constructor=at.getDeclaredConstructor(); constructor.setAccessible(true);
        Object thread=constructor.newInstance();
        var create=Class.forName("android.app.ContextImpl").getDeclaredMethod("createSystemContext",at);
        create.setAccessible(true);Context ctx=(Context)create.invoke(null,thread);
        FrameLayout real=new FrameLayout(ctx),fake=new FrameLayout(ctx),card=new FrameLayout(ctx);
        TextView realLyric=new TextView(ctx),fakeLyric=new TextView(ctx),cardLyric=new TextView(ctx);
        realLyric.setTag("HYPERLYRIC_LEFT_VIEW_WRAPPER");
        fakeLyric.setTag("HYPERLYRIC_RIGHT_VIEW_WRAPPER");
        cardLyric.setTag("HYPERLYRIC_LEFT_VIEW_WRAPPER");
        real.addView(realLyric);fake.addView(fakeLyric);card.addView(cardLyric);
        boolean[] side={true};int[] reports={0};
        IslandLyricGuard guard=new IslandLyricGuard(List.of(real,fake),()->side[0],()->{reports[0]++;return Unit.INSTANCE;});
        guard.applyNow();
        if(realLyric.getVisibility()!=View.GONE||fakeLyric.getVisibility()!=View.GONE||cardLyric.getVisibility()!=View.VISIBLE)
            throw new AssertionError("side/fake/card suppression scope incorrect");
        // The other module re-enables the fake lyric during app-return.
        fakeLyric.setVisibility(View.VISIBLE);guard.applyNow();
        if(fakeLyric.getVisibility()!=View.GONE||reports[0]!=1)throw new AssertionError("return rebind leaked text");
        side[0]=false;guard.applyNow();
        if(realLyric.getVisibility()!=View.VISIBLE||fakeLyric.getVisibility()!=View.VISIBLE||real.getVisibility()!=View.VISIBLE)
            throw new AssertionError("main promotion did not restore module text");
        side[0]=true;guard.applyNow();guard.release();
        if(realLyric.getVisibility()!=View.VISIBLE||fakeLyric.getVisibility()!=View.VISIBLE)
            throw new AssertionError("retirement did not restore text");
        System.out.println("SIDE_LYRIC_RETURN_OK real=true fake=true detached=true cardPreserved=true restored=true");
    }
}
