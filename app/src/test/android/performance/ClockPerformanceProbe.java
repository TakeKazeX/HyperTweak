import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import dalvik.system.PathClassLoader;
import java.lang.reflect.*;
import java.util.*;

/** Production clock refresh on a controlled, detached clock. No host setting writes. */
public final class ClockPerformanceProbe {
    public static final class Clock extends TextView {
        boolean visible = true; int updates;
        Clock(Context c) { super(c); }
        @Override public boolean isAttachedToWindow() { return true; }
        @Override public boolean isShown() { return visible; }
        @Override public int getWindowVisibility() { return visible ? View.VISIBLE : View.INVISIBLE; }
        @Override public void setText(CharSequence s, BufferType type) { updates++; super.setText(s,type); }
        public void updateTime() { setText("12:00"); }
    }
    private static Method method(Class<?> c,String name,Class<?>...p)throws Exception {
        Method m=c.getDeclaredMethod(name,p);m.setAccessible(true);return m;
    }
    private static void set(Class<?> c,Object owner,String name,Class<?> parameter,Object value)throws Exception {
        try { method(c,"set"+Character.toUpperCase(name.charAt(0))+name.substring(1),parameter).invoke(owner,value); }
        catch(NoSuchMethodException missing) { Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(owner,value); }
    }
    public static void main(String[] args) {
        try { run(args); } catch(Throwable t) { t.printStackTrace(System.out);System.exit(1); }
    }
    private static void run(String[] args)throws Exception {
        Looper.prepareMainLooper();
        android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap").invoke(null);
        Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
        Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
        // Detached ART isn't registered as an application with SettingsProvider. Supply a local,
        // read-only format provider; this measures clock code, never real Binder/storage latency.
        Class<?> settingsSystem=Class.forName("android.provider.Settings$System");
        Field cacheField=settingsSystem.getDeclaredField("sNameValueCache");cacheField.setAccessible(true);
        Object cache=cacheField.get(null);Field holderField=cache.getClass().getDeclaredField("mProviderHolder");
        holderField.setAccessible(true);Object holder=holderField.get(cache);
        Field providerField=holder.getClass().getDeclaredField("mContentProvider");providerField.setAccessible(true);
        Class<?> providerInterface=Class.forName("android.content.IContentProvider");
        Object local=Proxy.newProxyInstance(providerInterface.getClassLoader(),new Class<?>[]{providerInterface},(proxy,m,a)->{
            if(m.getName().equals("call")){Bundle b=new Bundle();b.putString("value","24");return b;}
            if(m.getName().equals("asBinder"))return new Binder();
            if(m.getName().equals("toString"))return "DetachedClockFormatProvider";
            throw new UnsupportedOperationException(m.getName());
        });
        providerField.set(holder,local);
        Context context=new ContextWrapper(system){@Override public Context getApplicationContext(){return null;}};
        Class<?> properties=Class.forName("android.os.SystemProperties");
        properties.getDeclaredMethod("addChangeCallback",Runnable.class);
        properties.getDeclaredMethod("removeChangeCallback",Runnable.class);
        ClassLoader loader=new PathClassLoader(args[0],ClassLoader.getSystemClassLoader());
        Class<?> type=loader.loadClass("com.takekazex.hypertweak.hook.rules.systemui.notification.NotificationHeaderClockSecondsHooker");
        Object hook=type.getField("INSTANCE").get(null);
        set(type,hook,"retiring",boolean.class,false);
        set(type,hook,"clockClass",Class.class,Clock.class);
        set(type,hook,"statusBarClockId",int.class,123);
        set(type,hook,"persistentStatusBarEnabled",boolean.class,true);
        set(type,hook,"updateTimeMethod",Method.class,Clock.class.getMethod("updateTime"));
        Class<?> target=loader.loadClass(type.getName()+"$ClockTarget");Object status=target.getEnumConstants()[0];
        Method refresh=method(type,"refresh",TextView.class,target,boolean.class);
        Clock view=new Clock(context);view.setId(123);view.setLayoutParams(new ViewGroup.LayoutParams(192,64));
        view.layout(0,0,192,64);
        System.out.println("case,round,iterations,wall_ns_op,cpu_ns_op,allocated_bytes_op,render_calls");
        for(boolean visible:new boolean[]{false,true}) {
            view.visible=visible;int n=1000;
            for(int round=-3;round<7;round++) {
                view.updates=0;long b=Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated"));
                long cpu=Debug.threadCpuTimeNanos(),start=System.nanoTime();
                for(int i=0;i<n;i++)refresh.invoke(hook,view,status,false);
                long elapsed=System.nanoTime()-start,usedCpu=Debug.threadCpuTimeNanos()-cpu;
                long used=Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated"))-b;
                if(round>=0)System.out.printf(Locale.US,"clock_%s,%d,%d,%.3f,%.3f,%.3f,%d%n",
                    visible?"visible":"hidden",round,n,(double)elapsed/n,(double)usedCpu/n,(double)used/n,view.updates);
            }
        }
        try {
            method(type,"setInteractive",boolean.class); // New demand gate only; baseline has no such field.
            for(int i=0;i<12;i++) {
                set(type,hook,"interactive",boolean.class,false);view.updates=0;
                refresh.invoke(hook,view,status,false);if(view.updates!=0)throw new AssertionError("screen off render");
                set(type,hook,"interactive",boolean.class,true);
                refresh.invoke(hook,view,status,false);if(view.updates!=1)throw new AssertionError("screen on restore");
                view.visible=false;refresh.invoke(hook,view,status,false);view.updates=0;
                view.visible=true;view.getViewTreeObserver().dispatchOnPreDraw();
                if(view.updates!=1)throw new AssertionError("visible pre-draw restore");
            }
            System.out.println("PASS demand_12_cycles screen_off=0 wake=1 visible_restore=1");
        } catch(NoSuchMethodException baseline) {System.out.println("META baseline_has_no_demand_gate");}
        type.getMethod("onPrepareHotReload").invoke(hook);
        System.out.println("PASS clock_refresh_retirement");
    }
}
