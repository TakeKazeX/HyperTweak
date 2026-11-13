import android.os.Debug;
import com.takekazex.hypertweak.hook.base.RetiringWorker;
import com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.ShellMoveQueue;
import com.takekazex.hypertweak.hook.rules.backgesture.hooks.systemui.barfollow.DiagnosticBindings;
import java.util.*;
import java.util.concurrent.*;
import kotlin.Unit;

/** Component ownership/queue checks on ART; no windows, hooks, or real Shell dispatch. */
public final class LifecyclePerformanceProbe {
    private static int threads() {
        return (int)Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().startsWith("HT-PerfWorker")).count();
    }
    private static void check(boolean b,String why) {if(!b)throw new AssertionError(why);}
    private static long bytes() {return Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated"));}
    private static final class Serial implements Executor {
        final ArrayDeque<Runnable> tasks=new ArrayDeque<>(); int submissions,peak;
        public void execute(Runnable r){tasks.add(r);submissions++;peak=Math.max(peak,tasks.size());}
        void drain(){while(!tasks.isEmpty())tasks.remove().run();}
    }
    public static void main(String[] args) throws Exception {
        for(int i=0;i<8;i++) {
            RetiringWorker worker=new RetiringWorker("HT-PerfWorker-"+i,1L);
            CountDownLatch done=new CountDownLatch(1);
            check(worker.execute(()->{done.countDown();return Unit.INSTANCE;}),"worker start");
            check(done.await(1,TimeUnit.SECONDS),"worker execution");
            worker.close();
            check(!worker.execute(()->Unit.INSTANCE),"retired admission");
        }
        for(int i=0;i<100 && threads()!=0;i++)Thread.sleep(10);
        check(threads()==0,"retired threads remain");
        RetiringWorker idle=new RetiringWorker("HT-PerfWorker-idle",1L);
        CountDownLatch done=new CountDownLatch(1);
        idle.execute(()->{done.countDown();return Unit.INSTANCE;});check(done.await(1,TimeUnit.SECONDS),"idle start");
        Thread.sleep(1300);check(threads()==0,"idle thread timeout");
        CountDownLatch rearm=new CountDownLatch(1);
        check(idle.execute(()->{rearm.countDown();return Unit.INSTANCE;}),"idle rearm");
        check(rearm.await(1,TimeUnit.SECONDS),"idle execution rearm");idle.close();
        System.out.println("PASS worker_8_retire_cycles threads=0 idle_timeout rearm rejected_retired");
        DiagnosticBindings diagnostics=new DiagnosticBindings(handle->{});
        for(int i=0;i<20;i++) {
            diagnostics.setEnabled(false,retain->{throw new AssertionError("off creates collector");});
            check(diagnostics.size()==0,"off collectors");
            diagnostics.setEnabled(true,retain->{for(int j=0;j<3;j++)retain.accept(new Object());});
            check(diagnostics.size()==3,"on collectors");diagnostics.close();
        }
        check(diagnostics.size()==0,"diagnostic retirement");
        System.out.println("PASS diagnostic_20_cycles off=0 on=3 retired=0");
        // Old admission shape is a labeled model, not a claim about measured host gesture latency.
        System.out.println("case,inputs,submitted,peak_queue,consumed,allocated_bytes");
        for(int round=0;round<3;round++) {
            Serial old=new Serial(); final float[] last={0};
            long before=bytes();
            for(int i=0;i<1000;i++){final float x=i; old.execute(()->last[0]=x);}
            long used=bytes()-before;old.drain();
            check(last[0]==999,"old final sample");
            System.out.println("old_per_event_model,1000,"+old.submissions+","+old.peak+",1000,"+used);
            Serial serial=new Serial();final int[] consumed={0},crossings={0};
            ShellMoveQueue queue=new ShellMoveQueue(serial,(x,y,d,cross,dispatch)->{last[0]=x;consumed[0]++;if(cross)crossings[0]++;});
            before=bytes();
            for(int i=0;i<1000;i++)queue.submit(i,0,i,i==300,true);
            used=bytes()-before;queue.seal();serial.drain();
            check(last[0]==999 && crossings[0]==1,"coalesced crossing and final sample");
            System.out.println("production_move_queue,1000,"+serial.submissions+","+serial.peak+","+consumed[0]+","+used);
        }
    }
}
