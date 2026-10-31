package ru.devimind.chitalka.checks;

import android.app.*;
import android.animation.ValueAnimator;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.webkit.WebView;
import org.json.*;
import ru.devimind.chitalka.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** End-to-end checks on the emulator: native controls, actual gestures and data. */
public final class ReaderChecks extends Instrumentation {
    private Activity activity;
    private Library library;
    private Library.BookRow book;
    private Bundle arguments;
    private final JSONArray checks=new JSONArray();
    private final Set<String> original=new HashSet<>();
    private File folder;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;start();}
    private void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void pass(String name)throws Exception{checks.put(new JSONObject().put("result","PASS").put("name",name));Bundle status=new Bundle();status.putString("check",name);sendStatus(0,status);}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String label,boolean prefix){
        if(node==null)return null;String text=String.valueOf(node.getText()),desc=String.valueOf(node.getContentDescription()),id=String.valueOf(node.getViewIdResourceName());
        if(prefix?text.startsWith(label):label.equalsIgnoreCase(text)||label.equals(desc)||label.equals(id))return node;
        if("android.webkit.WebView".contentEquals(node.getClassName()))return null;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),label,prefix);if(found!=null)return found;}return null;
    }
    private AccessibilityNodeInfo node(String label){return find(getUiAutomation().getRootInActiveWindow(),label,false);}
    private void tap(String label){
        for(int i=0;i<25;i++){AccessibilityNodeInfo found=node(label);if(found!=null){while(found!=null&&!found.isClickable())found=found.getParent();if(found!=null&&found.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(200);return;}}SystemClock.sleep(100);}
        throw new AssertionError("Control not found: "+label);
    }
    private void text(String id,String value){AccessibilityNodeInfo found=node("ru.devimind.chitalka:id/"+id);expect(found!=null,"Edit field "+id);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);expect(found.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"Set text "+id);SystemClock.sleep(100);}
    private View view(int id){AtomicReference<View> ref=new AtomicReference<>();runOnMainSync(()->ref.set(activity.findViewById(id)));return ref.get();}
    private String js(String expression)throws Exception{
        WebView web=(WebView)view(R.id.book_body);expect(web!=null,"Reader WebView exists");CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
        runOnMainSync(()->web.evaluateJavascript(expression,value->{result.set(value);done.countDown();}));expect(done.await(8,TimeUnit.SECONDS),"JavaScript callback");return result.get();
    }
    private boolean readerRestoring()throws Exception{Field field=MainActivity.class.getDeclaredField("restoring");field.setAccessible(true);return field.getBoolean(activity);}
    private JSONObject snapshot()throws Exception{for(int i=0;i<900;i++){if(view(R.id.book_body)==null||readerRestoring()){SystemClock.sleep(100);continue;}String value=js("window.Reader?Reader.snapshot():null");if(value!=null&&!value.equals("null")){JSONObject state=new JSONObject(value);if(!state.getBoolean("busy"))return state;}SystemClock.sleep(100);}throw new AssertionError("Reader not ready");}
    private void openBook()throws Exception{tap("О книге: "+book.title);tap("Читать");snapshot();}
    private void home(){tap("О книге");tap("В библиотеку");}
    private void setting(String name){tap("Настройки");tap(name);}
    private void effect(String name){setting("Анимация перелистывания");if(name.equals("none"))tap("Без анимации");else{tap("С анимацией");tap(name.equals("fade")?"Плавная смена страниц":"Перелистывание за угол");}}
    private void screenshot(String name)throws Exception{Bitmap image=getUiAutomation().takeScreenshot();expect(image!=null,"Screenshot "+name);try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    private void swipe(boolean corner){
        View web=view(R.id.book_body);int[] xy=new int[2];runOnMainSync(()->web.getLocationOnScreen(xy));float x1=xy[0]+web.getWidth()*.88f,x2=xy[0]+web.getWidth()*.18f,y=xy[1]+web.getHeight()*(corner?.13f:.5f),dy=corner?web.getHeight()*.14f:0;
        long duration=corner?700:180,down=SystemClock.uptimeMillis()-duration;
        pointer(down,down,MotionEvent.ACTION_DOWN,x1,y);
        for(int i=1;i<=10;i++)pointer(down,down+duration*i/10,MotionEvent.ACTION_MOVE,x1+(x2-x1)*i/10,y+dy*i/10);
        pointer(down,down+duration,MotionEvent.ACTION_UP,x2,y+dy);
    }
    private void pointer(long down,long time,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,time,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);sendPointerSync(event);event.recycle();}
    private void animation(String name)throws Exception{
        effect(name);openBook();js("Reader.jump(0)");SystemClock.sleep(100);int before=snapshot().getInt("page");swipe(name.equals("curl"));
        View overlay=null;for(int i=0;i<30;i++){overlay=view(R.id.page_animation);if(overlay!=null)break;SystemClock.sleep(10);}expect(overlay!=null,"Visible "+name+" animation");
        Field previous=overlay.getClass().getDeclaredField("previous"),next=overlay.getClass().getDeclaredField("next");previous.setAccessible(true);next.setAccessible(true);
        boolean different=false;for(int i=0;i<20;i++){Bitmap old=(Bitmap)previous.get(overlay),future=(Bitmap)next.get(overlay);if(old!=future&&!old.isRecycled()&&!future.isRecycled()&&!old.sameAs(future)){different=true;break;}SystemClock.sleep(10);}
        expect(different,"Both previous and future rendered pages for "+name);
        Field animatorField=MainActivity.class.getDeclaredField("pageAnimator");animatorField.setAccessible(true);ValueAnimator animator=(ValueAnimator)animatorField.get(activity);expect(animator!=null,"Animator started "+name);
        runOnMainSync(()->{animator.pause();animator.setCurrentFraction(.35f);});SystemClock.sleep(1500);screenshot(name+"-animation");
        final View captured=overlay;runOnMainSync(()->{try{Bitmap image=Bitmap.createBitmap(captured.getWidth(),captured.getHeight(),Bitmap.Config.ARGB_8888);captured.draw(new Canvas(image));try(OutputStream out=new FileOutputStream(new File(folder,name+"-canvas.png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();for(String label:new String[]{"previous","next"}){Field f=captured.getClass().getDeclaredField(label);f.setAccessible(true);try(OutputStream out=new FileOutputStream(new File(folder,name+"-"+label+".png"))){((Bitmap)f.get(captured)).compress(Bitmap.CompressFormat.PNG,100,out);}}}catch(Exception e){throw new RuntimeException(e);}});
        runOnMainSync(animator::resume);SystemClock.sleep(650);
        expect(snapshot().getInt("page")==before+1,"Exactly one page after "+name);for(int i=0;i<500&&view(R.id.page_animation)!=null;i++)SystemClock.sleep(20);expect(view(R.id.page_animation)==null,"Animation overlay removed "+name);pass(name.equals("fade")?"Плавная смена: две разные страницы и завершение на следующей":"Загиб от верхнего правого угла: диагональный медленный жест, две страницы и завершение");home();
    }
    private void runChecks()throws Exception{
        AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));SystemClock.sleep(250);
        if(arguments!=null&&"true".equals(arguments.getString("animations"))){animation("fade");animation("curl");return;}
        expect(node("Настройки")!=null,"Home gear");expect(node("Сменить тему")==null,"No separate theme button");pass("Шестерёнка на главном экране, отдельная кнопка смены темы убрана");screenshot("home-gear");
        setting("Перелистывание страниц");tap("Свайпы влево и вправо");effect("none");openBook();
        expect(node("Настройки чтения")==null,"No ellipsis on reader");expect(node("Конспект")!=null,"Notes retained on reader");pass("На экране чтения нет трёх точек, кнопка конспекта сохранена");
        js("Reader.jump(0)");SystemClock.sleep(100);int before=snapshot().getInt("page");swipe(false);SystemClock.sleep(100);expect(snapshot().getInt("page")==before+1,"Instant page turn");expect(view(R.id.page_animation)==null,"No overlay without animation");pass("Без анимации: мгновенное перелистывание как в 0.1.3");home();
        animation("fade");animation("curl");
        setting("Тема приложения");tap("Светлая");expect(getTargetContext().getSharedPreferences("settings",0).getBoolean("light",false),"Light saved");setting("Тема приложения");tap("Тёмная");expect(!getTargetContext().getSharedPreferences("settings",0).getBoolean("light",true),"Dark saved");pass("Обе темы переключаются из настроек главного экрана");
        openBook();tap("Конспект");tap("Мои мысли");tap("+  Моя мысль");text("note_title","Проверка мысли 014");text("note_body","Текст проверки сохраняется после обновления 014");
        tap("Раздел: общие записи");tap("+ Добавить пользовательский раздел");tap("Создать");expect(node("ru.devimind.chitalka:id/section_name")!=null,"Empty section name keeps dialog open");text("section_name","Мысли о работе 014");tap("Создать");
        expect(node("Раздел: Мысли о работе 014")!=null,"New section assigned to thought");pass("Раздел создаётся из редактора, пустое название отклоняется, мысль сразу назначена");
        AccessibilityNodeInfo trash=node("Удалить запись"),source=find(getUiAutomation().getRootInActiveWindow(),"Источник:",true),body=node("ru.devimind.chitalka:id/note_body");expect(trash!=null&&source!=null&&body!=null,"Editor source, trash and body");Rect a=new Rect(),b=new Rect(),c=new Rect();trash.getBoundsInScreen(a);source.getBoundsInScreen(b);body.getBoundsInScreen(c);expect(a.left>=b.right-2&&Math.abs(a.centerY()-b.centerY())<70&&c.top>=a.bottom,"Trash aligned with source above body");expect(node("Действия с записью")==null,"Bottom ellipsis removed");
        View icon=viewWithDescription(activity.getWindow().getDecorView(),"Удалить запись");AtomicReference<Bitmap> iconImage=new AtomicReference<>();runOnMainSync(()->{Bitmap image=Bitmap.createBitmap(icon.getWidth(),icon.getHeight(),Bitmap.Config.ARGB_8888);icon.draw(new Canvas(image));iconImage.set(image);});int red=0;Bitmap image=iconImage.get();for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int color=image.getPixel(x,y);if(Color.alpha(color)>128&&Color.red(color)>150&&Color.red(color)>Color.green(color)*1.5&&Color.red(color)>Color.blue(color)*1.5)red++;}image.recycle();expect(red>20,"Red trash pixels");pass("Красная корзина справа напротив источника, над текстом; нижние три точки убраны");screenshot("thought-section-trash");
        tap("Раздел: Мысли о работе 014");tap("+ Добавить пользовательский раздел");text("section_name","Мысли о жизни 014");tap("Создать");tap("Раздел: Мысли о жизни 014");tap("Мысли о работе 014");tap("Готово");
        Library.Note thought=null,section=null;for(Library.Note n:library.notes(book.id)){if(n.title.equals("Проверка мысли 014"))thought=n;if(n.title.equals("Мысли о работе 014"))section=n;}
        expect(thought!=null&&section!=null&&section.id.equals(thought.parent)&&thought.body.equals("Текст проверки сохраняется после обновления 014"),"Persisted thought parent and body");expect(library.markdown(book.id,library.load(book.id)).contains("Мысли о работе 014"),"Section appears in export");pass("Можно выбрать существующий раздел; связь и текст сохранены и включены в Markdown");
        tap("Закрыть конспект");home();setting("Цитаты");tap(book.title+"\n"+book.author);snapshot();expect(node("Цитата из выделенного текста")!=null,"Quote shortcut from home gear");tap("Закрыть конспект");home();pass("Функция цитат доступна через шестерёнку и вкладку конспекта");
        setting("Экспорт конспекта · Markdown");expect(node(book.title+"\n"+book.author)!=null,"Export chooses book on home");tap("Отмена");pass("Экспорт из главного экрана предлагает выбор книги");
        expect(getTargetContext().getSharedPreferences("settings",0).getString("page_animation","").equals("curl"),"Animation choice persisted");pass("Выбор анимации сохранён в настройках");
    }
    private View viewWithDescription(View view,String description){if(description.equals(view.getContentDescription()==null?null:view.getContentDescription().toString()))return view;if(view instanceof WebView)return null;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=viewWithDescription(group.getChildAt(i),description);if(found!=null)return found;}}return null;}
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();try{
        library=new Library(getTargetContext());folder=new File(getTargetContext().getFilesDir(),"reader-checks-0.1.4");folder.mkdirs();
        if(arguments!=null&&"true".equals(arguments.getString("cleanup"))){
            JSONObject prior=new JSONObject(Library.read(new FileInputStream(new File(folder,"checks.json"))));JSONArray ids=prior.getJSONArray("fixtureIds");for(int i=0;i<ids.length();i++){Library.Note n=library.note(ids.getString(i));if(n!=null){expect(n.title.endsWith("014"),"Only own fixture may be removed");library.delete(n);}}result.putString("result","PASS: own fixtures removed");finish(Activity.RESULT_OK,result);return;
        }
        expect(!library.books().isEmpty(),"Imported book exists");book=library.books().get(0);for(Library.Note n:library.notes(book.id))original.add(n.id);
        runChecks();report.put("result","PASS");result.putString("result","PASS: "+checks.length()+" checks");
    }catch(Throwable error){try{report.put("result","FAIL").put("error",error.toString());}catch(Exception ignored){}result.putString("result","FAIL: "+error);}
    finally{try{if(arguments!=null&&"true".equals(arguments.getString("cleanup")))return;JSONArray ids=new JSONArray();if(book!=null)for(Library.Note n:library.notes(book.id))if(!original.contains(n.id))ids.put(n.id);report.put("checks",checks).put("fixtureIds",ids);if(folder!=null)try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"checks.json")),"UTF-8")){out.write(report.toString(2));}}catch(Exception ignored){}}
        finish(report.optString("result").equals("PASS")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
