import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.FileOutputStream;

/** Whole stacked targets in the non-carrier layout: four/eight dots and central bars, no Window. */
public final class DuoStackedEndpointProbe {
    static final String P=DuoNetworkProbe.P,H=DuoNetworkProbe.H;
    static int[] pixels(Bitmap b){int[] out=new int[b.getWidth()*b.getHeight()];b.getPixels(out,0,b.getWidth(),0,0,b.getWidth(),b.getHeight());return out;}
    static Bitmap draw(View root){Bitmap b=Bitmap.createBitmap(192,224,Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));return b;}
    static void run(Context context,ClassLoader cl,String output)throws Exception{
        Class<?> renderer=cl.loadClass("com.takekazex.hypertweak.presentation.icon.IconSvgRenderer");Object rendering=renderer.getField("INSTANCE").get(null);
        Method parse=Arrays.stream(renderer.getMethods()).filter(m->m.getName().startsWith("parse-")&&m.getParameterCount()==1).findFirst().get();
        Class<?> doc=cl.loadClass("com.takekazex.hypertweak.presentation.icon.IconSvgRenderer$Document");
        Object single=load(context,rendering,parse,"Signal-HyperOS3-Single.svg"),stacked=load(context,rendering,parse,"Signal-HyperOS3-Stacked.svg");
        Class<?> barsType=cl.loadClass(P+"DuoSignalBars");Object bc=barsType.getField("Companion").get(null);Object bars=bc.getClass().getMethod("fromDocument",doc).invoke(bc,single);
        Class<?> morph=cl.loadClass(P+"SignalBarMorph");Object mc=morph.getField("Companion").get(null);Method make=mc.getClass().getMethod("create",doc,doc,int.class,int.class,float.class,float.class);
        Method picture=Arrays.stream(renderer.getMethods()).filter(m->m.getName().equals("signalPicture")&&m.getParameterCount()==5).findFirst().get();
        Class<?> drawable=cl.loadClass(P+"DuoDrawable"),layer=cl.loadClass(P+"DuoRenderLayer"),contentType=cl.loadClass(P+"DuoContent"),targetType=cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.StackedSignalTarget"),ownershipType=cl.loadClass("com.takekazex.hypertweak.hook.rules.systemui.icon.SignalTargetOwnership"),motionType=cl.loadClass(H+"DuoStackedSignalMotion");
        Object composite=Enum.valueOf((Class)layer,"COMPOSITE");
        Method update=motionType.getMethod("update",ViewGroup.class,View.class,drawable,targetType,List.class,barsType,float.class,ownershipType),clear=motionType.getMethod("clear");
        Bitmap sheet=Bitmap.createBitmap(960,8*224,Bitmap.Config.ARGB_8888);Canvas sheetCanvas=new Canvas(sheet);sheetCanvas.drawColor(Color.BLACK);int scenario=0,frames=0;
        for(int mode=0;mode<4;mode++)for(int count:new int[]{1,2})for(int active=1;active<=count;active++)
        for(String style:new String[]{"HyperOS3","iOS27"})for(float weight:new float[]{0f,1f})for(boolean reverse:new boolean[]{false,true}){
            Object state=DuoTransportProbe.state(cl,mode<2,count,active);Object icon=drawable.getConstructor(Context.class,layer).newInstance(context,composite);
            drawable.getMethod("setContent",contentType).invoke(icon,state);drawable.getMethod("setBounds",int.class,int.class,int.class,int.class).invoke(icon,0,0,128,128);
            drawable.getMethod("setCellularDots",boolean.class).invoke(icon,mode==2);drawable.getMethod("setDualSignalDots",boolean.class).invoke(icon,mode==1);drawable.getMethod("setForeground",int.class).invoke(icon,Color.GREEN);
            List<Integer> sourceLevels=count==1?Arrays.asList(4):Arrays.asList(4,2);
            Picture sourcePicture=(Picture)picture.invoke(rendering,count==1?single:stacked,sourceLevels,.28f,.28f,weight);
            drawable.getMethod("setCellularSignalPicture",Picture.class).invoke(icon,sourcePicture);
            List<Object> sourceMorphs=new ArrayList<>();for(int row=0;row<count;row++)sourceMorphs.add(make.invoke(mc,count==1?single:stacked,single,row,count,.632f,weight));
            Object targetDoc=load(context,rendering,parse,"Signal-"+style+(count==1?"-Single.svg":"-Stacked.svg"));float split=style.equals("HyperOS3")?.632f:.610455f;
            List<Object> targetRows=new ArrayList<>((List)contentType.getMethod("getCellularSignalRows").invoke(state));if(reverse)Collections.reverse(targetRows);
            List<Integer> levels=new ArrayList<>();List<Object> contours=new ArrayList<>();
            for(int row=0;row<count;row++){levels.add((Integer)targetRows.get(row).getClass().getMethod("getLevel").invoke(targetRows.get(row)));Object m=make.invoke(mc,targetDoc,single,row,count,split,weight);contours.add(morph.getMethod("getSource").invoke(m));}
            Picture targetPicture=(Picture)picture.invoke(rendering,targetDoc,levels,.4f,.2f,weight);
            Bitmap targetBitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);Canvas tc=new Canvas(targetBitmap);tc.scale(64f/targetPicture.getWidth(),64f/targetPicture.getHeight());tc.drawPicture(targetPicture);
            DuoNetworkProbe.ARoot root=new DuoNetworkProbe.ARoot(context);FrameLayout holder=new FrameLayout(context);root.addView(holder);
            DuoNetworkProbe.AImage nativeView=new DuoNetworkProbe.AImage(context);nativeView.setImageDrawable(new BitmapDrawable(context.getResources(),targetBitmap));holder.addView(nativeView);
            root.layout(0,0,192,224);holder.layout(100,144,180,224);nativeView.layout(4,0,68,64);
            DuoNetworkProbe.AView source=new DuoNetworkProbe.AView(context);source.layout(8,8,136,136);
            Object target=targetType.getConstructor(ImageView.class,List.class,float.class,List.class).newInstance(nativeView,targetRows,split,contours);
            Object owners=ownershipType.getConstructor().newInstance(),motion=motionType.getConstructor().newInstance(),rival=motionType.getConstructor().newInstance();
            int[][] forward=new int[5][];
            for(int step:new int[]{0,1,2,3,4,3,2,1,0}){
                float p=step/4f;update.invoke(motion,root,source,icon,target,sourceMorphs,bars,p,owners);draw(root);
                if(!(Boolean)update.invoke(motion,root,source,icon,target,sourceMorphs,bars,p,owners))throw new AssertionError("Whole stacked target never became ready");
                if(nativeView.getAlpha()!=(step==4?1f:0f))throw new AssertionError("Shared target alpha has competing writers");
                Bitmap b=draw(root);int[] now=pixels(b);
                if(Arrays.stream(now).noneMatch(color->Color.alpha(color)>10))throw new AssertionError("Signal geometry left the root bounds");
                if(forward[step]!=null&&!Arrays.equals(now,forward[step]))throw new AssertionError("Whole stacked target reverse differs mode="+mode+" style="+style);
                if(forward[step]==null&&scenario%12==0)sheetCanvas.drawBitmap(b,step*192,(scenario/12)*224,null);
                forward[step]=now;frames++;
            }
            if((Boolean)update.invoke(rival,root,source,icon,target,sourceMorphs,bars,.5f,owners)||nativeView.getAlpha()!=0f)throw new AssertionError("Second proxy stole the shared target");
            // Tint mutates the same Drawable; every SIM recording must refresh, not only the first crop.
            nativeView.getDrawable().setTint(Color.RED);update.invoke(motion,root,source,icon,target,sourceMorphs,bars,.99f,owners);Bitmap tinted=draw(root);
            for(int row=0;row<count;row++){
                List<?> paths=(List<?>)contours.get(row);RectF bound=(RectF)paths.get(0).getClass().getMethod("getBounds").invoke(paths.get(0));
                float rowTop=row==1?split:0f,rowHeight=count==1?1f:row==0?split:1f-split;
                int x=104+Math.round(bound.centerX()*64),y=144+Math.round((rowTop+bound.centerY()*rowHeight)*64);
                int color=tinted.getPixel(x,y);if(Color.red(color)<180||Color.green(color)>90)throw new AssertionError("Stale per-SIM target tint row="+row+" rgb="+Integer.toHexString(color));
            }
            holder.setVisibility(View.GONE);
            if((Boolean)update.invoke(motion,root,source,icon,target,sourceMorphs,bars,.5f,owners)||nativeView.getAlpha()!=1f)throw new AssertionError("Hidden holder retained phantom endpoint");
            holder.setVisibility(View.VISIBLE);nativeView.getDrawable().setTint(Color.WHITE);
            update.invoke(motion,root,source,icon,target,sourceMorphs,bars,.5f,owners);draw(root);update.invoke(motion,root,source,icon,target,sourceMorphs,bars,.5f,owners);
            clear.invoke(motion);if(nativeView.getAlpha()!=1f||(Boolean)ownershipType.getMethod("contains",Object.class).invoke(owners,nativeView))throw new AssertionError("Stacked target retirement leaked ownership");
            scenario++;
        }
        try(FileOutputStream stream=new FileOutputStream(output)){sheet.compress(Bitmap.CompressFormat.PNG,100,stream);}
        System.out.println("PASS whole_stacked_target_scenarios="+scenario+" whole_stacked_target_frames="+frames+" hidden_holder_tint_lease_cases="+scenario);
    }
    static Object load(Context c,Object renderer,Method parse,String file)throws Exception{return parse.invoke(renderer,new String(c.getAssets().open("svg/"+file).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
}
