package ru.devimind.chitalka.checks;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.webkit.WebView;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import ru.devimind.chitalka.*;
public final class PaperChecks extends Instrumentation {
 Activity activity;Library library;String id;File folder;JSONArray checks=new JSONArray();
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 void expect(boolean value,String text){if(!value)throw new AssertionError(text);}
 void pass(String text)throws Exception{checks.put(text);Bundle b=new Bundle();b.putString("check",text);sendStatus(0,b);}
 AccessibilityNodeInfo find(AccessibilityNodeInfo n,String s){if(n==null)return null;if(s.equalsIgnoreCase(String.valueOf(n.getText()))||s.equals(String.valueOf(n.getContentDescription()))||s.equals(n.getViewIdResourceName()))return n;if("android.webkit.WebView".contentEquals(n.getClassName()))return null;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo f=find(n.getChild(i),s);if(f!=null)return f;}return null;}
 void tap(String s){for(int i=0;i<40;i++){AccessibilityNodeInfo n=find(getUiAutomation().getRootInActiveWindow(),s);while(n!=null&&!n.isClickable())n=n.getParent();if(n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(130);return;}SystemClock.sleep(100);}throw new AssertionError("Missing "+s);}
 View view(int id){AtomicReference<View> r=new AtomicReference<>();runOnMainSync(()->r.set(activity.findViewById(id)));return r.get();}
 void input(int id,String s){runOnMainSync(()->((EditText)activity.findViewById(id)).setText(s));}
 void shot(String name)throws Exception{SystemClock.sleep(400);Bitmap b=getUiAutomation().takeScreenshot();try(OutputStream o=new FileOutputStream(new File(folder,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,o);}b.recycle();}
 Library.Note structure(String type,String title)throws Exception{tap("+  Добавить");input(R.id.structure_type,type);input(R.id.note_title,title);tap("Готово");for(Library.Note n:library.notes(id))if(n.title.equals(title))return n;throw new AssertionError("Saved structure");}
 void runChecks()throws Exception{
  library=new Library(getTargetContext());expect(library.getReadableDatabase().getVersion()==3,"Schema migrated");
  android.database.sqlite.SQLiteDatabase fresh=android.database.sqlite.SQLiteDatabase.create(null);library.onCreate(fresh);fresh.rawQuery("SELECT kind,page,total FROM books",null).close();fresh.rawQuery("SELECT structure_type FROM notes",null).close();fresh.close();pass("Миграция и создание новой базы версии 3");
  Set<String> original=new HashSet<>();for(Library.BookRow b:library.books())original.add(b.id);
  AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
  activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
  tap("+  Добавить книгу");tap("Бумажная книга");for(Library.BookRow b:library.books())if(!original.contains(b.id))id=b.id;expect(id!=null&&library.book(id).paper(),"Manual paper record");expect(library.book(id).started.isEmpty(),"Card does not start date");input(R.id.book_title,"Бумажная книга — проверка");input(R.id.book_author,"Автор примера");input(R.id.book_country,"Россия");input(R.id.book_genre,"Роман");tap("Начать чтение");for(int i=0;i<100&&view(R.id.paper_page)==null;i++)SystemClock.sleep(100);expect(view(R.id.book_body)==null&&!library.book(id).started.isEmpty(),"Paper starts without WebView");pass("Ручная карточка и начало чтения без файла FB2");
  input(R.id.paper_page,"25");input(R.id.paper_total,"240");tap("Сохранить прогресс");expect(library.book(id).page==25&&library.book(id).total==240,"Saved page");input(R.id.paper_page,"241");tap("Сохранить прогресс");expect(library.book(id).page==25,"Invalid page rejected");input(R.id.paper_page,"25");shot("paper-progress");pass("Прогресс 25 из 240, неверная страница отклонена");
  tap("Конспект");tap("Структура");Library.Note parent=structure("Раздел","I. Начало"),child=structure("Глава","1. Знакомство");tap("Глава 1. Знакомство");tap("Раздел: общие записи");tap("Раздел I. Начало");tap("Готово");expect(library.note(child.id).parent.equals(parent.id),"Nested chapter saved");
  tap("Раздел I. Начало");tap("Раздел: общие записи");expect(find(getUiAutomation().getRootInActiveWindow(),"Глава 1. Знакомство")==null,"Descendant excluded from parent choices");tap("Общие записи");tap("Готово");tap("Структура");shot("paper-structure");pass("Свободный тип, название с нумерацией, вложенная глава и защита от цикла");
  tap("Мои мысли");tap("+  Моя мысль");input(R.id.note_title,"Вывод читателя");input(R.id.note_body,"Моя мысль о прочитанном.");tap("Раздел: общие записи");tap("Глава 1. Знакомство");tap("Готово");
  tap("Герои");tap("+  Добавить");tap("Герой");input(R.id.note_title,"Главный герой");input(R.id.note_body,"Описание персонажа");tap("Готово");tap("Цитаты");tap("+  Добавить");tap("Цитата");input(R.id.note_title,"Цитата со страницы 25");input(R.id.note_body,"Текст цитаты из бумажной книги.");tap("Готово");
  expect(library.notes(id).size()==5,"Five records saved");for(Library.Note n:library.notes(id))if(n.type.equals("quote")||n.type.equals("thought"))expect(n.source==25,"Page source");String md=library.markdown(id,library.load(id));expect(md.contains("### Раздел I. Начало")&&md.contains("#### Глава 1. Знакомство")&&md.contains("##### Вывод читателя")&&md.contains("Источник: страница 25"),"Hierarchy and page exported");pass("Мысль, герой и ручная цитата; вложенность и страница в Markdown");
  tap("Закрыть конспект");tap("О книге");tap("Продолжить чтение");for(int i=0;i<100&&view(R.id.paper_page)==null;i++)SystemClock.sleep(100);expect(((EditText)view(R.id.paper_page)).getText().toString().equals("25"),"Progress restored");tap("Конспект");tap("Структура");shot("paper-restored");pass("Повторное открытие сохраняет прогресс и структуру");
 }
 @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();folder=new File(getTargetContext().getFilesDir(),"paper-checks-0.1.7");folder.mkdirs();try{runChecks();report.put("result","PASS");result.putString("result","PASS: "+checks.length()+" groups");}catch(Throwable e){try{report.put("result","FAIL").put("error",e.toString());}catch(Exception ignored){}result.putString("result","FAIL: "+e);}try{report.put("checks",checks).put("demoBook",id);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"checks.json")),"UTF-8")){out.write(report.toString(2));}}catch(Exception ignored){}finish(report.optString("result").equals("PASS")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);}
}
