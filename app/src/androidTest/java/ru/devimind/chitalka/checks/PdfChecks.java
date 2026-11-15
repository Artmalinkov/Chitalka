package ru.devimind.chitalka.checks;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.webkit.WebView;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import ru.devimind.chitalka.*;

/** Generated text and image-only PDFs stay in the emulator; none are bundled in the app. */
public final class PdfChecks extends Instrumentation {
    private Activity activity;private Library library;private File folder;private Bundle args;private String textId,scanId;
    private final JSONArray checks=new JSONArray();
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void pass(String message){checks.put(message);Bundle result=new Bundle();result.putString("check",message);sendStatus(0,result);}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo n,String value){if(n==null)return null;if(value.equalsIgnoreCase(String.valueOf(n.getText()))||value.equals(String.valueOf(n.getContentDescription()))||value.equals(n.getViewIdResourceName()))return n;if("android.webkit.WebView".contentEquals(n.getClassName()))return null;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo f=find(n.getChild(i),value);if(f!=null)return f;}return null;}
    private View nativeView(View v,String value){if(value.equals(v.getContentDescription()==null?null:v.getContentDescription().toString()))return v;if(v instanceof WebView)return null;if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++){View f=nativeView(((android.view.ViewGroup)v).getChildAt(i),value);if(f!=null)return f;}return null;}
    private void revealReaderControls(){
        if(activity==null)return;View controls=view(R.id.reader_controls);if(controls==null||controls.isShown())return;
        try{Field pane=MainActivity.class.getDeclaredField("paneOpen");pane.setAccessible(true);if(pane.getBoolean(activity))return;}catch(Exception error){throw new RuntimeException(error);}
        View page=view(R.id.book_body);if(page==null)page=view(R.id.pdf_page);if(page==null)return;final View target=page;int[] xy=new int[2];runOnMainSync(()->target.getLocationOnScreen(xy));long down=SystemClock.uptimeMillis();for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,xy[0]+page.getWidth()/2f,xy[1]+page.getHeight()/2f,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);sendPointerSync(event);event.recycle();SystemClock.sleep(40);}SystemClock.sleep(300);
    }
    private void tap(String value){revealReaderControls();if(activity!=null)runOnMainSync(()->{View v=nativeView(activity.getWindow().getDecorView(),value);if(v!=null)v.requestRectangleOnScreen(new Rect(0,0,v.getWidth(),v.getHeight()),true);});for(int i=0;i<50;i++){AccessibilityNodeInfo n=find(getUiAutomation().getRootInActiveWindow(),value);while(n!=null&&!n.isClickable())n=n.getParent();if(n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(150);return;}SystemClock.sleep(100);}throw new AssertionError("Missing "+value);}
    private View view(int id){AtomicReference<View> v=new AtomicReference<>();runOnMainSync(()->v.set(activity.findViewById(id)));return v.get();}
    private void input(int id,String text){if(view(id)!=null){runOnMainSync(()->((EditText)activity.findViewById(id)).setText(text));return;}AccessibilityNodeInfo field=find(getUiAutomation().getRootInActiveWindow(),getTargetContext().getResources().getResourceName(id));expect(field!=null,"Input field exists");Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);expect(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value),"Set dialog text");}
    private void shot(String name)throws Exception{SystemClock.sleep(450);Bitmap image=getUiAutomation().takeScreenshot();try(OutputStream output=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,output);}image.recycle();}
    private void waitPage(String id,int page,int count){for(int i=0;i<250;i++){View label=view(R.id.pdf_counter);if(label instanceof TextView&&((TextView)label).getText().toString().equals("Стр. "+page+" / "+count)&&library.book(id).page==page)return;SystemClock.sleep(100);}throw new AssertionError("PDF page "+page+" of "+count+" not ready");}
    private Object field(View target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void pinch(View view){runOnMainSync(()->{long down=SystemClock.uptimeMillis()-500;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];for(int i=0;i<2;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i]=new MotionEvent.PointerCoords();coords[i].y=view.getHeight()/2f;coords[i].pressure=1;coords[i].size=1;}for(int step=0;step<10;step++){float span=60+Math.max(0,Math.min(step-1,6))*60;coords[0].x=view.getWidth()/2f-span;coords[1].x=view.getWidth()/2f+span;int count=step==0||step==9?1:2;int action=step==0?MotionEvent.ACTION_DOWN:step==1?MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):step==8?MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):step==9?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;MotionEvent event=MotionEvent.obtain(down,down+step*40,action,count,properties,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);view.dispatchTouchEvent(event);event.recycle();}});}
    private File fixture(boolean scan)throws Exception{
        File file=new File(folder,scan?"pdf-scan-test.pdf":"pdf-text-test.pdf");PdfDocument document=new PdfDocument();
        for(int i=1;i<=(scan?2:3);i++){boolean landscape=!scan&&i==2;int width=landscape?792:612,height=landscape?612:792;PdfDocument.Page page=document.startPage(new PdfDocument.PageInfo.Builder(width,height,i).create());Canvas canvas=page.getCanvas();canvas.drawColor(Color.WHITE);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.rgb(40,90,160));paint.setTextSize(30);
            if(scan){Bitmap raster=Bitmap.createBitmap(1000,1300,Bitmap.Config.ARGB_8888);Canvas image=new Canvas(raster);image.drawColor(Color.rgb(247,243,230));paint.setTextSize(56);image.drawText("SCANNED BOOK",70,140,paint);paint.setColor(Color.BLACK);paint.setTextSize(38);image.drawText("Page "+i,70,220,paint);for(int line=0;line<12;line++)image.drawText("Image-only PDF sample",70,330+line*55,paint);canvas.drawBitmap(raster,null,new Rect(25,25,width-25,height-25),paint);raster.recycle();}
            else{canvas.drawText("TEXT PDF - PAGE "+i,40,70,paint);paint.setColor(Color.BLACK);paint.setTextSize(18);for(int line=0;line<14;line++)canvas.drawText("Readable text, original page layout. Line "+(line+1),40,120+line*25,paint);paint.setColor(Color.rgb(40,90,160));canvas.drawRect(40,height-90,width-40,height-45,paint);}
            document.finishPage(page);
        }try(OutputStream output=new FileOutputStream(file)){document.writeTo(output);}finally{document.close();}return file;
    }
    private void pick(File file,boolean cancel)throws Exception{
        boolean[] hit={false};ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(!Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()))return null;expect("application/pdf".equals(intent.getType())&&intent.hasCategory(Intent.CATEGORY_OPENABLE),"PDF picker intent");hit[0]=true;return new ActivityResult(cancel?Activity.RESULT_CANCELED:Activity.RESULT_OK,cancel?null:new Intent().setData(Uri.fromFile(file)));}};
        addMonitor(monitor);tap("+  Добавить книгу");tap("PDF-книга");removeMonitor(monitor);expect(hit[0],"System picker used");
    }
    private String waitBook(String name,int count){for(int i=0;i<200;i++){for(Library.BookRow row:library.books())if(row.title.equals(name)&&row.pdf()&&row.total==count&&view(R.id.book_details)!=null)return row.id;SystemClock.sleep(100);}throw new AssertionError("Imported "+name);}
    private void runChecks()throws Exception{
        File text=fixture(false),scan=fixture(true);AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        int before=library.books().size();pick(text,true);expect(library.books().size()==before,"Cancel creates no book");pick(text,false);textId=waitBook("pdf-text-test",3);expect(library.book(textId).started.isEmpty(),"Import and card do not start reading");expect(library.importBook(Uri.fromFile(text)).equals(textId)&&library.books().size()==before+1,"Duplicate import uses existing book");
        for(int i=0;i<100&&!(((ImageView)view(R.id.book_cover)).getDrawable() instanceof BitmapDrawable);i++)SystemClock.sleep(100);expect(((ImageView)view(R.id.book_cover)).getDrawable() instanceof BitmapDrawable,"First page cover rendered");shot("pdf-card");pass("Импорт PDF, отмена, повторный импорт, карточка и обложка из первой страницы");
        tap("Читать");waitPage(textId,1,3);expect(!library.book(textId).started.isEmpty(),"Start after first rendered page");tap("Следующая страница PDF");waitPage(textId,2,3);Bitmap bitmap=(Bitmap)field(view(R.id.pdf_page),"bitmap");expect(bitmap.getWidth()>bitmap.getHeight(),"Landscape page preserved");expect(Math.max(bitmap.getWidth(),bitmap.getHeight())<=2048,"Bounded page bitmap");shot("pdf-text-landscape");tap("Предыдущая страница PDF");waitPage(textId,1,3);pass("Текстовый PDF: чтение, перелистывание, альбомная страница и ограниченная память");
        tap("ru.devimind.chitalka:id/pdf_counter");input(R.id.pdf_page_number,"4");tap("Перейти");expect(view(R.id.pdf_page_number)==null,"Dialog field belongs to dialog window, not activity");expect(find(getUiAutomation().getRootInActiveWindow(),"ru.devimind.chitalka:id/pdf_page_number")!=null,"Invalid page leaves dialog open");AccessibilityNodeInfo number=find(getUiAutomation().getRootInActiveWindow(),"ru.devimind.chitalka:id/pdf_page_number");Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"3");expect(number.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value),"Set jump page");tap("Перейти");waitPage(textId,3,3);expect(!find(getUiAutomation().getRootInActiveWindow(),"Следующая страница PDF").isEnabled(),"Last page next disabled");pass("Переход к странице, проверка диапазона и границы документа");
        View page=view(R.id.pdf_page);pinch(page);expect((Float)field(page,"scale")>1&&library.book(textId).page==3,"Two-finger zoom preserves page; scale="+field(page,"scale")+", page="+library.book(textId).page);tap("Страница");for(int i=0;i<3;i++)tap("Увеличить PDF");expect((Float)field(page,"scale")>1,"Zoom works");int[] xy=new int[2];runOnMainSync(()->page.getLocationOnScreen(xy));long down=SystemClock.uptimeMillis()-200;float x=xy[0]+page.getWidth()/2f,y=xy[1]+page.getHeight()/2f;for(int i=0;i<3;i++){MotionEvent event=MotionEvent.obtain(down,down+i*90,i==0?MotionEvent.ACTION_DOWN:i==2?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,x-i*40,y-i*50,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);sendPointerSync(event);event.recycle();}expect(Math.abs((Float)field(page,"panX"))+Math.abs((Float)field(page,"panY"))>0,"Pan works");shot("pdf-zoom");tap("Страница");expect((Float)field(page,"scale")==1,"Reset fits whole page");tap("По ширине");expect((Float)field(page,"scale")>=1,"Fit width");pass("Масштаб двумя пальцами и кнопками, перемещение страницы и режимы вписывания");
        tap("Конспект");tap("Цитаты");tap("+  Добавить");tap("Цитата");input(R.id.note_title,"PDF цитата");input(R.id.note_body,"Цитата, введённая вручную со страницы 3.");tap("Готово");Library.Note quote=null;for(Library.Note note:library.notes(textId))if(note.title.equals("PDF цитата"))quote=note;expect(quote!=null&&quote.source==3,"Quote page stored");expect(library.markdown(textId,library.load(textId)).contains("Источник: страница 3"),"PDF page in export");tap("Закрыть конспект");tap("Предыдущая страница PDF");waitPage(textId,2,3);tap("Конспект");tap("PDF цитата");tap("Источник: страница 3");waitPage(textId,3,3);pass("Конспект и ручная цитата со ссылкой на страницу; переход к источнику и экспорт");
        tap("О книге");tap("Читать");waitPage(textId,3,3);runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));SystemClock.sleep(700);waitPage(textId,3,3);runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));SystemClock.sleep(700);waitPage(textId,3,3);pass("Позиция сохраняется при повторном открытии и повороте экрана");
        tap("О книге");tap("В библиотеку");pick(scan,false);scanId=waitBook("pdf-scan-test",2);tap("Читать");waitPage(scanId,1,2);tap("Следующая страница PDF");waitPage(scanId,2,2);shot("pdf-scan");pass("PDF-скан без текстового слоя читается и перелистывается");
        File broken=new File(folder,"broken.pdf");try(OutputStream output=new FileOutputStream(broken)){output.write("%PDF-1.7 invalid".getBytes("UTF-8"));}int count=library.books().size();boolean rejected=false;try{library.importBook(Uri.fromFile(broken));}catch(Exception error){rejected=true;}expect(rejected&&library.books().size()==count,"Invalid PDF creates no library entry");pass("Повреждённый PDF отклоняется без пустой записи в библиотеке");
    }
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();try{
        library=new Library(getTargetContext());folder=new File(getTargetContext().getFilesDir(),"pdf-checks-0.1.8");folder.mkdirs();
        if(args!=null&&"true".equals(args.getString("cleanup"))){
            Set<String> ids=new HashSet<>();File reportFile=new File(folder,"checks.json");if(reportFile.exists()){JSONObject prior=new JSONObject(Library.read(new FileInputStream(reportFile)));for(String key:new String[]{"textBook","scanBook"})if(prior.has(key))ids.add(prior.getString(key));}
            // An encrypted-only run may have replaced the report. Recompute IDs
            // from our own generated fixtures, never from book titles alone.
            for(String name:new String[]{"pdf-text-test.pdf","pdf-scan-test.pdf"}){File fixture=new File(folder,name);if(!fixture.exists())continue;java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(fixture)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));ids.add(hex.toString());}
            for(String id:ids){Library.BookRow row=library.book(id);if(row!=null){expect(row.pdf()&&(row.title.equals("pdf-text-test")||row.title.equals("pdf-scan-test")),"Only own test PDF may be removed");library.deleteBook(id,true);}}
            result.putString("result","PASS: own PDF fixtures removed");finish(Activity.RESULT_OK,result);return;
        }
        if(args!=null&&"true".equals(args.getString("encrypted"))){int before=library.books().size();boolean rejected=false;try{library.importBook(Uri.fromFile(new File(folder,"encrypted.pdf")));}catch(Exception error){expect(error.getMessage()!=null&&error.getMessage().contains("парол"),"Clear password error: "+error);rejected=true;}expect(rejected&&before==library.books().size(),"Encrypted PDF rejected without new book");result.putString("result","PASS: encrypted PDF rejected with clear password message");finish(Activity.RESULT_OK,result);return;}
        runChecks();report.put("result","PASS");result.putString("result","PASS: "+checks.length()+" groups");
    }catch(Throwable error){try{report.put("result","FAIL").put("error",error.toString());}catch(Exception ignored){}result.putString("result","FAIL: "+error);}
    try{report.put("checks",checks).put("textBook",textId).put("scanBook",scanId);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"checks.json")),"UTF-8")){out.write(report.toString(2));}}catch(Exception ignored){}
    finish(report.optString("result").equals("PASS")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
