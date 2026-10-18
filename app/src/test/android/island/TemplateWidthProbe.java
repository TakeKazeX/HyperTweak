import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandCameraSeat;
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandTemplateWidthLease;
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandRelayoutDriver;
import com.takekazex.hypertweak.hook.rules.systemui.island.template.IslandLayoutInvalidation;
import kotlin.Unit;
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.IslandContentSize;
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.PortraitIslandLayout;
import com.takekazex.hypertweak.hook.rules.systemui.island.policy.layout.ThreeIslandLayout;
/** Standalone app_process regression using real Android layout; no window or device UI interaction.
 * Geometry follows the verified native standard template: equal area weights and a camera seat.
 */
public class TemplateWidthProbe {
 static void layout(LinearLayout parent) {
  parent.measure(View.MeasureSpec.makeMeasureSpec(309,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));
  parent.layout(0,0,309,100);
 }
 public static void main(String[] args) throws Exception {
  if(Looper.myLooper()==null) Looper.prepareMainLooper();
  Class<?> at=Class.forName("android.app.ActivityThread");
  java.lang.reflect.Constructor<?> ctor=at.getDeclaredConstructor(); ctor.setAccessible(true);
  Object thread=ctor.newInstance();
  Class<?> impl=Class.forName("android.app.ContextImpl");
  java.lang.reflect.Method create=impl.getDeclaredMethod("createSystemContext",at);create.setAccessible(true);
  Context ctx=(Context)create.invoke(null,thread);
  LinearLayout parent=new LinearLayout(ctx);parent.setOrientation(LinearLayout.HORIZONTAL);
  FrameLayout left=new FrameLayout(ctx), cutout=new FrameLayout(ctx),right=new FrameLayout(ctx);
  parent.addView(left,new LinearLayout.LayoutParams(93,100,1));
  parent.addView(cutout,new LinearLayout.LayoutParams(59,100));
  parent.addView(right,new LinearLayout.LayoutParams(216,100,1));
  layout(parent);int before=right.getMeasuredWidth();
  if(before>=216)throw new AssertionError("native weighted shrink not reproduced "+before);
  IslandTemplateWidthLease lease=new IslandTemplateWidthLease();Object owner=new Object();
  lease.acquire(owner,left,right,0);lease.acquire(owner,left,right,0);layout(parent);
  if(right.getMeasuredWidth()!=216||left.getMeasuredWidth()!=93||cutout.getMeasuredWidth()!=0)throw new AssertionError("repair widths "+left.getMeasuredWidth()+","+cutout.getMeasuredWidth()+","+right.getMeasuredWidth());
  // Folme onBegin now reads getFixedCutoutWidth from both real and fake owners.
  // It writes the final target directly, with no module pre-draw repair listener.
  cutout.getLayoutParams().width=0;
  // dispatchOnPreDraw() returns true when a listener cancels the draw; no module listener may.
  if(parent.getViewTreeObserver().dispatchOnPreDraw())throw new AssertionError("lease blocked native drawing");
  layout(parent);
  if(cutout.getMeasuredWidth()!=0||right.getMeasuredWidth()!=216)throw new AssertionError("native callback geometry changed");
  lease.releaseAll(key -> null);layout(parent);
  if(((LinearLayout.LayoutParams)left.getLayoutParams()).weight!=1||cutout.getLayoutParams().width!=59||right.getMeasuredWidth()!=before)throw new AssertionError("restore failed");
  // Rotation must use the new native portrait cutout, not the landscape snapshot.
  lease.acquire(owner,left,right,0);
  lease.release(owner,72);layout(parent);
  if(cutout.getLayoutParams().width!=72)throw new AssertionError("landscape width leaked into portrait");
  // A prior full-template generation can leave a zero placeholder before acquisition.
  cutout.getLayoutParams().width=0;
  lease.acquire(owner,left,right,0);
  lease.release(owner,72);layout(parent);
  if(cutout.getLayoutParams().width!=72)throw new AssertionError("zero snapshot hid portrait camera seat");
  cutout.getLayoutParams().width=0;
  new IslandTemplateWidthLease().restoreCutout(left,right,72);layout(parent);
  if(cutout.getLayoutParams().width!=72)throw new AssertionError("hot reload inherited camera seat not repaired");
  ThreeIslandLayout portrait=PortraitIslandLayout.INSTANCE.calculate(1200,2608,170,170,new IslandContentSize(93,216),72,100,12,true,true,0,0,100);
  if(portrait==null)throw new AssertionError("portrait row unavailable");
  for(int rotation=0;rotation<3;rotation++) {
   lease.acquire(owner,left,right,72);
   parent.measure(View.MeasureSpec.makeMeasureSpec(portrait.getBigWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));
   parent.layout(0,0,portrait.getBigWidth(),100);
   if(portrait.getBigX()+cutout.getLeft()+cutout.getMeasuredWidth()/2!=600||left.getMeasuredWidth()!=93||right.getMeasuredWidth()!=216)
    throw new AssertionError("portrait camera or content widths shifted");
   lease.acquire(owner,left,right,0);layout(parent);
   if(right.getMeasuredWidth()!=216||cutout.getMeasuredWidth()!=0)throw new AssertionError("landscape transition failed");
  }
  lease.release(owner,72);
  if(IslandCameraSeat.INSTANCE.holds(parent))throw new AssertionError("main template released camera seat");
  lease.acquireMedia(owner,left,right);
  if(!IslandCameraSeat.INSTANCE.holds(parent))throw new AssertionError("media lease did not hold camera seat");
  lease.release(owner,72);layout(parent);
  if(IslandCameraSeat.INSTANCE.holds(parent)||IslandCameraSeat.INSTANCE.tracked()!=0)throw new AssertionError("camera seat leaked after media release");
  System.out.println("TEMPLATE_CAMERA_SEAT_OK mainReleased=true mediaHeld=true mediaReleased=true tracked=0");
  // Native content sits below several unchanged FrameLayout/LightBgView ancestors.
  // Manual premeasurement can leave a dirty child beneath clean cached ancestors.
  FrameLayout outer=new FrameLayout(ctx),middle=new FrameLayout(ctx),wrapper=new FrameLayout(ctx);
  FrameLayout template=new FrameLayout(ctx);
  outer.addView(middle,new FrameLayout.LayoutParams(-1,100));
  middle.addView(wrapper,new FrameLayout.LayoutParams(-1,100));
  wrapper.addView(template,new FrameLayout.LayoutParams(315,100));
  outer.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));
  outer.layout(0,0,1200,100);
  template.getLayoutParams().width=381;template.forceLayout();outer.requestLayout();
  outer.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));
  outer.layout(0,0,1200,100);
  if(template.getWidth()!=315)throw new AssertionError("cached ancestor regression not reproduced "+template.getWidth());
  IslandLayoutInvalidation.INSTANCE.request(template);
  outer.measure(View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY));
  outer.layout(0,0,1200,100);
  if(template.getWidth()!=381)throw new AssertionError("ancestor chain did not remeasure "+template.getWidth());
  System.out.println("ANDROID_CACHED_ANCESTOR_REPAIR_OK stale=315 target=381 actual="+template.getWidth());
  final int[] prepared={0},published={0};
  final IslandRelayoutDriver[] driver=new IslandRelayoutDriver[1];
  driver[0]=new IslandRelayoutDriver(parent,()->true,()->{
   prepared[0]++;
   if(prepared[0]==1)driver[0].request(); // Fake binding finishes during the old preparation.
   return Unit.INSTANCE;
  },()->{published[0]++;parent.requestLayout();return Unit.INSTANCE;},error->{throw new AssertionError(error);});
  driver[0].request();layout(parent);
  if(driver[0].onPreDraw()||published[0]!=0)throw new AssertionError("published before layout barrier");
  layout(parent);
  if(driver[0].onPreDraw()||prepared[0]!=2||published[0]!=0)throw new AssertionError("stale generation published");
  layout(parent);
  if(driver[0].onPreDraw()||published[0]!=1)throw new AssertionError("draw allowed before native publication layout");
  layout(parent);if(!driver[0].onPreDraw())throw new AssertionError("committed layout was blocked");
  driver[0].request();layout(parent);driver[0].onPreDraw();driver[0].retire();layout(parent);driver[0].onPreDraw();
  if(published[0]!=1)throw new AssertionError("retired publication ran");
  System.out.println("FRAME_RELAYOUT_DRIVER_OK prepared="+prepared[0]+" published=1 lateBindingInvalidated=true retiredCancelled=true");
  System.out.println("CAMERA_ANCHORED_PORTRAIT_OK width="+portrait.getBigWidth()+" left=93 right=216 cameraCentre=600 removedMirroredBlank=123 rotations=3");
  System.out.println("PORTRAIT_CUTOUT_RESTORE_OK landscape=59 portrait=72 staleZeroRestored=72 inheritedZeroRestored=72");
  System.out.println("ANDROID_TEMPLATE_WIDTH_OK before="+before+" repaired=216 originalSnapshotRestored="+before);
  System.exit(0);
 }
}
