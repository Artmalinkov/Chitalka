package ru.devimind.chitalka;

import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.*;

/** PDF bytes stay local; only a bounded bitmap of one page is decoded at a time. */
final class PdfBook {
    static int count(File file)throws IOException{
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor)){int count=renderer.getPageCount();if(count<1)throw new IOException("В PDF нет страниц");return count;}
        catch(SecurityException error){throw new IOException("PDF защищён паролем. Выберите копию без пароля.",error);}
        catch(IllegalArgumentException error){throw new IOException("Не удалось открыть PDF. Возможно, файл повреждён.",error);}
        catch(IOException error){throw new IOException("Не удалось открыть PDF. Возможно, файл повреждён.",error);}
    }
    static Bitmap render(PdfRenderer renderer,int index,int edge){
        try(PdfRenderer.Page page=renderer.openPage(index)){
            float scale=Math.min(3f,edge/(float)Math.max(page.getWidth(),page.getHeight()));
            Bitmap image=Bitmap.createBitmap(Math.max(1,Math.round(page.getWidth()*scale)),Math.max(1,Math.round(page.getHeight()*scale)),Bitmap.Config.ARGB_8888);
            image.eraseColor(Color.WHITE);try{page.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);return image;}catch(RuntimeException|Error error){image.recycle();throw error;}
        }
    }
    static Bitmap cover(File file,int edge)throws IOException{
        try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor)){return render(renderer,0,edge);}
    }
}
