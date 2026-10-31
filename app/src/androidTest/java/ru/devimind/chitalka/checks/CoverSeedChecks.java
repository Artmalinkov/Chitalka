package ru.devimind.chitalka.checks;

import android.app.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import org.json.*;
import java.io.*;
import ru.devimind.chitalka.Library;

/** Seeds a covered book and a note using only APIs already present in 0.1.5. */
public final class CoverSeedChecks extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Library library=new Library(getTargetContext());File folder=new File(getTargetContext().getFilesDir(),"cover-checks-0.1.6");folder.mkdirs();JSONArray books=new JSONArray(),notes=new JSONArray();for(Library.BookRow b:library.books()){books.put(b.id);for(Library.Note n:library.notes(b.id))notes.put(n.id);}
        Bitmap image=Bitmap.createBitmap(480,720,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.rgb(35,77,140));Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);paint.setTextSize(40);canvas.drawText("FB2 COVER",55,130,paint);ByteArrayOutputStream bytes=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,bytes);image.recycle();
        String xml="<FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0' xmlns:l='http://www.w3.org/1999/xlink'><description><title-info><book-title>Обложка 016</book-title><author><first-name>Автор</first-name></author><coverpage><image l:href='#cover'/></coverpage></title-info></description><body><section><title><p>Глава</p></title><p>Проверка обложки.</p></section></body><binary id='cover' content-type='image/png'>"+Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)+"</binary></FictionBook>";
        File source=new File(folder,"source.fb2");try(Writer out=new OutputStreamWriter(new FileOutputStream(source),"UTF-8")){out.write(xml);}String id=library.importBook(Uri.fromFile(source));Library.BookRow book=library.book(id);book.country="Россия";book.genre="Проверка";book.started="2026-10-01";library.saveBook(book);library.position(id,0,.35f);Library.Note note=library.add(id,"thought",0,"Мысль до обновления обложки 016");note.title="Мысль 016";library.save(note);
        JSONObject record=new JSONObject().put("id",id).put("note",note.id).put("originalBooks",books).put("originalNotes",notes);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"seed.json")),"UTF-8")){out.write(record.toString(2));}
        result.putString("result","PASS: covered book, metadata, position and non-empty note seeded on previous version");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("result","FAIL: "+error);finish(Activity.RESULT_CANCELED,result);}}
}
