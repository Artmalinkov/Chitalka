package ru.devimind.chitalka.checks;

import android.app.*;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.util.*;

/** Run against an installed v1 database before installing the new APK. */
public final class LegacyBookSeed extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context context=getTargetContext();JSONObject before=new JSONObject();SQLiteDatabase db=context.openOrCreateDatabase("library.db",0,null);if(db.getVersion()!=1)throw new AssertionError("Expected legacy database v1");
        try(Cursor cursor=db.rawQuery("SELECT id,title,author,chapter,scroll FROM books ORDER BY opened DESC LIMIT 1",null)){if(!cursor.moveToFirst())throw new AssertionError("Existing book required");before.put("id",cursor.getString(0)).put("title",cursor.getString(1)).put("author",cursor.getString(2)).put("chapter",cursor.getInt(3)).put("scroll",cursor.getDouble(4));}
        JSONArray original=new JSONArray();try(Cursor cursor=db.rawQuery("SELECT id FROM notes",null)){while(cursor.moveToNext())original.put(cursor.getString(0));}before.put("originalNotes",original);
        String section=UUID.randomUUID().toString(),thought=UUID.randomUUID().toString();int order=100000;
        for(String id:new String[]{section,thought}){ContentValues values=new ContentValues();values.put("id",id);values.put("book_id",before.getString("id"));values.put("type",id.equals(section)?"section":"thought");values.put("title",id.equals(section)?"Проверка раздела 015":"Проверка миграции 015");values.put("body",id.equals(section)?"":"Непустая мысль до обновления 015");if(id.equals(thought))values.put("parent_id",section);values.put("source",0);values.put("position",order++);values.put("updated",System.currentTimeMillis());db.insertOrThrow("notes",null,values);}
        before.put("section",section).put("thought",thought).put("preferences",new JSONObject(context.getSharedPreferences("settings",0).getAll()));db.close();
        File folder=new File(context.getFilesDir(),"book-checks-0.1.5");folder.mkdirs();try(Writer writer=new OutputStreamWriter(new FileOutputStream(new File(folder,"legacy.json")),"UTF-8")){writer.write(before.toString(2));}
        result.putString("result","PASS: legacy book and non-empty related notes recorded");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("result","FAIL: "+error);finish(Activity.RESULT_CANCELED,result);}}
}
