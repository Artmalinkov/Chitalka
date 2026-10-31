package ru.devimind.chitalka.checks;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.webkit.WebView;
import android.widget.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import ru.devimind.chitalka.*;

public final class CoverChecks extends Instrumentation {
    private Activity activity;private Library library;private File folder;private JSONObject seed;private String id;private Bundle args;
    private final JSONArray checks=new JSONArray();private static final Uri IMAGE=Uri.parse("content://ru.devimind.chitalka.checks.images/replacement");
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private void expect(boolean value,String message){if(!value)throw new AssertionError(message);}
    private void pass(String name)throws Exception{checks.put(new JSONObject().put("name",name).put("result","PASS"));Bundle out=new Bundle();out.putString("check",name);sendStatus(0,out);}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value){if(node==null)return null;if(value.equalsIgnoreCase(String.valueOf(node.getText()))||value.equals(String.valueOf(node.getContentDescription()))||value.equals(node.getViewIdResourceName()))return node;if("android.webkit.WebView".contentEquals(node.getClassName()))return null;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),value);if(found!=null)return found;}return null;}
    private void tap(String label){for(int i=0;i<35;i++){AccessibilityNodeInfo node=find(getUiAutomation().getRootInActiveWindow(),label);while(node!=null&&!node.isClickable())node=node.getParent();if(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){SystemClock.sleep(160);return;}SystemClock.sleep(100);}throw new AssertionError("Control not found: "+label);}
    private View view(int id){AtomicReference<View> value=new AtomicReference<>();runOnMainSync(()->value.set(activity.findViewById(id)));return value.get();}
    private int center(ImageView image){if(!(image.getDrawable() instanceof BitmapDrawable))return 0;Bitmap b=((BitmapDrawable)image.getDrawable()).getBitmap();return b.getPixel(b.getWidth()/2,b.getHeight()/2);}
    private void waitColor(ImageView image,int color){for(int i=0;i<150;i++){AtomicReference<Integer> found=new AtomicReference<>();runOnMainSync(()->found.set(center(image)));if(found.get()==color)return;SystemClock.sleep(100);}throw new AssertionError("Expected cover color "+color);}
    private String hash(File file)throws Exception{byte[] data;try(InputStream input=new FileInputStream(file)){data=input.readAllBytes();}return android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(data),android.util.Base64.NO_WRAP);}
    private void shot(String name)throws Exception{SystemClock.sleep(550);Bitmap image=getUiAutomation().takeScreenshot();try(OutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    private void pick(Uri image,boolean cancel)throws Exception{
        final boolean[] intercepted={false};ActivityMonitor monitor=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){if(!Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()))return null;expect("image/*".equals(intent.getType())&&intent.hasCategory(Intent.CATEGORY_OPENABLE),"Image-only system document picker");intercepted[0]=true;return new ActivityResult(cancel?Activity.RESULT_CANCELED:Activity.RESULT_OK,cancel?null:new Intent().setData(image));}};
        addMonitor(monitor);tap("Изменить обложку");removeMonitor(monitor);expect(intercepted[0],"System picker intent intercepted");
    }
    private void runChecks()throws Exception{
        expect(library.book(id).country.equals("Россия")&&library.book(id).genre.equals("Проверка")&&Math.abs(library.book(id).scroll-.35)<.0001,"Old metadata and position retained");expect(library.note(seed.getString("note")).body.equals("Мысль до обновления обложки 016"),"Non-empty note retained");pass("Обновление сохранило сведения, позицию и непустую мысль");
        getTargetContext().getContentResolver().call(IMAGE,"enable",null,null);
        AccessibilityServiceInfo info=getUiAutomation().getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(info);
        activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));tap("О книге: Обложка 016");
        ImageView cover=(ImageView)view(R.id.book_cover);waitColor(cover,Color.rgb(35,77,140));Rect image=new Rect(),author=new Rect();runOnMainSync(()->{cover.getGlobalVisibleRect(image);activity.findViewById(R.id.book_author).getGlobalVisibleRect(author);});expect(image.top<author.top,"Cover at top before metadata");expect(view(R.id.read_book)!=null,"Read button retained");shot("original-cover");pass("Вверху карточки отображается обложка из FB2 целиком, кнопка чтения сохранена");
        String original=hash(library.file(id,".fb2")),cached=hash(library.file(id,".json"));runOnMainSync(()->((EditText)activity.findViewById(R.id.book_author)).setText("Автор изменён 016"));pick(IMAGE,true);expect(!library.file(id,".cover").exists(),"Cancel keeps original cover");waitColor(cover,Color.rgb(35,77,140));expect(library.book(id).author.equals("Автор изменён 016"),"Metadata saved before picker");pass("Отмена выбора сохраняет прежнюю обложку и изменения карточки");
        pick(IMAGE,false);waitColor(cover,Color.rgb(28,135,86));Bitmap saved=library.cover(id,1280);expect(saved.getWidth()<=1280&&saved.getHeight()<=1280&&Math.abs(saved.getWidth()/(float)saved.getHeight()-2f/3)<.01,"Large image scaled with original proportions");saved.recycle();expect(hash(library.file(id,".fb2")).equals(original)&&hash(library.file(id,".json")).equals(cached),"Source FB2 and cache untouched");shot("custom-cover");pass("Выбор изображения через системное окно заменяет обложку; размер ограничен, пропорции и FB2 сохранены");
        String custom=hash(library.file(id,".cover"));pick(Uri.parse("content://ru.devimind.chitalka.checks.images/broken"),false);tap("Понятно");expect(hash(library.file(id,".cover")).equals(custom),"Bad image does not replace prior cover");waitColor(cover,Color.rgb(28,135,86));pass("Повреждённый файл отклоняется, прежняя пользовательская обложка остаётся");
        File rotated=new File(folder,"rotated.jpg");Bitmap jpeg=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888);jpeg.eraseColor(Color.RED);try(OutputStream output=new FileOutputStream(rotated)){jpeg.compress(Bitmap.CompressFormat.JPEG,90,output);}jpeg.recycle();ExifInterface exif=new ExifInterface(rotated.getAbsolutePath());exif.setAttribute(ExifInterface.TAG_ORIENTATION,String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));exif.saveAttributes();library.replaceCover(id,Uri.fromFile(rotated));saved=library.cover(id,1280);expect(saved.getWidth()==200&&saved.getHeight()==400,"Photo EXIF rotation applied");saved.recycle();library.replaceCover(id,IMAGE);pass("Ориентация фотографии из EXIF учтена при сохранении");
        getTargetContext().getContentResolver().call(IMAGE,"disable",null,null);ActivityMonitor monitor=addMonitor(MainActivity.class.getName(),null,false);runOnMainSync(activity::recreate);Activity restored=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);expect(restored!=null,"Activity recreated");activity=restored;for(int i=0;i<30&&view(R.id.book_cover)==null;i++)SystemClock.sleep(100);waitColor((ImageView)view(R.id.book_cover),Color.rgb(28,135,86));pass("Обложка сохраняется после пересоздания экрана даже при недоступном исходном изображении");
        tap("В библиотеку");View card=findView(activity.getWindow().getDecorView(),"О книге: Обложка 016");expect(card!=null,"Own book in library");ImageView thumbnail=card.findViewById(R.id.library_cover);waitColor(thumbnail,Color.rgb(28,135,86));shot("library-custom-cover");pass("Новая обложка показывается также в общей библиотеке");
    }
    private View findView(View root,String description){if(description.equals(root.getContentDescription()==null?null:root.getContentDescription().toString()))return root;if(root instanceof WebView)return null;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View result=findView(((ViewGroup)root).getChildAt(i),description);if(result!=null)return result;}return null;}
    @Override public void onStart(){Bundle result=new Bundle();JSONObject report=new JSONObject();boolean cleanup=args!=null&&"true".equals(args.getString("cleanup"));try{
        folder=new File(getTargetContext().getFilesDir(),"cover-checks-0.1.6");seed=new JSONObject(Library.read(new FileInputStream(new File(folder,"seed.json"))));id=seed.getString("id");library=new Library(getTargetContext());
        if(cleanup){expect(library.book(id).title.equals("Обложка 016"),"Only own fixture");library.getWritableDatabase().delete("books","id=?",new String[]{id});for(String extension:new String[]{".fb2",".json",".cover"})library.file(id,extension).delete();result.putString("result","PASS: own covered book and note removed");finish(Activity.RESULT_OK,result);return;}
        runChecks();report.put("result","PASS");result.putString("result","PASS: "+checks.length()+" checks");
    }catch(Throwable error){try{report.put("result","FAIL").put("error",error.toString());}catch(Exception ignored){}result.putString("result","FAIL: "+error);}
    finally{if(!cleanup)try{report.put("checks",checks);try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(folder,"checks.json")),"UTF-8")){out.write(report.toString(2));}}catch(Exception ignored){}}
        finish(report.optString("result").equals("PASS")?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
