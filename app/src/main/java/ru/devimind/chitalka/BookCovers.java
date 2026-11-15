package ru.devimind.chitalka;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.*;
import java.util.concurrent.*;

/** A private, bounded image copy survives gallery changes; FB2 and SQLite stay untouched. */
final class BookCovers {
    // Import and subsequent screen loads stay ordered across Activity recreation.
    static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final long MAX_SOURCE=32L*1024*1024;
    private static BitmapFactory.Options options(int width,int height,int edge)throws IOException{
        if(width<=0||height<=0)throw new IOException("Не удалось прочитать изображение. Выберите другой файл.");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(width,height)/(long)options.inSampleSize>edge)options.inSampleSize*=2;return options;
    }
    private static Bitmap decode(File file,int edge)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
        Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options(bounds.outWidth,bounds.outHeight,edge));if(bitmap==null)throw new IOException("Не удалось прочитать изображение. Выберите другой файл.");return bitmap;
    }
    static Bitmap load(Library library,String id,int edge)throws Exception{
        File custom=library.file(id,".cover");AtomicFile saved=new AtomicFile(custom);
        try(InputStream input=saved.openRead()){
            // openRead also restores a backup left by an interrupted AtomicFile write.
            return decode(custom,edge);
        }catch(FileNotFoundException absent){/* Use the original book cover. */}
        catch(IOException corrupt){android.util.Log.e("Chitalka","Custom cover unreadable; using original",corrupt);}
        Library.BookRow row=library.book(id);if(row!=null&&row.pdf())return PdfBook.cover(library.file(id,".pdf"),edge);
        String data=library.load(id).cover;if(data.isEmpty())return null;
        byte[] bytes=Base64.decode(data.substring(data.indexOf(',')+1),Base64.DEFAULT);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options(bounds.outWidth,bounds.outHeight,edge));if(image==null)throw new IOException("Не удалось прочитать обложку книги");return image;
    }
    static void replace(Context context,File target,Uri uri)throws Exception{
        File input=File.createTempFile("cover-",".image",context.getCacheDir());Bitmap image=null;
        try{
            try(InputStream source=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(input)){
                if(source==null)throw new IOException("Не удалось открыть изображение");long size=0;byte[] buffer=new byte[65536];int count;while((count=source.read(buffer))!=-1){size+=count;if(size>MAX_SOURCE)throw new IOException("Выберите изображение размером до 32 МБ.");out.write(buffer,0,count);}
            }
            image=decode(input,1280);int orientation=ExifInterface.ORIENTATION_NORMAL;try{orientation=new ExifInterface(input.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}catch(IOException ignored){}
            Matrix matrix=new Matrix();switch(orientation){
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
                case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
                case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(-90);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(-90);break;
            }
            if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),matrix,true);if(rotated!=image)image.recycle();image=rotated;}
            AtomicFile saved=new AtomicFile(target);FileOutputStream output=saved.startWrite();try{if(!image.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("Не удалось сохранить обложку");saved.finishWrite(output);}catch(Throwable error){saved.failWrite(output);throw error;}
        }finally{if(image!=null&&!image.isRecycled())image.recycle();input.delete();}
    }
}
