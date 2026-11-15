package ru.devimind.chitalka;

import android.app.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

/** Renderer ownership and close are serialized; stale renders never replace newer pages. */
final class PdfReaderView extends LinearLayout {
    interface Listener {void page(int page,int count);void failed(Exception error);}
    private final Activity activity;private final Design design;private final Listener listener;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private PdfRenderer renderer;private ParcelFileDescriptor descriptor;
    private volatile boolean closed;private volatile int generation;private int count,requested,page;
    private final TextView counter,previous,next;private final ProgressBar loading;private final PageImage image;
    PdfReaderView(Activity activity,Design design,File file,int initial,Listener listener){
        super(activity);this.activity=activity;this.design=design;this.listener=listener;setOrientation(VERTICAL);setId(R.id.pdf_reader);
        LinearLayout controls=design.row();controls.setPadding(design.dp(8),design.dp(6),design.dp(8),design.dp(6));
        previous=design.button("‹",false,()->showPage(requested-1));previous.setContentDescription("Предыдущая страница PDF");controls.addView(previous,new LayoutParams(design.dp(48),design.dp(48)));
        counter=design.button("Открываем PDF…",false,this::choosePage);counter.setTextSize(12);counter.setId(R.id.pdf_counter);LayoutParams center=new LayoutParams(0,design.dp(48),1);center.setMargins(design.dp(6),0,design.dp(6),0);controls.addView(counter,center);
        next=design.button("›",false,()->showPage(requested+1));next.setContentDescription("Следующая страница PDF");controls.addView(next,new LayoutParams(design.dp(48),design.dp(48)));addView(controls);
        FrameLayout surface=new FrameLayout(activity);surface.setBackgroundColor(design.bg);image=new PageImage();image.setId(R.id.pdf_page);surface.addView(image,new FrameLayout.LayoutParams(-1,-1));loading=new ProgressBar(activity);FrameLayout.LayoutParams progress=new FrameLayout.LayoutParams(design.dp(48),design.dp(48),Gravity.CENTER);surface.addView(loading,progress);addView(surface,new LayoutParams(-1,0,1));
        LinearLayout zoom=design.row();zoom.setPadding(design.dp(8),design.dp(6),design.dp(8),design.dp(8));TextView smaller=design.button("−",false,()->image.zoom(.8f)),fit=design.button("По ширине",false,image::width),reset=design.button("Страница",false,image::reset),larger=design.button("+",false,()->image.zoom(1.25f));smaller.setContentDescription("Уменьшить PDF");larger.setContentDescription("Увеличить PDF");for(TextView control:new TextView[]{smaller,fit,reset,larger})zoom.addView(control,new LayoutParams(0,design.dp(48),1));addView(zoom);
        updateControls();worker.execute(()->{try{descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(descriptor);int pages=renderer.getPageCount();if(pages<1)throw new IOException("В PDF нет страниц");main.post(()->{if(closed)return;count=pages;showPage(Math.max(1,Math.min(initial,count)));});}catch(Exception error){error(error);}});
    }
    void showPage(int target){if(closed||count==0)return;target=Math.max(1,Math.min(target,count));final int number=target,token=++generation;requested=number;loading.setVisibility(VISIBLE);updateControls();worker.execute(()->{
        if(closed||token!=generation)return;try{Bitmap bitmap=PdfBook.render(renderer,number-1,2048);main.post(()->{if(closed||token!=generation){bitmap.recycle();return;}image.set(bitmap);page=number;loading.setVisibility(GONE);counter.setText("Стр. "+page+" / "+count);counter.setContentDescription("Перейти к странице PDF. "+page+" из "+count);listener.page(page,count);updateControls();});}catch(Exception|OutOfMemoryError failure){main.post(()->{if(closed||token!=generation)return;requested=page>0?page:1;loading.setVisibility(GONE);counter.setText(page>0?"Стр. "+page+" / "+count:"PDF не открыт");updateControls();listener.failed(failure instanceof Exception?(Exception)failure:new IOException("Не хватает памяти для страницы PDF."));});}
        });}
    private void error(Exception error){main.post(()->{if(closed)return;loading.setVisibility(GONE);counter.setText("PDF не открыт");listener.failed(error);});}
    private void updateControls(){previous.setEnabled(count>0&&requested>1);next.setEnabled(count>0&&requested<count);counter.setEnabled(count>0);}
    private void choosePage(){EditText number=design.edit("Номер страницы",1);number.setId(R.id.pdf_page_number);number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);number.setText(String.valueOf(page>0?page:requested));number.setSelectAllOnFocus(true);LinearLayout box=design.column();box.setPadding(design.dp(20),design.dp(12),design.dp(20),design.dp(8));box.addView(number);AlertDialog dialog=new AlertDialog.Builder(new ContextThemeWrapper(activity,design.light?android.R.style.Theme_Material_Light_Dialog_Alert:android.R.style.Theme_Material_Dialog_Alert)).setTitle("Страница PDF · 1–"+count).setView(box).setPositiveButton("Перейти",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{try{int value=Integer.parseInt(number.getText().toString().trim());if(value<1||value>count)throw new NumberFormatException();dialog.dismiss();showPage(value);}catch(NumberFormatException error){number.setError("Укажите страницу от 1 до "+count);}}));dialog.show();}
    void close(){if(closed)return;closed=true;generation++;worker.execute(()->{if(renderer!=null){renderer.close();renderer=null;}if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}});worker.shutdown();}
    @Override protected void onDetachedFromWindow(){close();super.onDetachedFromWindow();}
    private final class PageImage extends View {
        private Bitmap bitmap;private float scale=1,panX,panY,lastX,lastY,startX,startY;private boolean pinch;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector detector=new ScaleGestureDetector(activity,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){pinch=true;float old=scale;scale=Math.max(1,Math.min(5,scale*d.getScaleFactor()));float ratio=scale/old;panX=(panX+getWidth()/2f-d.getFocusX())*ratio-getWidth()/2f+d.getFocusX();panY=(panY+getHeight()/2f-d.getFocusY())*ratio-getHeight()/2f+d.getFocusY();clamp();invalidate();return true;}});
        PageImage(){super(activity);setContentDescription("Страница PDF. Масштаб двумя пальцами, перемещение одним.");}
        void set(Bitmap image){bitmap=image;reset();}
        float base(){return bitmap==null?1:Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());}
        void clamp(){if(bitmap==null)return;float x=Math.max(0,(bitmap.getWidth()*base()*scale-getWidth())/2),y=Math.max(0,(bitmap.getHeight()*base()*scale-getHeight())/2);panX=Math.max(-x,Math.min(x,panX));panY=Math.max(-y,Math.min(y,panY));}
        void zoom(float factor){scale=Math.max(1,Math.min(5,scale*factor));clamp();invalidate();}
        void reset(){scale=1;panX=panY=0;invalidate();}
        void width(){if(bitmap==null||base()<=0)return;scale=Math.max(1,Math.min(5,getWidth()/(bitmap.getWidth()*base())));panX=panY=0;clamp();invalidate();}
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);clamp();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(bitmap==null)return;float factor=base()*scale,w=bitmap.getWidth()*factor,h=bitmap.getHeight()*factor;canvas.drawBitmap(bitmap,null,new RectF((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY),paint);}
        @Override public boolean onTouchEvent(android.view.MotionEvent event){
            detector.onTouchEvent(event);int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){lastX=startX=event.getX();lastY=startY=event.getY();pinch=false;return true;}
            if(action==MotionEvent.ACTION_POINTER_DOWN)pinch=true;
            if(action==MotionEvent.ACTION_POINTER_UP){int remaining=event.getActionIndex()==0?1:0;lastX=event.getX(remaining);lastY=event.getY(remaining);}
            if(action==MotionEvent.ACTION_MOVE){if(!detector.isInProgress()){panX+=event.getX()-lastX;panY+=event.getY()-lastY;clamp();invalidate();}lastX=event.getX();lastY=event.getY();}
            if(action==MotionEvent.ACTION_UP&&!pinch&&scale<=1.01f){float dx=event.getX()-startX,dy=event.getY()-startY;if(Math.abs(dx)>design.dp(65)&&Math.abs(dx)>Math.abs(dy)*1.5f)showPage(requested+(dx<0?1:-1));}return true;
        }
    }
}
