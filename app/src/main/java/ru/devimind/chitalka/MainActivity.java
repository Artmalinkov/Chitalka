package ru.devimind.chitalka;

import android.app.*;
import android.animation.*;
import android.view.animation.DecelerateInterpolator;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int IMPORT=10,EXPORT=11,COVER_IMAGE=12;
    private Library library; private Design d; private SharedPreferences prefs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private FrameLayout root,paneLayer,readingSurface;private LinearLayout screen,pane;private WebView web;
    private String bookId,exportText; private Fb2.Book book;private int chapter,tab;private float fraction;
    private boolean restoring,loading,paneOpen,dirty;
    private String readingAnchor="";
    private float touchX,touchY; private long touchTime;
    private PageAnimationView pageAnimation;private ValueAnimator pageAnimator;
    private Bitmap turnPrevious,turnNext;private boolean preparingTurn;private int turnToken;
    private Library.Note editing;private EditText noteTitle,noteBody,structureType;private TextView saveStatus,chapterCount,partLabel;
    private BookDetailsForm detailsForm;private String pendingBookId;private int openToken;private boolean readingStartedMarked;
    private String coverBookId;private PdfReaderView pdfView;
    private boolean readerActive,readerControlsVisible;private View readerHeader;
    private static final String[] SORT_FIELDS={"opened","author","title","country","genre","started","finished"};
    private static final String[] SORT_LABELS={"Недавно открытые","Автор","Название","Страна автора","Жанр","Дата начала чтения","Дата завершения чтения"};
    private final Runnable saveNoteJob=()->flushNote();
    private final Runnable savePositionJob=()->savePosition();

    @Override public void onCreate(Bundle state){super.onCreate(state);library=new Library(this);prefs=getSharedPreferences("settings",MODE_PRIVATE);exportText=prefs.getString("pending_export","");if(state!=null){tab=state.getInt("tab",0);coverBookId=state.getString("cover_book");}buildRoot();
        if(state!=null&&state.getString("book")!=null){String editor=state.getString("editing");boolean open=state.getBoolean("pane");openBook(state.getString("book"),()->{if(open){showPane();if(editor!=null)openEditor(library.note(editor));}});}else if(state!=null&&state.getString("details")!=null)showBookDetails(state.getString("details"));else showLibrary();}
    private void buildRoot(){
        d=new Design(this,prefs.getBoolean("light",false));root=new FrameLayout(this);root.setBackgroundColor(d.bg);setContentView(root);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if(Build.VERSION.SDK_INT>=30){
            root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets safe=insets.getInsets((readerActive?0:WindowInsets.Type.systemBars())|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(safe.left,safe.top,safe.right,safe.bottom);return insets;});
            WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null)controller.setSystemBarsAppearance(d.light?WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }else{root.setFitsSystemWindows(true);getWindow().setStatusBarColor(d.bg);getWindow().setNavigationBarColor(d.bg);}
    }
    private LinearLayout frame(){readerActive=false;readerControlsVisible=false;readerHeader=null;applyReaderWindow();root.removeAllViews();screen=d.column();screen.setBackgroundColor(d.bg);root.addView(screen,new FrameLayout.LayoutParams(-1,-1));return screen;}
    private void applyReaderWindow(){
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(readerActive)controller.hide(WindowInsets.Type.systemBars());else controller.show(WindowInsets.Type.systemBars());}if(root!=null)root.requestApplyInsets();}
        else{getWindow().getDecorView().setSystemUiVisibility(readerActive?View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION:View.SYSTEM_UI_FLAG_VISIBLE);if(root!=null){root.setFitsSystemWindows(!readerActive);if(readerActive)root.setPadding(0,0,0,0);}}
    }
    private void enterReader(FrameLayout stage,View header){readerHeader=header;readerHeader.setId(R.id.reader_controls);readerHeader.setBackgroundColor(d.bg);stage.addView(header,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));readerActive=true;setReaderControls(false);applyReaderWindow();}
    private void setReaderControls(boolean visible){readerControlsVisible=readerActive&&visible;if(readerHeader!=null)readerHeader.setVisibility(readerControlsVisible?View.VISIBLE:View.GONE);if(pdfView!=null)pdfView.setControlsVisible(readerControlsVisible);}
    private void toggleReaderControls(){if(readerActive&&!paneOpen){cancelPageTurn();setReaderControls(!readerControlsVisible);}}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&readerActive)applyReaderWindow();}
    private void add(LinearLayout into,View child,int width,int height){into.addView(child,new LinearLayout.LayoutParams(width<0?width:d.dp(width),height<0?height:d.dp(height)));}
    private void weighted(LinearLayout into,View view){into.addView(view,new LinearLayout.LayoutParams(0,-2,1));}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void fail(String action,Exception e){android.util.Log.e("Chitalka",action,e);new AlertDialog.Builder(dialogContext()).setTitle(action).setMessage(e.getMessage()==null?"Попробуйте ещё раз. Данные сохранены на устройстве.":e.getMessage()).setPositiveButton("Понятно",null).show();}
    private Context dialogContext(){return new ContextThemeWrapper(this,d.light?android.R.style.Theme_Material_Light_Dialog_Alert:android.R.style.Theme_Material_Dialog_Alert);}
    private void hideKeyboard(){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);}

    private void showLibrary(){
        if(detailsForm!=null&&!detailsForm.save())return;detailsForm=null;openToken++;pendingBookId=null;loading=false;
        cancelPageTurn();flushNote();savePosition();releaseReader();paneOpen=false;editing=null;bookId=null;frame();
        LinearLayout header=d.row();header.setPadding(d.dp(12),d.dp(12),d.dp(12),d.dp(8));add(header,d.icon("book","Читалка",()->{}),48,48);
        LinearLayout brand=d.column();brand.addView(d.title("Читалка",27));brand.addView(d.text("Книги и собственные мысли",11,d.muted));weighted(header,brand);add(header,d.icon("gear","Настройки",this::settings),44,44);add(screen,header,-1,80);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout content=d.column();content.setPadding(d.dp(20),d.dp(22),d.dp(20),d.dp(24));scroll.addView(content);
        TextView badge=d.text("НА УСТРОЙСТВЕ",10,d.silver);badge.setLetterSpacing(.14f);content.addView(badge);d.gap(content,8);content.addView(d.title("Библиотека",30));d.gap(content,6);content.addView(d.text("Читать. Замечать. Запоминать.",14,d.muted));d.gap(content,24);
        String sort=prefs.getString("library_sort","opened");boolean descending=prefs.getBoolean("library_desc",true);int selected=0;for(int i=0;i<SORT_FIELDS.length;i++)if(SORT_FIELDS[i].equals(sort))selected=i;
        List<Library.BookRow> books=library.books(sort,descending);
        if(!books.isEmpty()){
            LinearLayout sorting=d.row();TextView choose=d.button("Сортировка: "+SORT_LABELS[selected],false,this::sortDialog);choose.setTextSize(12);choose.setId(R.id.library_sort);sorting.addView(choose,new LinearLayout.LayoutParams(0,d.dp(52),1));
            TextView direction=d.button(descending?"↓":"↑",false,()->{prefs.edit().putBoolean("library_desc",!descending).apply();showLibrary();});direction.setId(R.id.sort_direction);direction.setContentDescription(descending?"По убыванию. Изменить порядок":"По возрастанию. Изменить порядок");LinearLayout.LayoutParams order=new LinearLayout.LayoutParams(d.dp(48),d.dp(52));order.leftMargin=d.dp(8);sorting.addView(direction,order);content.addView(sorting);d.gap(content,16);
        }
        if(books.isEmpty()){
            LinearLayout empty=d.column();empty.setPadding(d.dp(22),d.dp(28),d.dp(22),d.dp(26));empty.setBackground(d.surface(d.panel));
            View mark=d.icon("book","Книга",()->{});add(empty,mark,56,56);d.gap(empty,18);empty.addView(d.title("Начните с одной книги",23));d.gap(empty,12);
            TextView help=d.text("Добавьте файл FB2, PDF или бумажную книгу и собирайте собственный конспект.",16,d.muted);help.setLineSpacing(d.dp(4),1);empty.addView(help);d.gap(empty,22);add(empty,d.button("Добавить книгу",true,this::addBookDialog),-1,52);content.addView(empty);
        }else{
            for(Library.BookRow row:books){
                LinearLayout card=d.row();card.setPadding(d.dp(14),d.dp(16),d.dp(14),d.dp(16));card.setBackground(d.surface(d.panel));
                ImageView cover=new ImageView(this);cover.setId(R.id.library_cover);cover.setContentDescription("Обложка: "+row.label());cover.setImageResource(R.drawable.ic_launcher);cover.setScaleType(ImageView.ScaleType.FIT_CENTER);add(card,cover,72,106);
                LinearLayout labels=d.column();labels.setPadding(d.dp(16),0,0,0);TextView title=d.title(row.label(),20);title.setMaxLines(3);labels.addView(title);d.gap(labels,8);labels.addView(d.text(row.author,13,d.muted));
                String tags=row.country+(row.country.isEmpty()||row.genre.isEmpty()?"":" · ")+row.genre;if(!tags.isEmpty()){d.gap(labels,6);labels.addView(d.text(tags,11,d.muted));}
                d.gap(labels,12);String progress=!row.finished.isEmpty()?"Завершено · "+BookDetailsForm.displayDate(row.finished):!row.started.isEmpty()?"Читаю с "+BookDetailsForm.displayDate(row.started):"О книге · глава "+(row.chapter+1);labels.addView(d.text(progress,12,d.silver));weighted(card,labels);card.setContentDescription("О книге: "+row.label());card.setOnClickListener(v->showBookDetails(row.id));content.addView(card,new LinearLayout.LayoutParams(-1,-2));d.gap(content,14);
                loadCover(row.id,cover,d.dp(106));
            }
            d.gap(content,8);add(content,d.button("+  Добавить книгу",true,this::addBookDialog),-1,52);
        }
        if(!library.savedNotebooks().isEmpty()){d.gap(content,18);add(content,d.button("Сохранённые конспекты",false,this::chooseSavedNotebook),-1,52);}
        TextView local=d.text("Локально · ваши книги и записи",12,d.muted);local.setPadding(d.dp(20),d.dp(14),d.dp(20),d.dp(16));screen.addView(local);
    }
    private void sortDialog(){String current=prefs.getString("library_sort","opened");int selected=0;for(int i=0;i<SORT_FIELDS.length;i++)if(SORT_FIELDS[i].equals(current))selected=i;new AlertDialog.Builder(dialogContext()).setTitle("Сортировка библиотеки").setSingleChoiceItems(SORT_LABELS,selected,(dialog,which)->{prefs.edit().putString("library_sort",SORT_FIELDS[which]).putBoolean("library_desc",which==0||which>=5).apply();dialog.dismiss();showLibrary();}).setNegativeButton("Отмена",null).show();}
    private void showBookDetails(String id){
        if(detailsForm!=null&&!detailsForm.save())return;detailsForm=null;openToken++;pendingBookId=null;
        cancelPageTurn();flushNote();savePosition();releaseReader();bookId=null;loading=false;paneOpen=false;editing=null;hideKeyboard();frame();
        Library.BookRow row=library.book(id);if(row==null){showLibrary();return;}if(row.archived){openBook(id,null);return;}
        detailsForm=new BookDetailsForm(this,d,library,row,this::showLibrary,()->{hideKeyboard();openBook(id,null);},this::pickCover,()->confirmDeleteBook(id));screen.addView(detailsForm,new LinearLayout.LayoutParams(-1,-1));loadCover(id,detailsForm.cover,1280);
    }
    private boolean archived(){Library.BookRow row=bookId==null?null:library.book(bookId);return row!=null&&row.archived;}
    private void chooseSavedNotebook(){List<Library.BookRow> rows=library.savedNotebooks();String[] titles=new String[rows.size()];for(int i=0;i<titles.length;i++)titles[i]=rows.get(i).label()+"\n"+rows.get(i).author;new AlertDialog.Builder(dialogContext()).setTitle("Сохранённые конспекты").setItems(titles,(dialog,index)->openBook(rows.get(index).id,null)).setNegativeButton("Отмена",null).show();}
    private void confirmDeleteBook(String id){Library.BookRow row=library.book(id);if(row==null)return;hideKeyboard();new AlertDialog.Builder(dialogContext()).setTitle("Удалить книгу?").setMessage("«"+row.label()+"»\n\nУдалить вместе с конспектом? Если сохранить конспект, он останется доступен в библиотеке. Исходный файл на телефоне останется.").setPositiveButton("Удалить вместе с конспектом",(a,b)->removeBook(id,true)).setNeutralButton("Сохранить конспект",(a,b)->removeBook(id,false)).setNegativeButton("Отмена",null).show();}
    private void removeBook(String id,boolean withNotebook){
        if(detailsForm!=null&&!detailsForm.save())return;detailsForm=null;flushNote();cancelPageTurn();releaseReader();openToken++;pendingBookId=null;bookId=null;editing=null;paneOpen=false;hideKeyboard();busy("Удаляем книгу…");
        BookCovers.WORKER.execute(()->{Exception failure=null;try{library.deleteBook(id,withNotebook);if(withNotebook)prefs.edit().remove("anchor_"+id).apply();}catch(Exception error){failure=error;}final Exception error=failure;handler.post(()->{if(isDestroyed()||isFinishing())return;showLibrary();if(error!=null)fail("Не удалось завершить удаление",error);else toast(withNotebook?"Книга и конспект удалены":"Книга удалена. Конспект сохранён.");});});
    }
    private void showSavedNotebook(){
        releaseReader();frame();paneOpen=false;editing=null;paneLayer=null;
        LinearLayout header=d.row();add(header,d.icon("back","В библиотеку",this::showLibrary),44,52);weighted(header,d.title("Сохранённый конспект",18));add(header,d.button("Экспорт",false,()->{flushNote();exportBook(bookId);}),80,48);add(header,d.icon("trash","Удалить конспект",()->new AlertDialog.Builder(dialogContext()).setTitle("Удалить конспект?").setMessage("«"+book.title+"». Все записи будут удалены без возможности восстановления.").setPositiveButton("Удалить",(a,b)->removeBook(bookId,true)).setNegativeButton("Отмена",null).show()),44,48);add(screen,header,-1,64);
        pane=d.column();pane.setPadding(d.dp(16),0,d.dp(16),d.dp(12));screen.addView(pane,new LinearLayout.LayoutParams(-1,0,1));paneOpen=true;showNoteList();
    }
    private void loadCover(String id,ImageView image,int edge){BookCovers.WORKER.execute(()->{try{Bitmap bitmap=library.cover(id,edge);handler.post(()->{if(bitmap==null)return;if(isDestroyed()||!image.isAttachedToWindow()){bitmap.recycle();return;}image.setImageBitmap(bitmap);});}catch(Exception|OutOfMemoryError error){android.util.Log.e("Chitalka","Cover load failed",error);}});}
    private void pickCover(){if(detailsForm==null||!detailsForm.save())return;hideKeyboard();coverBookId=detailsForm.book.id;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(intent,COVER_IMAGE);}catch(ActivityNotFoundException error){coverBookId=null;fail("Не удалось открыть выбор изображения",error);}}
    private boolean paper(){Library.BookRow row=bookId==null?null:library.book(bookId);return row!=null&&row.paper();}
    private void addBookDialog(){new AlertDialog.Builder(dialogContext()).setTitle("Добавить книгу").setItems(new String[]{"Электронная · файл FB2","PDF-книга","Бумажная книга"},(dialog,which)->{if(which==0)pickBook();else if(which==1)startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE),IMPORT);else showBookDetails(library.addPaper());}).setNegativeButton("Отмена",null).show();}
    private boolean pdf(){Library.BookRow row=bookId==null?null:library.book(bookId);return row!=null&&row.pdf();}
    private void showPdfReader(){
        releaseReader();frame();paneOpen=false;editing=null;Library.BookRow row=library.book(bookId);final String id=bookId;
        LinearLayout header=d.row();add(header,d.icon("back","О книге",()->showBookDetails(id)),44,52);TextView title=d.title(row.label(),16);title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);weighted(header,title);add(header,d.button("Конспект",true,this::showPane),92,48);FrameLayout stage=new FrameLayout(this);screen.addView(stage,new LinearLayout.LayoutParams(-1,-1));
        pdfView=new PdfReaderView(this,d,library.file(id,".pdf"),Math.max(1,row.page),new PdfReaderView.Listener(){public void page(int page,int count){if(!id.equals(bookId))return;try{library.pdfPosition(id,page,count);library.markReadingStarted(id);}catch(Exception error){fail("Не удалось сохранить страницу PDF",error);}}public void failed(Exception error){if(id.equals(bookId))fail("Не удалось показать PDF",error);}public void controls(){toggleReaderControls();}});stage.addView(pdfView,new FrameLayout.LayoutParams(-1,-1));enterReader(stage,header);
    }
    private void showPaperReader(){
        frame();paneOpen=false;editing=null;library.markReadingStarted(bookId);Library.BookRow row=library.book(bookId);
        LinearLayout header=d.row();add(header,d.icon("back","О книге",()->showBookDetails(bookId)),44,52);weighted(header,d.title(row.label(),20));add(screen,header,-1,64);
        LinearLayout box=d.column();box.setPadding(d.dp(20),d.dp(20),d.dp(20),d.dp(20));screen.addView(box,new LinearLayout.LayoutParams(-1,-2));box.addView(d.text("БУМАЖНАЯ КНИГА",12,d.muted));d.gap(box,16);box.addView(d.text("Текущая страница",14,d.text));EditText current=d.edit("0",1);current.setId(R.id.paper_page);current.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);current.setText(String.valueOf(row.page));add(box,current,-1,54);d.gap(box,12);box.addView(d.text("Всего страниц · можно оставить пустым",14,d.text));EditText total=d.edit("Не указано",1);total.setId(R.id.paper_total);total.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);total.setText(row.total==0?"":String.valueOf(row.total));add(box,total,-1,54);d.gap(box,16);
        add(box,d.button("Сохранить прогресс",false,()->{try{int page=current.getText().toString().trim().isEmpty()?0:Integer.parseInt(current.getText().toString().trim());int count=total.getText().toString().trim().isEmpty()?0:Integer.parseInt(total.getText().toString().trim());library.paperProgress(bookId,page,count);hideKeyboard();toast("Прогресс сохранён");}catch(IllegalArgumentException error){toast("Проверьте страницы: текущая не должна превышать общее число.");}}),-1,52);d.gap(box,20);add(box,d.button("Конспект",true,this::showPane),-1,52);
    }
    private void pickBook(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra("android.provider.extra.INITIAL_URI",Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FChitalka"));startActivityForResult(intent,IMPORT);}
    private void busy(String message){loading=true;frame();LinearLayout box=d.column();box.setGravity(Gravity.CENTER);box.setPadding(d.dp(24),0,d.dp(24),0);ProgressBar indicator=new ProgressBar(this);indicator.setId(R.id.loading_indicator);LinearLayout.LayoutParams progress=new LinearLayout.LayoutParams(d.dp(48),d.dp(48));progress.gravity=Gravity.CENTER_HORIZONTAL;box.addView(indicator,progress);d.gap(box,20);TextView label=d.text(message,17,d.text);label.setId(R.id.loading_label);label.setGravity(Gravity.CENTER);box.addView(label,new LinearLayout.LayoutParams(-1,-2));screen.addView(box,new LinearLayout.LayoutParams(-1,-1));}
    private void openBook(String id,Runnable after){
        if(detailsForm!=null&&!detailsForm.save())return;detailsForm=null;flushNote();savePosition();releaseReader();bookId=null;pendingBookId=id;readingStartedMarked=false;final int token=++openToken;busy("Открываем книгу…");
        worker.execute(()->{try{Fb2.Book loaded=library.load(id);Library.BookRow saved=library.book(id);handler.post(()->{if(token!=openToken||isFinishing()||isDestroyed())return;pendingBookId=null;bookId=id;book=loaded;chapter=Math.max(0,Math.min(saved.chapter,book.chapters.size()-1));fraction=saved.scroll;readingAnchor=prefs.getString("anchor_"+bookId,"");loading=false;showReader();if(after!=null)after.run();});}catch(Exception e){handler.post(()->{if(token!=openToken||isFinishing()||isDestroyed())return;loading=false;showBookDetails(id);fail("Не удалось открыть книгу",e);});}});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==COVER_IMAGE){String id=coverBookId;coverBookId=null;if(result!=RESULT_OK||data==null||data.getData()==null||id==null)return;Uri uri=data.getData();if(detailsForm!=null&&id.equals(detailsForm.book.id))detailsForm.coverSaving(true);
            BookCovers.WORKER.execute(()->{try{library.replaceCover(id,uri);handler.post(()->{if(isFinishing()||isDestroyed())return;if(detailsForm!=null&&id.equals(detailsForm.book.id)){detailsForm.coverSaving(false);loadCover(id,detailsForm.cover,1280);}});}catch(Exception|OutOfMemoryError error){handler.post(()->{if(isFinishing()||isDestroyed())return;if(detailsForm!=null&&id.equals(detailsForm.book.id))detailsForm.coverSaving(false);fail("Не удалось заменить обложку",error instanceof Exception?(Exception)error:new IOException("Недостаточно памяти для изображения. Выберите файл поменьше."));});}});return;
        }
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==IMPORT){busy("Добавляем книгу…");Uri uri=data.getData();final int token=++openToken;worker.execute(()->{try{String id=library.importBook(uri);handler.post(()->{if(token==openToken&&!isFinishing()&&!isDestroyed())showBookDetails(id);});}catch(Exception e){handler.post(()->{if(token!=openToken||isFinishing()||isDestroyed())return;loading=false;showLibrary();fail("Не удалось добавить книгу",e);});}});}
        if(request==EXPORT){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Не удалось сохранить файл");out.write(exportText.getBytes(java.nio.charset.StandardCharsets.UTF_8));toast("Конспект сохранён в Markdown");}catch(Exception e){fail("Экспорт не завершён",e);}}
    }
    private void showReader(){
        if(archived()){showSavedNotebook();return;}
        if(pdf()){showPdfReader();return;}
        if(paper()){releaseReader();showPaperReader();return;}
        cancelPageTurn();releaseReader();frame();paneOpen=false;editing=null;
        LinearLayout header=d.row();header.setPadding(d.dp(4),d.dp(4),d.dp(8),d.dp(4));add(header,d.icon("back","О книге",()->showBookDetails(bookId)),44,48);
        LinearLayout labels=d.column();TextView title=d.title(book.title,17);title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);labels.addView(title);TextView author=d.text(book.author,11,d.muted);author.setSingleLine(true);labels.addView(author);weighted(header,labels);
        TextView notes=d.button("Конспект",true,this::showPane);notes.setTextSize(12);add(header,notes,92,44);LinearLayout chrome=d.column();add(chrome,header,-1,62);chrome.addView(d.divider());
        LinearLayout meta=d.row();meta.setPadding(d.dp(20),0,d.dp(16),0);partLabel=d.text("",10,d.muted);partLabel.setLetterSpacing(.09f);partLabel.setSingleLine(true);partLabel.setEllipsize(TextUtils.TruncateAt.END);weighted(meta,partLabel);chapterCount=d.text("",11,d.silver);chapterCount.setPadding(d.dp(8),d.dp(8),0,d.dp(8));chapterCount.setOnClickListener(v->outline());meta.addView(chapterCount);add(chrome,meta,-1,38);
        web=new WebView(this);web.setId(R.id.book_body);web.setBackgroundColor(d.bg);web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)WebView.setWebContentsDebuggingEnabled(true);
        WebSettings ws=web.getSettings();ws.setJavaScriptEnabled(true);ws.setBlockNetworkLoads(true);ws.setAllowFileAccess(false);ws.setAllowContentAccess(false);ws.setBuiltInZoomControls(false);ws.setDefaultTextEncodingName("UTF-8");
        web.addJavascriptInterface(new ReaderHost(web,bookId),"ReaderHost");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return link(request.getUrl());}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return link(Uri.parse(url));}
        });
        web.setOnTouchListener((view,event)->{
            if(!"pages".equals(prefs.getString("reader_mode","scroll")))return false;
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){touchX=event.getX();touchY=event.getY();touchTime=event.getEventTime();}
            if(event.getActionMasked()==MotionEvent.ACTION_UP){float dx=event.getX()-touchX,dy=event.getY()-touchY;
                boolean corner="curl".equals(prefs.getString("page_animation","none"))&&touchX>view.getWidth()*.6f&&touchY<view.getHeight()*.35f;
                boolean swipe=event.getEventTime()-touchTime<650&&Math.abs(dx)>d.dp(55)&&Math.abs(dx)>Math.abs(dy)*1.5f;
                boolean pull=corner&&event.getEventTime()-touchTime<3000&&dx<-d.dp(40)&&dy>-d.dp(30);
                if(swipe||pull){
                    turnPage(dx<0?1:-1);
                    MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);view.onTouchEvent(cancel);cancel.recycle();return true;
                }
            }
            return false;
        });
        readingSurface=new FrameLayout(this);readingSurface.addView(web,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout stage=new FrameLayout(this);screen.addView(stage,new LinearLayout.LayoutParams(-1,-1));stage.addView(readingSurface,new FrameLayout.LayoutParams(-1,-1));enterReader(stage,chrome);renderChapter();
    }
    private void releaseReader(){if(pdfView!=null){pdfView.close();pdfView=null;}if(web!=null){WebView old=web;web=null;if(old.getParent() instanceof ViewGroup)((ViewGroup)old.getParent()).removeView(old);old.stopLoading();old.removeJavascriptInterface("ReaderHost");old.destroy();}}
    private Bitmap pageSnapshot(){Bitmap image=Bitmap.createBitmap(web.getWidth(),web.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(d.bg);web.draw(canvas);return image;}
    private void cancelPageTurn(){
        turnToken++;if(pageAnimator!=null){pageAnimator.cancel();pageAnimator=null;}
        if(preparingTurn&&web!=null)web.evaluateJavascript("if(window.Reader)Reader.preview(0)",null);
        if(pageAnimation!=null&&readingSurface!=null)readingSurface.removeView(pageAnimation);pageAnimation=null;preparingTurn=false;
        if(turnPrevious!=null&&!turnPrevious.isRecycled())turnPrevious.recycle();if(turnNext!=null&&turnNext!=turnPrevious&&!turnNext.isRecycled())turnNext.recycle();turnPrevious=null;turnNext=null;
    }
    private void turnPage(int direction){
        if(web==null||preparingTurn||restoring)return;
        String effect=prefs.getString("page_animation","none");
        if("none".equals(effect)){web.evaluateJavascript("if(window.Reader)Reader.turn("+direction+")",null);return;}
        final WebView owner=web;final int token=++turnToken;preparingTurn=true;
        web.evaluateJavascript("!!window.Reader&&Reader.canTurn("+direction+")",allowed->{
            if(token!=turnToken||owner!=web)return;
            if(!"true".equals(allowed)){preparingTurn=false;owner.evaluateJavascript("if(window.Reader)Reader.turn("+direction+")",null);return;}
            try{
                turnPrevious=pageSnapshot();pageAnimation=new PageAnimationView(this,turnPrevious,effect,direction>0,d.bg,d.light);readingSurface.addView(pageAnimation,new FrameLayout.LayoutParams(-1,-1));
                owner.evaluateJavascript("Reader.preview("+direction+")",value->{
                    if(token!=turnToken||owner!=web)return;
                    if(!"true".equals(value)){cancelPageTurn();return;}
                    owner.postVisualStateCallback(token,new WebView.VisualStateCallback(){@Override public void onComplete(long requestId){owner.postOnAnimation(()->{
                        if(token!=turnToken||owner!=web)return;
                        try{
                            turnNext=pageSnapshot();pageAnimation.setNext(turnNext);owner.evaluateJavascript("Reader.preview(0)",null);
                            pageAnimator=ValueAnimator.ofFloat(0,1);pageAnimator.setDuration("curl".equals(effect)?520:320);pageAnimator.setInterpolator(new DecelerateInterpolator(1.2f));
                            pageAnimator.addUpdateListener(a->{if(token==turnToken&&pageAnimation!=null)pageAnimation.setProgress((Float)a.getAnimatedValue());});
                            pageAnimator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){
                                if(token!=turnToken||owner!=web)return;owner.evaluateJavascript("Reader.turn("+direction+")",result->owner.postVisualStateCallback(token,new WebView.VisualStateCallback(){@Override public void onComplete(long requestId){owner.postOnAnimation(()->{if(token==turnToken)cancelPageTurn();});}}));
                            }});pageAnimator.start();
                        }catch(RuntimeException|OutOfMemoryError failure){cancelPageTurn();owner.evaluateJavascript("if(window.Reader)Reader.turn("+direction+")",null);}
                    });}});
                });
            }catch(RuntimeException|OutOfMemoryError failure){cancelPageTurn();owner.evaluateJavascript("if(window.Reader)Reader.turn("+direction+")",null);}
        });
    }
    private final class ReaderHost {
        private final WebView owner;private final String id;
        ReaderHost(WebView owner,String id){this.owner=owner;this.id=id;}
        @JavascriptInterface public void position(int current,double progress,String anchor,int page,int pages){
            if(current<0||book==null||current>=book.chapters.size()||Double.isNaN(progress)||Double.isInfinite(progress)||anchor==null||anchor.length()>160)return;
            handler.post(()->{if(owner!=web||!id.equals(bookId)||loading)return;chapter=current;fraction=(float)Math.max(0,Math.min(1,progress));readingAnchor=anchor;restoring=false;if(!readingStartedMarked){try{library.markReadingStarted(id);readingStartedMarked=true;}catch(Exception e){android.util.Log.e("Chitalka","Reading start save failed",e);}}updateReaderMeta(page,pages);handler.removeCallbacks(savePositionJob);handler.postDelayed(savePositionJob,400);});
        }
        @JavascriptInterface public void controls(){handler.post(()->{if(owner==web&&id.equals(bookId))toggleReaderControls();});}
        @JavascriptInterface public void edge(boolean beginning){handler.post(()->{if(owner==web)toast(beginning?"Это начало книги":"Это конец книги");});}
    }
    private void updateReaderMeta(int page,int pages){
        if(book==null||partLabel==null)return;Fb2.Chapter c=book.chapters.get(chapter);partLabel.setText(c.part.isEmpty()?"ЧТЕНИЕ":c.part.toUpperCase(new Locale("ru")));
        chapterCount.setText(("pages".equals(prefs.getString("reader_mode","scroll"))?"Стр. "+page+" · ":"")+(chapter+1)+" / "+book.chapters.size());
    }
    private boolean link(Uri uri){if("chitalka.invalid".equals(uri.getHost())&&uri.getPath()!=null&&uri.getPath().startsWith("/note/")){String id=uri.getLastPathSegment();String html=book.footnotes.get(id);if(html==null)toast("Примечание не найдено");else{TextView text=d.text("",18,d.text);text.setPadding(d.dp(22),d.dp(14),d.dp(22),d.dp(16));text.setText(android.text.Html.fromHtml(html,android.text.Html.FROM_HTML_MODE_LEGACY));text.setTextIsSelectable(true);ScrollView scroll=new ScrollView(this);scroll.addView(text);new AlertDialog.Builder(dialogContext()).setTitle("Примечание").setView(scroll).setPositiveButton("К чтению",null).show();}}else toast("Внешние ссылки пока не открываются");return true;}
    // Preserve authored hyphens while keeping compound words on one line.
    // Process text fragments only: HTML attributes, note URLs and image data stay intact.
    private static String keepCompoundWords(String html){
        java.util.regex.Pattern words=java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}]+(?:[-\\u2010][\\p{L}\\p{N}]+)+");
        java.util.regex.Matcher fragments=java.util.regex.Pattern.compile("<[^>]*>|[^<]+").matcher(html);
        StringBuilder result=new StringBuilder();
        while(fragments.find()){
            String fragment=fragments.group();
            if(fragment.startsWith("<")){result.append(fragment);continue;}
            java.util.regex.Matcher matches=words.matcher(fragment);int start=0;
            while(matches.find()){result.append(fragment,start,matches.start()).append("<span class='whole-word'>").append(matches.group()).append("</span>");start=matches.end();}
            result.append(fragment,start,fragment.length());
        }
        return result.toString();
    }
    private void renderChapter(){
        if(web==null||book==null)return;cancelPageTurn();restoring=true;updateReaderMeta(1,1);
        try{
            JSONObject config=new JSONObject().put("mode",prefs.getString("reader_mode","scroll")).put("chapter",chapter).put("fraction",fraction).put("anchor",readingAnchor);
            String style;try(InputStream in=getResources().openRawResource(R.raw.reader_css)){style=Library.read(in);}
            String script;try(InputStream in=getResources().openRawResource(R.raw.reader_js)){script=Library.read(in);}
            String colors=":root{--bg:"+(d.light?"#F4F5F7":"#17191C")+";--ink:"+(d.light?"#252B34":"#E5E8ED")+";--muted:"+(d.light?"#566277":"#AAB5C5")+";--font:"+prefs.getInt("font",20)+"px}";
            StringBuilder html=new StringBuilder("<!doctype html><html lang='ru'><head><meta name='viewport' content='width=device-width, initial-scale=1'><style>").append(style).append(colors).append("</style></head><body><main id='viewport'><div id='flow'>");
            for(int i=0;i<book.chapters.size();i++){Fb2.Chapter c=book.chapters.get(i);html.append("<section class='chapter' data-chapter='").append(i).append("'><h1>").append(Fb2.esc(c.title)).append("</h1>").append(keepCompoundWords(c.html)).append("</section>");}
            html.append("</div></main><script>window.readerConfig=").append(config).append(';').append(script).append("</script></body></html>");
            web.loadDataWithBaseURL("https://chitalka.invalid/",html.toString(),"text/html","UTF-8",null);
        }catch(Exception e){restoring=false;fail("Не удалось показать книгу",e);}
    }
    private void goChapter(int index){
        if(index<0||index>=book.chapters.size()){toast(index<0?"Это начало книги":"Это последняя глава");return;}
        if(restoring)return;savePosition();chapter=index;fraction=0;readingAnchor="{\"c\":"+index+",\"b\":0,\"o\":0}";savePosition();
        web.evaluateJavascript("if(window.Reader)Reader.jump("+index+")",null);
    }
    private void savePosition(){handler.removeCallbacks(savePositionJob);if(bookId!=null&&book!=null&&!loading&&!restoring&&!paper()&&!pdf()&&!archived()){try{library.position(bookId,chapter,fraction);prefs.edit().putString("anchor_"+bookId,readingAnchor).apply();}catch(Exception e){android.util.Log.e("Chitalka","Position save failed",e);}}}
    private void outline(){tab=4;showPane();}
    private void showBookStructure(){
        EditText search=d.edit("Найти главу",1);search.setSingleLine();add(pane,search,-1,48);d.gap(pane,8);
        ListView list=new ListView(this);list.setDividerHeight(0);pane.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        List<Integer> matches=new ArrayList<>();ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1){
            @Override public View getView(int position,View recycled,ViewGroup parent){TextView label=(TextView)super.getView(position,recycled,parent);label.setTextColor(matches.get(position)==chapter?d.silver:d.text);label.setTextSize(14);label.setPadding(d.dp(6),d.dp(12),d.dp(6),d.dp(12));return label;}
        };list.setAdapter(adapter);
        Runnable filter=()->{matches.clear();adapter.clear();String needle=search.getText().toString().toLowerCase(new Locale("ru"));for(int i=0;i<book.chapters.size();i++){Fb2.Chapter c=book.chapters.get(i);String label=(c.part.isEmpty()?"":c.part+"\n")+c.title;if(label.toLowerCase(new Locale("ru")).contains(needle)){matches.add(i);adapter.add(label);}}};filter.run();search.addTextChangedListener(watch(filter));
        list.setOnItemClickListener((parent,view,pos,id)->{int target=matches.get(pos);closePane();goChapter(target);});
        if(chapter<matches.size())list.setSelection(chapter);search.clearFocus();pane.setFocusableInTouchMode(true);pane.requestFocus();
    }
    private void settings(){
        String[] options={"Перелистывание страниц","Анимация перелистывания","Размер текста","Тема приложения","Цитаты","Экспорт конспекта · Markdown","О приложении"};
        new AlertDialog.Builder(dialogContext()).setTitle("Настройки").setItems(options,(dialog,which)->{
            if(which==0)readingModeDialog();else if(which==1)animationDialog();else if(which==2)fontDialog();else if(which==3)themeDialog();else if(which==4)chooseBookAction(false);else if(which==5)chooseBookAction(true);else new AlertDialog.Builder(dialogContext()).setTitle("Читалка · 0.1.9").setMessage("FB2, PDF и бумажные книги, карточки с обложками, конспекты, герои, цитаты, мысли и разделы. Книгу можно удалить отдельно от конспекта. Книги и записи хранятся на этом устройстве.").setPositiveButton("Понятно",null).show();
        }).show();
    }
    private void readingModeDialog(){
        String[] options={"Свайпы влево и вправо","Непрерывная прокрутка вверх и вниз"};int selected="pages".equals(prefs.getString("reader_mode","scroll"))?0:1;
        new AlertDialog.Builder(dialogContext()).setTitle("Перелистывание страниц").setSingleChoiceItems(options,selected,(dialog,which)->{savePosition();String mode=which==0?"pages":"scroll";prefs.edit().putString("reader_mode",mode).apply();if(web!=null)web.evaluateJavascript("if(window.Reader)Reader.setMode('"+mode+"')",null);dialog.dismiss();}).setNegativeButton("Отмена",null).show();
    }
    private void animationDialog(){
        boolean enabled=!"none".equals(prefs.getString("page_animation","none"));
        new AlertDialog.Builder(dialogContext()).setTitle("Анимация в режиме свайпов").setSingleChoiceItems(new String[]{"С анимацией","Без анимации"},enabled?0:1,(dialog,which)->{
            dialog.dismiss();if(which==1){prefs.edit().putString("page_animation","none").apply();return;}
            String current=prefs.getString("page_animation","fade");
            new AlertDialog.Builder(dialogContext()).setTitle("С анимацией").setSingleChoiceItems(new String[]{"Плавная смена страниц","Перелистывание за угол"},"curl".equals(current)?1:0,(choice,index)->{prefs.edit().putString("page_animation",index==0?"fade":"curl").apply();choice.dismiss();}).setNegativeButton("Отмена",null).show();
        }).setNegativeButton("Отмена",null).show();
    }
    private void themeDialog(){new AlertDialog.Builder(dialogContext()).setTitle("Тема приложения").setSingleChoiceItems(new String[]{"Светлая","Тёмная"},d.light?0:1,(dialog,which)->{dialog.dismiss();if((which==0)!=d.light)switchTheme();}).setNegativeButton("Отмена",null).show();}
    private void chooseBookAction(boolean exporting){
        List<Library.BookRow> books=library.books();books.addAll(library.savedNotebooks());if(books.isEmpty()){toast("Сначала добавьте книгу в библиотеку");return;}
        String[] names=new String[books.size()];for(int i=0;i<names.length;i++)names[i]=books.get(i).title+"\n"+books.get(i).author;
        new AlertDialog.Builder(dialogContext()).setTitle(exporting?"Конспект какой книги сохранить?":"Цитаты какой книги открыть?").setItems(names,(dialog,index)->{String id=books.get(index).id;if(exporting)exportBook(id);else openBook(id,()->{tab=2;showPane();});}).setNegativeButton("Отмена",null).show();
    }
    private void fontDialog(){LinearLayout box=d.column();box.setPadding(d.dp(24),d.dp(16),d.dp(24),d.dp(16));TextView label=d.text("",18,d.text);SeekBar slider=new SeekBar(this);slider.setMax(14);slider.setProgress(prefs.getInt("font",20)-16);label.setText("Размер текста: "+(slider.getProgress()+16));box.addView(label);add(box,slider,-1,60);slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){label.setText("Размер текста: "+(p+16));}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});new AlertDialog.Builder(dialogContext()).setTitle("Текст книги").setView(box).setPositiveButton("Применить",(a,b)->{prefs.edit().putInt("font",slider.getProgress()+16).apply();renderChapter();}).setNegativeButton("Отмена",null).show();}
    private void switchTheme(){flushNote();savePosition();hideKeyboard();boolean reading=bookId!=null;String id=bookId;prefs.edit().putBoolean("light",!d.light).apply();buildRoot();if(reading){bookId=id;showReader();}else showLibrary();}

    private void showPane(){
        if(book==null)return;if(readerActive)setReaderControls(false);if(archived()){if(paneOpen)showNoteList();else showSavedNotebook();return;}cancelPageTurn();if(paneOpen){showNoteList();return;}paneOpen=true;
        paneLayer=new FrameLayout(this);root.addView(paneLayer,new FrameLayout.LayoutParams(-1,-1));View shade=new View(this);shade.setBackgroundColor(0x88000000);shade.setOnClickListener(v->closePane());paneLayer.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        pane=d.column();pane.setPadding(d.dp(16),0,d.dp(16),d.dp(12));pane.setBackgroundColor(d.panel);pane.setElevation(d.dp(12));pane.setClickable(true);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(paneWidth(),-1,Gravity.END);paneLayer.addView(pane,lp);showNoteList();
    }
    private int paneWidth(){return Math.min(d.dp(560),(int)(getResources().getDisplayMetrics().widthPixels*.9f));}
    private void closePane(){if(archived()){showLibrary();return;}flushNote();hideKeyboard();if(paneLayer!=null)root.removeView(paneLayer);paneOpen=false;editing=null;if(readerActive)setReaderControls(false);}
    private LinearLayout paneHeader(String title,Runnable back){LinearLayout row=d.row();TextView label=d.title(title,23);weighted(row,label);add(row,d.icon(back==null?"close":"back",back==null?"Закрыть конспект":"Назад к записям",back==null?this::closePane:back),44,52);add(pane,row,-1,64);return row;}
    private void showNoteList(){
        flushNote();editing=null;if(pane==null)return;pane.removeAllViews();paneHeader("Конспект",null);TextView sub=d.text(book.title,12,d.muted);sub.setMaxLines(2);pane.addView(sub);d.gap(pane,16);
        String[] names={"Записи","Герои","Цитаты","Мои мысли","Структура"};int[][] rows={{4,0,1},{2,3}};
        for(int[] row:rows){LinearLayout tabs=d.row();for(int t:row){TextView b=d.text(names[t],13,tab==t?d.text:d.muted);b.setContentDescription(names[t]);b.setGravity(Gravity.CENTER);b.setPadding(d.dp(4),d.dp(8),d.dp(4),d.dp(8));if(tab==t)b.setBackground(d.surface(d.field));b.setOnClickListener(v->{tab=t;hideKeyboard();showNoteList();});tabs.addView(b,new LinearLayout.LayoutParams(0,d.dp(44),1));}pane.addView(tabs);d.gap(pane,4);}d.gap(pane,10);
        if(tab==4&&!paper()&&!pdf()&&!archived()){showBookStructure();return;}
        add(pane,d.button(tab==3?"+  Моя мысль":"+  Добавить",true,this::addNoteDialog),-1,46);d.gap(pane,10);
        if(tab==2&&!paper()&&!pdf()&&!archived()){add(pane,d.button("Цитата из выделенного текста",false,this::quote),-1,44);d.gap(pane,10);}
        ScrollView scroll=new ScrollView(this);pane.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout entries=d.column();scroll.addView(entries);List<Library.Note> all=library.orderedNotes(bookId);int visible=0;
        for(Library.Note n:all){if(tab==1&&!n.type.equals("hero")||tab==2&&!n.type.equals("quote")||tab==3&&!n.type.equals("thought")||(tab==0||tab==4)&&!(n.type.equals("part")||n.type.equals("chapter")||n.type.equals("section")))continue;visible++;
            LinearLayout card=d.column();card.setPadding(d.dp(12),d.dp(12),d.dp(12),d.dp(12));card.setBackground(d.surface(d.field));card.addView(d.text((n.structureType.isEmpty()?Library.typeLabel(n.type):n.structureType).toUpperCase(new Locale("ru")),9,d.silver));d.gap(card,6);card.addView(d.title(n.label(),16));if(!n.body.trim().isEmpty()){d.gap(card,6);TextView excerpt=d.text(n.body,13,d.muted);excerpt.setMaxLines(3);excerpt.setEllipsize(TextUtils.TruncateAt.END);card.addView(excerpt);}
            if(n.parent!=null){Library.Note parent=library.note(n.parent);if(parent!=null){d.gap(card,6);card.addView(d.text("В разделе: "+parent.label(),10,d.muted));}}
            card.setOnClickListener(v->openEditor(n));card.setOnLongClickListener(v->{deleteNote(n);return true;});LinearLayout.LayoutParams item=new LinearLayout.LayoutParams(-1,-2);if(tab==0||tab==4){String ancestor=n.parent;Set<String> visited=new HashSet<>();int depth=0;while(ancestor!=null&&visited.add(ancestor)){Library.Note parent=library.note(ancestor);if(parent==null)break;depth++;ancestor=parent.parent;}item.leftMargin=d.dp(Math.min(depth,5)*12);}entries.addView(card,item);d.gap(entries,10);
        }
        if(visible==0){d.gap(entries,20);TextView empty=d.text(tab==1?"Сохраните имя героя и собственное описание.":tab==2?"Добавляйте цитаты во время чтения.":tab==3?"Записывайте собственные мысли, вопросы и выводы.":"Добавляйте свои части и главы конспекта по мере чтения.",15,d.muted);empty.setLineSpacing(d.dp(4),1);entries.addView(empty);}
        d.gap(pane,8);pane.addView(d.text("Записи сохраняются автоматически",11,d.muted));
    }
    private void addNoteDialog(){if((paper()||pdf()||archived())&&(tab==0||tab==4)){createNote("section","");return;}if(tab==3){createNote("thought","");return;}String[] types=tab==1?new String[]{"hero"}:tab==2?new String[]{"quote"}:new String[]{"part","chapter","section"};String[] labels=new String[types.length];for(int i=0;i<types.length;i++)labels[i]=Library.typeLabel(types[i]);new AlertDialog.Builder(dialogContext()).setTitle("Добавить в конспект").setItems(labels,(a,index)->createNote(types[index],"")).show();}
    private void createNote(String type,String text){try{Library.Note n=library.add(bookId,type,archived()?-1:type.equals("hero")||type.equals("part")||type.equals("section")?-1:(paper()||pdf())?library.book(bookId).page:chapter,text);tab=type.equals("hero")?1:type.equals("quote")?2:type.equals("thought")?3:0;if(!paneOpen)showPane();openEditor(n);}catch(Exception e){fail("Не удалось создать запись",e);}}
    private void openEditor(Library.Note n){
        if(n==null)return;flushNote();editing=n;dirty=false;pane.removeAllViews();paneHeader(Library.typeLabel(n.type),this::showNoteList);
        saveStatus=d.text("Сохранено на устройстве",11,d.muted);pane.addView(saveStatus);d.gap(pane,12);
        structureType=null;if(n.type.equals("section")||n.type.equals("part")||n.type.equals("chapter")){pane.addView(d.text("Структурный тип",12,d.muted));structureType=d.edit("Часть, раздел, глава или свой тип",1);structureType.setId(R.id.structure_type);structureType.setSingleLine();structureType.setText(n.structureType.isEmpty()?Library.typeLabel(n.type):n.structureType);add(pane,structureType,-1,52);d.gap(pane,8);}
        noteTitle=d.edit(n.type.equals("hero")?"Имя героя":n.type.equals("section")?"Наименование, включая нумерацию":"Название",1);noteTitle.setId(R.id.note_title);noteTitle.setMaxLines(2);noteTitle.setText(n.title);add(pane,noteTitle,-1,58);d.gap(pane,10);
        if(!n.type.equals("hero")){Library.Note parent=n.parent==null?null:library.note(n.parent);TextView hierarchy=d.button(parent==null?"Раздел: общие записи":"Раздел: "+parent.label(),false,this::chooseParent);hierarchy.setMaxLines(1);hierarchy.setEllipsize(TextUtils.TruncateAt.END);add(pane,hierarchy,-1,42);d.gap(pane,8);}
        LinearLayout sourceRow=d.row();boolean linked=!paper()&&n.source>=0&&n.source<book.chapters.size();TextView source=d.text(linked?"Источник: "+book.chapters.get(n.source).title:(paper()||pdf())&&n.source>0?"Источник: страница "+n.source:"Источник: вся книга",11,d.silver);source.setMaxLines(2);source.setEllipsize(TextUtils.TruncateAt.END);if(linked&&!archived())source.setOnClickListener(v->{int target=editing.source;closePane();goChapter(target);});if(pdf()&&!archived()&&n.source>0)source.setOnClickListener(v->{int page=n.source;closePane();if(pdfView!=null)pdfView.showPage(page);});weighted(sourceRow,source);add(sourceRow,d.icon("trash","Удалить запись",()->deleteNote(editing)),44,44);add(pane,sourceRow,-1,48);d.gap(pane,8);
        noteBody=d.edit(n.type.equals("hero")?"Что вы знаете об этом герое?":n.type.equals("quote")?"Цитата из книги":n.type.equals("section")?"Описание раздела":"Содержание, выводы, вопросы…",3);noteBody.setId(R.id.note_body);noteBody.setText(n.body);noteBody.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);pane.addView(noteBody,new LinearLayout.LayoutParams(-1,0,1));d.gap(pane,10);
        if(structureType!=null)structureType.addTextChangedListener(watch(this::noteChanged));
        noteTitle.addTextChangedListener(watch(this::noteChanged));noteBody.addTextChangedListener(watch(this::noteChanged));
        LinearLayout actions=d.row();TextView save=d.button("Готово",true,()->{flushNote();hideKeyboard();showNoteList();});actions.addView(save,new LinearLayout.LayoutParams(-1,d.dp(46)));pane.addView(actions);noteTitle.clearFocus();noteBody.clearFocus();pane.setFocusableInTouchMode(true);pane.requestFocus();
    }
    private void noteChanged(){if(editing==null)return;if(structureType!=null)editing.structureType=structureType.getText().toString().trim();editing.title=noteTitle.getText().toString();editing.body=noteBody.getText().toString();dirty=true;if(saveStatus!=null)saveStatus.setText("Сохраняется…");handler.removeCallbacks(saveNoteJob);handler.postDelayed(saveNoteJob,350);}
    private void flushNote(){handler.removeCallbacks(saveNoteJob);if(editing==null||!dirty)return;try{library.save(editing);dirty=false;if(saveStatus!=null)saveStatus.setText("Сохранено на устройстве");}catch(Exception e){if(saveStatus!=null)saveStatus.setText("Не удалось сохранить");toast("Не удалось сохранить запись. Оставьте её открытой и повторите попытку.");android.util.Log.e("Chitalka","Note save failed",e);}}
    private void chooseParent(){flushNote();List<Library.Note> candidates=new ArrayList<>();List<String> labels=new ArrayList<>();labels.add("+ Добавить пользовательский раздел");labels.add("Общие записи");List<Library.Note> all=library.notes(bookId);
        for(Library.Note n:all){if(n.id.equals(editing.id)||!(n.type.equals("part")||n.type.equals("chapter")||n.type.equals("section")))continue;String ancestor=n.parent;boolean cycle=false;Set<String> seen=new HashSet<>();while(ancestor!=null&&seen.add(ancestor)){if(ancestor.equals(editing.id)){cycle=true;break;}Library.Note p=library.note(ancestor);ancestor=p==null?null:p.parent;}if(!cycle){candidates.add(n);labels.add(n.label());}}
        new AlertDialog.Builder(dialogContext()).setTitle("Поместить запись в раздел").setItems(labels.toArray(new String[0]),(a,index)->{if(index==0){newSectionDialog();return;}editing.parent=index==1?null:candidates.get(index-2).id;dirty=true;flushNote();Library.Note current=editing;openEditor(current);}).show();
    }
    private void newSectionDialog(){
        if(editing==null)return;final Library.Note target=editing;EditText name=d.edit("Например: мысли о работе",1);name.setId(R.id.section_name);name.setSingleLine(true);LinearLayout box=d.column();box.setPadding(d.dp(20),d.dp(12),d.dp(20),d.dp(8));add(box,name,-1,56);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle("Добавить пользовательский раздел").setView(box).setPositiveButton("Создать",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(v->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{String title=name.getText().toString().trim();if(title.isEmpty()){name.setError("Введите название раздела");return;}try{Library.Note section=library.addSection(bookId,title);target.parent=section.id;editing=target;dirty=true;flushNote();dialog.dismiss();hideKeyboard();openEditor(target);}catch(Exception e){fail("Не удалось создать раздел",e);}});name.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);});dialog.show();
    }
    private void deleteNote(Library.Note n){if(n==null)return;new AlertDialog.Builder(dialogContext()).setTitle("Удалить запись?").setMessage("«"+n.label()+"». Вложенные записи останутся в общих записях.").setPositiveButton("Удалить",(a,b)->{if(editing!=null&&editing.id.equals(n.id)){handler.removeCallbacks(saveNoteJob);dirty=false;editing=null;}library.delete(n);showNoteList();}).setNegativeButton("Отмена",null).show();}
    private void quote(){if(paper()||pdf()){createNote("quote","");return;}web.evaluateJavascript("window.getSelection().toString()",value->{try{Object selected=new JSONTokener(value).nextValue();String text=selected instanceof String?(String)selected:"";if(text.trim().isEmpty()){toast("Сначала выделите фрагмент текста долгим нажатием, затем нажмите «Цитата».");return;}createNote("quote",text);}catch(Exception e){toast("Не удалось получить выделенный текст");}});}
    private void exportBook(String id){
        worker.execute(()->{try{Fb2.Book selected=library.load(id);String text=library.markdown(id,selected);handler.post(()->{if(isFinishing()||isDestroyed())return;exportText=text;prefs.edit().putString("pending_export",exportText).apply();Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/markdown").addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_TITLE,selected.title.replaceAll("[\\\\/:*?\"<>|]","_")+" — конспект.md");startActivityForResult(intent,EXPORT);});}catch(Exception e){handler.post(()->fail("Не удалось подготовить конспект",e));}});
    }
    private TextWatcher watch(Runnable change){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){change.run();}public void afterTextChanged(Editable e){}};}
    @Override protected void onPause(){cancelPageTurn();flushNote();if(detailsForm!=null)detailsForm.save();savePosition();super.onPause();}
    @Override protected void onDestroy(){cancelPageTurn();releaseReader();worker.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){flushNote();if(detailsForm!=null){detailsForm.save();state.putString("details",detailsForm.book.id);}savePosition();state.putString("cover_book",coverBookId);state.putString("book",bookId!=null?bookId:pendingBookId);state.putBoolean("pane",paneOpen);state.putInt("tab",tab);if(editing!=null)state.putString("editing",editing.id);super.onSaveInstanceState(state);}
    @Override public void onConfigurationChanged(Configuration config){cancelPageTurn();super.onConfigurationChanged(config);if(pane!=null&&paneOpen&&!archived()){FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)pane.getLayoutParams();lp.width=paneWidth();pane.setLayoutParams(lp);}}
    @Override public void onBackPressed(){if(paneOpen){closePane();return;}if(readerActive&&readerControlsVisible){setReaderControls(false);return;}if(bookId!=null&&!loading){if(archived())showLibrary();else showBookDetails(bookId);return;}if(pendingBookId!=null){showBookDetails(pendingBookId);return;}if(detailsForm!=null){showLibrary();return;}if(loading){showLibrary();return;}super.onBackPressed();}
}
