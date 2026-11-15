package ru.devimind.chitalka.checks;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.widget.*;
import org.json.*;
import ru.devimind.chitalka.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercises only newly created fixtures; existing library books are never removed. */
public final class DeleteChecks extends Instrumentation {
    private Activity activity;private Library library;private File folder;
    private final List<String> own=new ArrayList<>();private final JSONArray checks=new JSONArray();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void expect(boolean value,String text){if(!value)throw new AssertionError(text);}
    private void pass(String text)throws Exception{checks.put(text);Bundle b=new Bundle();b.putString("check",text);sendStatus(0,b);}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value){if(node==null)return null;if(value.equalsIgnoreCase(String.valueOf(node.getText()))||value.equals(String.valueOf(node.getContentDescription()))||value.equals(node.getViewIdResourceName()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo result=find(node.getChild(i),value);if(result!=null)return result;}return null;}
    private void tap(String value){for(int i=0;i<60;i++){AccessibilityNodeInfo n=find(getUiAutomation().getRootInActiveWindow(),value);while(n!=null&&!n.isClickable())n=n.getParent();if(n!=null&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(250);return;}SystemClock.sleep(100);}throw new AssertionError("Missing control: "+value);}
    private void invoke(String name,String id)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,String.class);m.setAccessible(true);runOnMainSync(()->{try{m.invoke(activity,id);}catch(Exception e){throw new RuntimeException(e);}});SystemClock.sleep(400);}
    private void input(int id,String text){runOnMainSync(()->((EditText)activity.findViewById(id)).setText(text));}
    private void shot(String name)throws Exception{SystemClock.sleep(650);Bitmap image=getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    private void deleted(String id,boolean retained){for(int i=0;i<100;i++){Library.BookRow row=library.book(id);if(retained?row!=null&&row.archived:row==null){SystemClock.sleep(450);return;}SystemClock.sleep(100);}throw new AssertionError("Delete did not complete");}
    private boolean listed(String id,boolean saved){for(Library.BookRow row:saved?library.savedNotebooks():library.books())if(row.id.equals(id))return true;return false;}
    private void noCopies(String id){for(String ext:new String[]{".fb2",".pdf",".json",".cover",".cover.bak",".cover.new"})expect(!library.file(id,ext).exists(),"Private copy removed "+ext);}
    private File fb2()throws Exception{File f=new File(folder,"delete-test.fb2");try(Writer out=new OutputStreamWriter(new FileOutputStream(f),"UTF-8")){out.write("<FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0'><description><title-info><book-title>Удаление: тест "+UUID.randomUUID()+"</book-title></title-info></description><body><section><title><p>Глава источника</p></title><p>Книжный текст, который удаляется из приложения.</p></section></body></FictionBook>");}return f;}
    private File pdf()throws Exception{File f=new File(folder,"delete-test.pdf");PdfDocument doc=new PdfDocument();PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(300,400,1).create());Paint p=new Paint();p.setTextSize(24);page.getCanvas().drawText("PDF delete test "+UUID.randomUUID(),15,50,p);doc.finishPage(page);try(OutputStream out=new FileOutputStream(f)){doc.writeTo(out);}finally{doc.close();}return f;}
    private void keep(String id)throws Exception{invoke("showBookDetails",id);tap("Удалить книгу");tap("Сохранить конспект");deleted(id,true);expect(!listed(id,false)&&listed(id,true),"Separate notebook list");noCopies(id);}
    private void checks()throws Exception{
        AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
        File source=fb2();String id=library.importBook(Uri.fromFile(source));own.add(id);Library.BookRow row=library.book(id);row.author="Автор теста";row.country="Россия";row.genre="Проверка";library.saveBook(row);library.position(id,0,.42f);
        Library.Note section=library.addSection(id,"Раздел 1"),thought=library.add(id,"thought",0,"Непустая мысль");thought.title="Сохранённая мысль";thought.parent=section.id;library.save(thought);
        File cover=library.file(id,".cover");try(OutputStream out=new FileOutputStream(cover)){out.write(new byte[]{1,2,3});}
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        invoke("showBookDetails",id);tap("Удалить книгу");shot("delete-dialog");expect(find(getUiAutomation().getRootInActiveWindow(),"Сохранить конспект")!=null&&find(getUiAutomation().getRootInActiveWindow(),"Удалить вместе с конспектом")!=null,"Explicit alternatives");tap("Отмена");expect(listed(id,false)&&library.note(thought.id)!=null&&library.file(id,".fb2").isFile(),"Cancel changes nothing");pass("Корзина в карточке, явный выбор и отмена без изменений");
        keep(id);expect(source.isFile(),"External source untouched");expect(library.notes(id).size()==2&&library.note(thought.id).parent.equals(section.id),"Hierarchy preserved");Fb2.Book stored=library.load(id);expect(stored.chapters.size()==1&&stored.chapters.get(0).html.isEmpty()&&stored.cover.isEmpty()&&stored.footnotes.isEmpty(),"Only source labels retained, no text");expect(library.markdown(id,stored).contains("Глава источника"),"Source title retained in export");pass("FB2 удалён из библиотеки и памяти приложения; конспект, связи и названия источников сохранены");
        tap("Сохранённые конспекты");tap(row.label()+"\n"+row.author);tap("Мои мысли");tap("Сохранённая мысль");input(R.id.note_body,"Изменённая сохранённая мысль");tap("Готово");expect(library.note(thought.id).body.equals("Изменённая сохранённая мысль"),"Saved notes editable");shot("saved-notebook");
        ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction()))return null;expect("text/markdown".equals(intent.getType()),"Markdown export");return new ActivityResult(Activity.RESULT_CANCELED,null);}};addMonitor(monitor);tap("Экспорт");for(int i=0;i<50&&getLast(monitor)==0;i++)SystemClock.sleep(100);removeMonitor(monitor);Field export=MainActivity.class.getDeclaredField("exportText");export.setAccessible(true);expect(String.valueOf(export.get(activity)).contains("Изменённая сохранённая мысль"),"Export contains saved notebook");
        ActivityMonitor recreate=addMonitor(MainActivity.class.getName(),null,false);runOnMainSync(activity::recreate);Activity next=waitForMonitorWithTimeout(recreate,5000);removeMonitor(recreate);expect(next!=null,"Recreated saved notebook");activity=next;SystemClock.sleep(700);tap("Сохранённая мысль");expect(((EditText)activity.findViewById(R.id.note_body)).getText().toString().equals("Изменённая сохранённая мысль"),"State restored without source book");tap("Готово");tap("В библиотеку");pass("Сохранённый конспект доступен из библиотеки: редактирование, экспорт и пересоздание экрана");
        String restored=library.importBook(Uri.fromFile(source));expect(restored.equals(id)&&listed(id,false)&&!listed(id,true),"Same file restores original notebook");expect(library.notes(id).size()==2&&library.book(id).author.equals("Автор теста")&&Math.abs(library.book(id).scroll-.42f)<.001,"Metadata, position and notes retained after reimport");expect(!library.load(id).chapters.get(0).html.isEmpty(),"Restored book text available");pass("Повторный импорт того же FB2 возвращает книгу к сохранённому конспекту без дубликатов");
        invoke("showBookDetails",id);tap("Удалить книгу");tap("Удалить вместе с конспектом");deleted(id,false);expect(library.note(section.id)==null&&library.note(thought.id)==null&&!listed(id,true)&&source.exists(),"Cascade removes only chosen notebook");noCopies(id);pass("Удаление вместе с конспектом удаляет записи и связи; исходный файл остаётся");
        String paper=library.addPaper();own.add(paper);Library.Note pn=library.add(paper,"thought",12,"Бумажная мысль");keep(paper);expect(library.note(pn.id)!=null,"Paper notebook retained");invoke("showBookDetails",paper);tap("Удалить конспект");tap("Отмена");expect(library.note(pn.id)!=null,"Cancel saved notebook delete");tap("Удалить конспект");tap("Удалить");deleted(paper,false);expect(library.note(pn.id)==null,"Saved notebook final removal");pass("Бумажная книга: сохранить конспект, отменить и подтвердить его отдельное удаление");
        File pdf=pdf();String pid=library.importBook(Uri.fromFile(pdf));own.add(pid);Library.Note quote=library.add(pid,"quote",1,"Цитата PDF");keep(pid);expect(library.markdown(pid,library.load(pid)).contains("Источник: страница 1"),"PDF page reference retained");expect(library.importBook(Uri.fromFile(pdf)).equals(pid)&&library.note(quote.id)!=null,"PDF reimport reconnects notes");invoke("showBookDetails",pid);tap("Удалить книгу");tap("Удалить вместе с конспектом");deleted(pid,false);noCopies(pid);expect(pdf.exists()&&library.note(quote.id)==null,"PDF file outside app retained");pass("PDF: оба режима удаления, ссылка на страницу и восстановление связи при повторном импорте");
        try(android.database.Cursor c=library.getReadableDatabase().rawQuery("PRAGMA foreign_key_check",null)){expect(!c.moveToFirst(),"Foreign keys intact");}pass("Связи базы данных целостны после всех вариантов удаления");
    }
    private int getLast(ActivityMonitor monitor){return monitor.getHits();}
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();try{library=new Library(getTargetContext());folder=new File(getTargetContext().getFilesDir(),"delete-checks-0.1.8");folder.mkdirs();checks();report.put("result","PASS");result.putString("result","PASS: "+checks.length()+" groups");}catch(Throwable error){try{report.put("result","FAIL").put("error",error.toString());}catch(Exception ignored){}result.putString("result","FAIL: "+error);}
        finally{for(String id:own)try{library.deleteBook(id,true);}catch(Exception ignored){}}
        try{report.put("checks",checks);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"checks.json")),"UTF-8")){out.write(report.toString(2));}}catch(Exception ignored){}
        finish("PASS".equals(report.optString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
