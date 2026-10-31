package ru.devimind.chitalka;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

/** Short-lived viewport snapshots: book HTML is never duplicated for animation. */
final class PageAnimationView extends View {
    private final Bitmap previous;
    private Bitmap next;
    private final boolean curl,forward,light;
    private final int background;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private float progress;

    PageAnimationView(Context context,Bitmap previous,String effect,boolean forward,int background,boolean light){
        super(context);this.previous=previous;this.next=previous;this.curl="curl".equals(effect);this.forward=forward;this.background=background;this.light=light;setClickable(true);setId(R.id.page_animation);
    }
    void setNext(Bitmap bitmap){next=bitmap;invalidate();}
    void setProgress(float value){progress=Math.max(0,Math.min(1,value));invalidate();}
    private void bitmap(Canvas canvas,Bitmap bitmap,int alpha){paint.setShader(null);paint.setAlpha(alpha);canvas.drawBitmap(bitmap,null,new RectF(0,0,getWidth(),getHeight()),paint);paint.setAlpha(255);}
    @Override protected void onDraw(Canvas canvas){
        canvas.drawColor(background);
        if(progress<=.001f){bitmap(canvas,previous,255);return;}
        bitmap(canvas,next,255);
        if(progress>=.999f)return;
        if(!curl){bitmap(canvas,previous,Math.round(255*(1-progress)));return;}
        float w=getWidth(),h=getHeight(),cx=forward?w:0;
        float px=forward?w-2*w*progress:2*w*progress;
        float py=(float)Math.sin(Math.PI*progress)*h*.24f;
        float nx=cx-px,ny=-py,mx=(cx+px)/2,my=py/2,limit=nx*mx+ny*my;
        ArrayList<PointF> rectangle=new ArrayList<>(Arrays.asList(new PointF(0,0),new PointF(w,0),new PointF(w,h),new PointF(0,h)));
        Path front=path(clip(rectangle,nx,ny,limit,false));
        canvas.save();canvas.clipPath(front);bitmap(canvas,previous,255);canvas.restore();
        float length=(float)Math.hypot(nx,ny);if(length<.01f)return;
        float ux=nx/length,uy=ny/length,distance=limit/length;
        Matrix reflection=new Matrix();reflection.setValues(new float[]{1-2*ux*ux,-2*ux*uy,2*distance*ux,-2*ux*uy,1-2*uy*uy,2*distance*uy,0,0,1});
        ArrayList<PointF> folded=clip(rectangle,nx,ny,limit,true);Path back=path(folded);back.transform(reflection);
        float shadow=Math.min(w*.09f,42*getResources().getDisplayMetrics().density);
        canvas.save();canvas.clipPath(front);
        paint.setShader(new LinearGradient(mx-ux*shadow,my-uy*shadow,mx,my,new int[]{Color.TRANSPARENT,0x65000000},null,Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,paint);paint.setShader(null);canvas.restore();
        canvas.save();canvas.clipPath(back);canvas.drawColor(light?Color.rgb(227,231,237):Color.rgb(53,59,67));
        canvas.save();canvas.concat(reflection);bitmap(canvas,previous,42);canvas.restore();
        paint.setShader(new LinearGradient(mx,my,px,py,new int[]{light?0x50000000:0x70000000,0x08FFFFFF,light?0x55FFFFFF:0x22FFFFFF},null,Shader.TileMode.CLAMP));canvas.drawPath(back,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);paint.setColor(light?0x70FFFFFF:0x507F8998);canvas.drawPath(back,paint);paint.setStyle(Paint.Style.FILL);canvas.restore();
    }
    private static Path path(List<PointF> points){Path p=new Path();if(!points.isEmpty()){p.moveTo(points.get(0).x,points.get(0).y);for(int i=1;i<points.size();i++)p.lineTo(points.get(i).x,points.get(i).y);p.close();}return p;}
    private static ArrayList<PointF> clip(List<PointF> points,float nx,float ny,float limit,boolean positive){
        ArrayList<PointF> result=new ArrayList<>();if(points.isEmpty())return result;
        PointF a=points.get(points.size()-1);float da=nx*a.x+ny*a.y-limit;
        for(PointF b:points){float db=nx*b.x+ny*b.y-limit;boolean ia=positive?da>=0:da<=0,ib=positive?db>=0:db<=0;
            if(ia!=ib){float t=da/(da-db);result.add(new PointF(a.x+t*(b.x-a.x),a.y+t*(b.y-a.y)));}
            if(ib)result.add(new PointF(b.x,b.y));a=b;da=db;
        }return result;
    }
}
