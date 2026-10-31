package ru.devimind.chitalka.checks;

import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import java.io.*;

/** Test APK only: a non-seekable image provider, like a document picker result. */
public final class CoverImagesProvider extends ContentProvider {
    private boolean disabled;
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){disabled=method.equals("disable");return Bundle.EMPTY;}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
        if(disabled)throw new FileNotFoundException("Source is no longer available");
        try{ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();new Thread(()->{try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
            if(uri.getLastPathSegment().equals("broken")){output.write(new byte[]{1,2,3,4});return;}
            Bitmap image=Bitmap.createBitmap(2048,3072,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.rgb(28,135,86));Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.WHITE);paint.setTextSize(180);canvas.drawText("MY COVER",150,420,paint);image.compress(Bitmap.CompressFormat.PNG,100,output);image.recycle();
        }catch(IOException ignored){}}).start();return pipe[0];}catch(IOException error){throw new FileNotFoundException(error.toString());}
    }
    @Override public String getType(Uri uri){return "image/png";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
