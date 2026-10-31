package ru.devimind.chitalka.checks;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.webkit.WebView;
import android.widget.*;
import org.json.*;
import ru.devimind.chitalka.*;
import java.io.*;
import java.lang.reflect.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public final class BookChecks extends Instrumentation {
    private Activity activity;private Library library;private File folder;private Bundle args;
    private final JSONArray checks=new JSONArray(),created=new JSONArray();
    private JSONObject legacy;private String id;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void pass(String name)throws Exception{checks.put(new JSONObject().put("name",name).put("result","PASS"));Bundle b=new Bundle();b.putString("check",name);sendStatus(0,b);}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value){if(node==null)return null;if(value.equalsIgnoreCase(String.valueOf(node.getText()))||value.equals(String.valueOf(node.getContentDescription()))||value.equals(node.getViewIdResourceName()))return node;if("android.webkit.WebView".contentEquals(node.getClassName()))return null;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),value);if(found!=null)return found;}return null;}
    private void tap(String value){for(int i=0;i<40;i++){AccessibilityNodeInfo node=find(getUiAutomation().getRootInActiveWindow(),value);while(node!=null&&!node.isClickable())node=node.getParent();if(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(180);return;}SystemClock.sleep(100);}throw new AssertionError("Control not found: "+value);}
    private View view(int id){AtomicReference<View> found=new AtomicReference<>();runOnMainSync(()->found.set(activity.findViewById(id)));return found.get();}
    private void input(int id,String value){runOnMainSync(()->((EditText)activity.findViewById(id)).setText(value));}
    private void reveal(int id){runOnMainSync(()->{View v=activity.findViewById(id);v.requestRectangleOnScreen(new Rect(0,0,v.getWidth(),v.getHeight()),true);});SystemClock.sleep(150);}
    private void shot(String name)throws Exception{SystemClock.sleep(500);Bitmap image=getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    private void flush()throws Exception{View form=view(R.id.book_details);Method save=form.getClass().getDeclaredMethod("save");save.setAccessible(true);runOnMainSync(()->{try{save.invoke(form);}catch(Exception e){throw new RuntimeException(e);}});}
    private void date(boolean beginning,String value)throws Exception{View form=view(R.id.book_details);Method set=form.getClass().getDeclaredMethod("setDate",boolean.class,String.class);set.setAccessible(true);runOnMainSync(()->{try{set.invoke(form,beginning,value);}catch(Exception e){throw new RuntimeException(e);}});}
    private void readerReady(){for(int i=0;i<300;i++){if(view(R.id.book_body)!=null&&!library.book(id).started.isEmpty())return;SystemClock.sleep(100);}throw new AssertionError("Reader and start date ready");}
    private String fixture(String title,String author)throws Exception{
        StringBuilder text=new StringBuilder("<?xml version='1.0' encoding='UTF-8'?><FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'><description><title-info><genre>prose_classic</genre><author><first-name>").append(author).append("</first-name></author><book-title>").append(title).append("</book-title><lang>ru</lang></title-info></description><body><section><title><p>Первая глава</p></title>");for(int i=0;i<25;i++)text.append("<p>Проверочная страница книги для проверки карточки и сохранения даты начала чтения. Абзац ").append(i).append(".</p>");text.append("</section></body></FictionBook>");
        File source=new File(folder,"source-"+created.length()+".fb2");try(Writer out=new OutputStreamWriter(new FileOutputStream(source),"UTF-8")){out.write(text.toString());}String result=library.importBook(Uri.fromFile(source));created.put(result);return result;
    }
    private void migration()throws Exception{
        Library.BookRow book=library.book(legacy.getString("id"));expect(library.getReadableDatabase().getVersion()==2,"Schema migrated to v2");expect(book.title.equals(legacy.getString("title"))&&book.author.equals(legacy.getString("author"))&&book.chapter==legacy.getInt("chapter")&&Math.abs(book.scroll-legacy.getDouble("scroll"))<.00001,"Legacy metadata and position retained");expect(book.country.isEmpty()&&book.genre.isEmpty()&&book.started.isEmpty()&&book.finished.isEmpty(),"New fields empty, historical dates not invented");
        Library.Note thought=library.note(legacy.getString("thought"));expect(thought!=null&&thought.parent.equals(legacy.getString("section"))&&thought.body.equals("Непустая мысль до обновления 015"),"Non-empty related notes retained");JSONObject prefs=legacy.getJSONObject("preferences");for(Iterator<String> keys=prefs.keys();keys.hasNext();){String key=keys.next();expect(String.valueOf(getTargetContext().getSharedPreferences("settings",0).getAll().get(key)).equals(String.valueOf(prefs.get(key))),"Preference retained: "+key);}pass("Обновление v1 → v2 сохранило книгу, позицию, мысль, раздел, связь и настройки");
    }
    private void sorting()throws Exception{
        String b=fixture("Бета 015","Борис"),c=fixture("Гамма 015","Виктор");Library.BookRow a=library.book(id),br=library.book(b),cr=library.book(c);
        a.title="Альфа 015";a.author="Яков";a.country="Швеция";a.genre="История";a.started="2026-01-03";a.finished="2026-02-02";
        br.country="Армения";br.genre="Фантастика";br.started="2026-01-01";br.finished="2026-02-03";
        cr.country="Россия";cr.genre="Биография";cr.started="2026-01-02";cr.finished="2026-02-01";
        for(Library.BookRow book:new Library.BookRow[]{a,br,cr})library.saveBook(book);
        String[][] orders={{"author",b,c,id},{"title",id,b,c},{"country",b,c,id},{"genre",c,id,b},{"started",b,c,id},{"finished",c,id,b}};
        Set<String> own=new HashSet<>(Arrays.asList(id,b,c));for(String[] order:orders)for(boolean desc:new boolean[]{false,true}){List<String> actual=new ArrayList<>();boolean blank=false;for(Library.BookRow row:library.books(order[0],desc)){String value="author".equals(order[0])?row.author:"title".equals(order[0])?row.title:"country".equals(order[0])?row.country:"genre".equals(order[0])?row.genre:"started".equals(order[0])?row.started:row.finished;if(value.isEmpty())blank=true;else expect(!blank,"Empty sort fields last "+order[0]);if(own.contains(row.id))actual.add(row.id);}List<String> expected=new ArrayList<>(Arrays.asList(order[1],order[2],order[3]));if(desc)Collections.reverse(expected);expect(actual.equals(expected),"Sort "+order[0]+" descending="+desc);}
        pass("Все шесть полей: 12 вариантов сортировки, русский алфавит, пустые значения в конце");
    }
    private void runChecks()throws Exception{
        migration();id=fixture("Книга для карточки 015","Иван");
        AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        tap("О книге: Книга для карточки 015");expect(view(R.id.book_details)!=null&&view(R.id.book_body)==null,"Card first, no reader");expect(((EditText)view(R.id.book_title)).getText().toString().equals("Книга для карточки 015")&&((EditText)view(R.id.book_author)).getText().toString().equals("Иван"),"FB2 values prefilled");expect(library.book(id).started.isEmpty(),"Opening card does not start reading");pass("Нажатие открывает карточку; автор и название из FB2, дата ещё пустая");shot("card-initial");
        input(R.id.book_author,"Редактор автора 015");input(R.id.book_title,"Новое название 015");input(R.id.book_country,"Россия");input(R.id.book_genre,"Роман");flush();
        Instrumentation.ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);runOnMainSync(activity::recreate);Activity restored=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);expect(restored!=null,"Activity recreated");activity=restored;for(int i=0;i<30&&view(R.id.book_details)==null;i++)SystemClock.sleep(100);expect(((EditText)view(R.id.book_title)).getText().toString().equals("Новое название 015"),"Card restored after recreation");pass("Четыре текстовых поля сохраняются и восстанавливаются после пересоздания экрана");
        Field worker=MainActivity.class.getDeclaredField("worker");worker.setAccessible(true);CountDownLatch gate=new CountDownLatch(1);((ExecutorService)worker.get(activity)).execute(()->{try{gate.await(8,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});
        tap("Читать");View spinner=view(R.id.loading_indicator),label=view(R.id.loading_label);expect(spinner!=null&&label!=null,"Real loading UI shown");Rect a=new Rect(),b=new Rect(),screen=new Rect();runOnMainSync(()->{spinner.getGlobalVisibleRect(a);label.getGlobalVisibleRect(b);activity.getWindow().getDecorView().getGlobalVisibleRect(screen);});expect(b.top>a.bottom&&Math.abs(a.centerX()-b.centerX())<3&&Math.abs(a.centerX()-screen.centerX())<3,"Label centered beneath spinner");expect((((TextView)label).getGravity()&Gravity.HORIZONTAL_GRAVITY_MASK)==Gravity.CENTER_HORIZONTAL,"Text centered inside label");shot("loading-centered");runOnMainSync(activity::onBackPressed);gate.countDown();SystemClock.sleep(900);expect(view(R.id.book_details)!=null&&library.book(id).started.isEmpty(),"Canceled load stays card without start date");pass("Надпись по центру под индикатором; отмена загрузки не открывает книгу повторно");
        tap("Читать");readerReady();expect(library.book(id).started.equals(LocalDate.now().toString()),"First actual reading sets today");Fb2.Book loaded=library.load(id);expect(loaded.title.equals("Новое название 015")&&loaded.author.equals("Редактор автора 015"),"Reader and export use corrected metadata");expect(library.markdown(id,loaded).startsWith("# Новое название 015\n\nРедактор автора 015"),"Export corrected title and author");pass("Дата начала ставится при открытии текста; чтение и экспорт используют исправленные сведения");tap("О книге");
        String earlier=LocalDate.now().minusDays(4).toString();date(true,earlier);tap("Читать");readerReady();tap("О книге");expect(library.book(id).started.equals(earlier),"Manual start date not overwritten");reveal(R.id.finish_reading);tap("Завершить чтение");expect(library.book(id).finished.equals(LocalDate.now().toString()),"Finish button sets today");shot("card-dates");date(false,LocalDate.parse(earlier).minusDays(1).toString());expect(library.book(id).finished.equals(LocalDate.now().toString()),"Reversed date rejected");pass("Ручная дата начала сохраняется; завершение ставит дату, обратный порядок дат отклоняется");
        reveal(R.id.book_finished);tap("ru.devimind.chitalka:id/book_finished");tap("Очистить");expect(library.book(id).finished.isEmpty(),"Date picker clears end date");pass("Даты редактируются календарём; очистка даты завершения сохраняется");tap("В библиотеку");
        sorting();monitor=addMonitor(MainActivity.class.getName(),null,false);runOnMainSync(activity::recreate);restored=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);expect(restored!=null,"Library recreated");activity=restored;tap("ru.devimind.chitalka:id/library_sort");tap("Страна автора");expect(getTargetContext().getSharedPreferences("settings",0).getString("library_sort","").equals("country"),"UI sort saved");tap("ru.devimind.chitalka:id/sort_direction");expect(getTargetContext().getSharedPreferences("settings",0).getBoolean("library_desc",false),"Sort direction saved");shot("library-sort");pass("Выбор сортировки и направление доступны в библиотеке и сохраняются");
        expect(library.book(legacy.getString("id")).started.isEmpty(),"Unopened old book still has unknown start date");pass("Для старой неоткрывавшейся книги историческая дата не выдумывается");
    }
    private void cleanup(JSONObject report)throws Exception{JSONArray ids=report.getJSONArray("createdBooks");for(int i=0;i<ids.length();i++){String key=ids.getString(i);expect(!key.equals(legacy.getString("id")),"Keep original book");library.getWritableDatabase().delete("books","id=?",new String[]{key});for(String extension:new String[]{".fb2",".json"})library.file(key,extension).delete();}for(String key:new String[]{"thought","section"}){Library.Note note=library.note(legacy.getString(key));if(note!=null)library.delete(note);}SharedPreferences.Editor editor=getTargetContext().getSharedPreferences("settings",0).edit();JSONObject prefs=legacy.getJSONObject("preferences");for(String key:new String[]{"library_sort","library_desc"})if(prefs.has(key)){Object value=prefs.get(key);if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else editor.putString(key,String.valueOf(value));}else editor.remove(key);editor.commit();}
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();boolean cleaning=args!=null&&"true".equals(args.getString("cleanup"));try{
        folder=new File(getTargetContext().getFilesDir(),"book-checks-0.1.5");folder.mkdirs();legacy=new JSONObject(Library.read(new FileInputStream(new File(folder,"legacy.json"))));library=new Library(getTargetContext());
        if(cleaning){cleanup(new JSONObject(Library.read(new FileInputStream(new File(folder,"checks.json")))));result.putString("result","PASS: own fixtures removed and sort preferences restored");finish(Activity.RESULT_OK,result);return;}
        runChecks();report.put("result","PASS");result.putString("result","PASS: "+checks.length()+" checks");
    }catch(Throwable error){try{report.put("result","FAIL").put("error",error.toString());}catch(Exception ignored){}result.putString("result","FAIL: "+error);}
    finally{if(!cleaning)try{report.put("checks",checks).put("createdBooks",created);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"checks.json")),"UTF-8")){out.write(report.toString(2));}}catch(Exception ignored){}}
        finish(report.optString("result").equals("PASS")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
