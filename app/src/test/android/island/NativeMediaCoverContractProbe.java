import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;

/** Isolated app_process reflection probe; never attaches to SystemUI or operates its UI. */
public final class NativeMediaCoverContractProbe {
    public static void main(String[] args) throws Exception {
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        ClassLoader systemUi = new PathClassLoader(args[0], boot);
        ClassLoader plugin = new PathClassLoader(args[1], systemUi);
        Class<?> utility = systemUi.loadClass(
                "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaAlbumAnimationUtils");
        Class<?> contract = Class.forName(
                "com.takekazex.hypertweak.hook.rules.systemui.island.host.NativeMediaCoverContract");
        Object resolved = contract.getConstructor(ClassLoader.class, ClassLoader.class).newInstance(plugin, systemUi);
        Method bind = (Method) contract.getMethod("getBind").invoke(resolved);
        Object animator = contract.getMethod("animator").invoke(resolved);
        if (animator.getClass() != utility) throw new AssertionError("wrong native animator loader");
        System.out.println("NATIVE_COVER_CONTRACT_OK nested=" + utility.getDeclaredClasses().length
                + " setter=" + bind.getName() + " animator=" + animator.getClass().getName());
    }
}
