import android.content.Context;
import java.lang.reflect.*;
import java.util.*;

/** The installed host's actual strength tables, without constructing controllers or windows. */
public final class MobileSignalHostProbe {
    static void run(Context context,ClassLoader cl)throws Exception {
        Context host=context.createPackageContext("com.android.systemui",Context.CONTEXT_IGNORE_SECURITY|Context.CONTEXT_INCLUDE_CODE);
        Class<?> icons=host.getClassLoader().loadClass("com.miui.systemui.statusbar.mobile.TelephonyIcons");
        List<int[]> tables=new ArrayList<>();
        for(String field:new String[]{"TELEPHONY_SIGNAL_STRENGTH","TELEPHONY_SIGNAL_NO_VOICE_STRENGTH"}) {
            Field f=icons.getDeclaredField(field);f.setAccessible(true);tables.add((int[])f.get(null));
        }
        Class<?> display=cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.MobileSignalDisplayModel");
        Object companion=display.getField("Companion").get(null);
        Map<?,?> levels=(Map<?,?>)companion.getClass().getMethod("levels",List.class).invoke(companion,tables);
        Class<?> model=cl.loadClass("com.takekazex.hypertweak.presentation.icon.MobileSignalModel");
        Object factory=model.getField("Companion").get(null);
        Method cellular=factory.getClass().getMethod("cellular",int.class,int.class,boolean.class,boolean.class);
        Method put=display.getMethod("model",model),icon=display.getMethod("icon",Integer.class);
        int cases=0;
        for(int[] table:tables)for(int index=0;index<table.length;index++)for(int raw=0;raw<5;raw++) {
            Object source=display.getConstructor(Map.class).newInstance(levels);
            put.invoke(source,cellular.invoke(factory,raw,5,false,false));
            Object actual=icon.invoke(source,table[index]);
            int expected=Math.round(index*4f/(table.length-1));
            if((Integer)model.getMethod("getLevel").invoke(actual)!=expected)throw new AssertionError("Host final strength lost to generic level");
            put.invoke(source,cellular.invoke(factory,(raw+1)%5,5,false,false));
            actual=icon.invoke(source,table[index]);
            if((Integer)model.getMethod("getLevel").invoke(actual)!=expected)throw new AssertionError("Generic update overwrote host final strength");
            cases++;
        }
        System.out.println("PASS mobile_signal_host tables="+tables.size()+" runtime_ids="+levels.size()+" final_strength_cases="+cases);
    }
}
