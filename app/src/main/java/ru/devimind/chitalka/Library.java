package ru.devimind.chitalka;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.provider.OpenableColumns;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.text.Collator;
import java.time.LocalDate;

/** Local SQLite notes, positions and book metadata. Original documents are copied, never edited. */
public final class Library extends SQLiteOpenHelper {
    public static final class BookRow {
        public String id,title,author,country,genre,started,finished; public String kind; public boolean archived; public int page,total; public boolean paper(){return "paper".equals(kind);} public boolean pdf(){return "pdf".equals(kind);} public int chapter; public float scroll; public long opened;
        BookRow(Cursor c){id=c.getString(0);title=c.getString(1);author=c.getString(2);chapter=c.getInt(3);scroll=c.getFloat(4);country=c.getString(5);genre=c.getString(6);started=c.getString(7);finished=c.getString(8);opened=c.getLong(9);kind=c.getString(10);page=c.getInt(11);total=c.getInt(12);archived=c.getInt(13)!=0;}
        public String label(){return title.trim().isEmpty()?"Без названия":title;}
    }
    public static final class Note {
        public String id,book,type,title,body,parent,structureType; public int source;
        Note(Cursor c){id=c.getString(0);book=c.getString(1);type=c.getString(2);title=c.getString(3);body=c.getString(4);parent=c.getString(5);source=c.getInt(6);structureType=c.getString(7);}
        public String label(){return structureType.isEmpty()?(title.trim().isEmpty()?typeLabel(type):title):(structureType+" "+title).trim();}
    }
    public static final class HeroRelation {
        public String id,book,heroA,heroB,description;
        HeroRelation(Cursor c){id=c.getString(0);book=c.getString(1);heroA=c.getString(2);heroB=c.getString(3);description=c.getString(4);}
        public String other(String hero){if(heroA.equals(hero))return heroB;if(heroB.equals(hero))return heroA;throw new IllegalArgumentException("Герой не участвует в связи");}
    }
    private final Context context;
    private final File folder;
    public Library(Context c){super(c.getApplicationContext(),"library.db",null,5);context=c.getApplicationContext();folder=new File(context.getFilesDir(),"books");folder.mkdirs();}
    @Override public void onConfigure(SQLiteDatabase db){db.setForeignKeyConstraintsEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,chapter INTEGER NOT NULL DEFAULT 0,scroll REAL NOT NULL DEFAULT 0,opened INTEGER NOT NULL,country TEXT NOT NULL DEFAULT '',genre TEXT NOT NULL DEFAULT '',started TEXT NOT NULL DEFAULT '',finished TEXT NOT NULL DEFAULT '',kind TEXT NOT NULL DEFAULT 'fb2',page INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,removed INTEGER NOT NULL DEFAULT 0,source_outline TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,type TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,parent_id TEXT REFERENCES notes(id) ON DELETE SET NULL,source INTEGER NOT NULL DEFAULT -1,position INTEGER NOT NULL,updated INTEGER NOT NULL,structure_type TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX notes_book ON notes(book_id,position)");
        createHeroRelations(db);
    }
    private static void createHeroRelations(SQLiteDatabase db){
        db.execSQL("CREATE TABLE hero_relations(id TEXT PRIMARY KEY,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,hero_a TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,hero_b TEXT NOT NULL REFERENCES notes(id) ON DELETE CASCADE,description TEXT NOT NULL DEFAULT '',updated INTEGER NOT NULL,CHECK(hero_a < hero_b),UNIQUE(book_id,hero_a,hero_b))");
        db.execSQL("CREATE INDEX hero_relations_b ON hero_relations(hero_b)");
        db.execSQL("CREATE INDEX hero_relations_a ON hero_relations(hero_a)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV){
        if(oldV<2&&newV>=2)for(String column:new String[]{"country","genre","started","finished"})db.execSQL("ALTER TABLE books ADD COLUMN "+column+" TEXT NOT NULL DEFAULT ''");
        if(oldV<3&&newV>=3){db.execSQL("ALTER TABLE books ADD COLUMN kind TEXT NOT NULL DEFAULT 'fb2'");db.execSQL("ALTER TABLE books ADD COLUMN page INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE books ADD COLUMN total INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE notes ADD COLUMN structure_type TEXT NOT NULL DEFAULT ''");}
        if(oldV<4&&newV>=4){db.execSQL("ALTER TABLE books ADD COLUMN removed INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE books ADD COLUMN source_outline TEXT NOT NULL DEFAULT ''");}
        if(oldV<5&&newV>=5)createHeroRelations(db);
        if(oldV>=1&&newV<=5)return;
        throw new IllegalStateException("Unsupported database upgrade; data retained");
    }
    private static final String BOOK_COLUMNS="id,title,author,chapter,scroll,country,genre,started,finished,opened,kind,page,total,removed";
    public List<BookRow> books(){return books("opened",true);}
    public List<BookRow> books(String field,boolean descending){
        List<BookRow> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT "+BOOK_COLUMNS+" FROM books WHERE removed=0",null)){while(c.moveToNext())rows.add(new BookRow(c));}
        Collator collator=Collator.getInstance(new Locale("ru"));collator.setStrength(Collator.SECONDARY);
        rows.sort((a,b)->{int order;if("opened".equals(field))order=Long.compare(a.opened,b.opened);else{String x=sortValue(a,field),y=sortValue(b,field);if(x.isEmpty()!=y.isEmpty())return x.isEmpty()?1:-1;order=collator.compare(x,y);}if(order!=0)return descending?-order:order;order=collator.compare(a.label(),b.label());return order!=0?order:a.id.compareTo(b.id);});return rows;
    }
    private static String sortValue(BookRow row,String field){switch(field){case "author":return row.author;case "country":return row.country;case "genre":return row.genre;case "started":return row.started;case "finished":return row.finished;default:return row.title;}}
    public List<BookRow> savedNotebooks(){List<BookRow> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT "+BOOK_COLUMNS+" FROM books WHERE removed=1 ORDER BY opened DESC",null)){while(c.moveToNext())rows.add(new BookRow(c));}return rows;}
    public BookRow book(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT "+BOOK_COLUMNS+" FROM books WHERE id=?",new String[]{id})){return c.moveToFirst()?new BookRow(c):null;}}
    public void saveBook(BookRow book){
        if(!book.started.isEmpty())LocalDate.parse(book.started);if(!book.finished.isEmpty())LocalDate.parse(book.finished);
        if(!book.started.isEmpty()&&!book.finished.isEmpty()&&book.finished.compareTo(book.started)<0)throw new IllegalArgumentException("Дата завершения раньше даты начала");
        ContentValues values=new ContentValues();values.put("title",book.title.trim());values.put("author",book.author.trim());values.put("country",book.country.trim());values.put("genre",book.genre.trim());values.put("started",book.started);values.put("finished",book.finished);
        getWritableDatabase().update("books",values,"id=?",new String[]{book.id});
    }
    public String addPaper(){String id="paper-"+UUID.randomUUID();ContentValues values=new ContentValues();values.put("id",id);values.put("title","Новая бумажная книга");values.put("author","");values.put("kind","paper");values.put("opened",System.currentTimeMillis());getWritableDatabase().insertOrThrow("books",null,values);return id;}
    public void paperProgress(String id,int page,int total){if(page<0||total<0||(total>0&&page>total))throw new IllegalArgumentException("Проверьте номер страницы и общее число страниц");ContentValues values=new ContentValues();values.put("page",page);values.put("total",total);values.put("opened",System.currentTimeMillis());getWritableDatabase().update("books",values,"id=? AND kind='paper'",new String[]{id});}
    public void pdfPosition(String id,int page,int total){if(page<1||total<1||page>total)throw new IllegalArgumentException("Неверная страница PDF");ContentValues values=new ContentValues();values.put("page",page);values.put("total",total);values.put("opened",System.currentTimeMillis());getWritableDatabase().update("books",values,"id=? AND kind='pdf'",new String[]{id});}
    public void markReadingStarted(String id){ContentValues values=new ContentValues();values.put("started",LocalDate.now().toString());getWritableDatabase().update("books",values,"id=? AND started='' AND finished=''",new String[]{id});}
    public void position(String id,int chapter,float scroll){ContentValues v=new ContentValues();v.put("chapter",chapter);v.put("scroll",scroll);v.put("opened",System.currentTimeMillis());getWritableDatabase().update("books",v,"id=?",new String[]{id});}
    public File file(String id,String extension){return new File(folder,id+extension);}
    public android.graphics.Bitmap cover(String id,int edge)throws Exception{return BookCovers.load(this,id,Math.max(1,edge));}
    public void replaceCover(String id,Uri image)throws Exception{if(book(id)==null)throw new IOException("Книга не найдена");BookCovers.replace(context,file(id,".cover"),image);}
    public synchronized String importBook(Uri uri) throws Exception {
        File temporary=File.createTempFile("import-",".fb2",folder);
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(temporary)){
                if(in==null)throw new IOException("Не удалось открыть файл");byte[] buffer=new byte[65536];int count;
                while((count=in.read(buffer))!=-1){total+=count;if(total>256L*1024*1024)throw new IOException("Выберите файл размером до 256 МБ.");digest.update(buffer,0,count);out.write(buffer,0,count);}
            }
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));String id=hex.toString();
            BookRow existing=book(id);if(existing!=null&&!existing.archived)return id;
            byte[] signature=new byte[1024];int length;try(InputStream input=new FileInputStream(temporary)){length=input.read(signature);}
            boolean pdf=length>0&&new String(signature,0,length,java.nio.charset.StandardCharsets.ISO_8859_1).contains("%PDF-");
            if(pdf){int pages=PdfBook.count(temporary);String name=uri.getLastPathSegment();try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}catch(Exception ignored){if(uri.getLastPathSegment()!=null)name=uri.getLastPathSegment();}if(name==null||name.trim().isEmpty())name="PDF-книга";name=name.replaceFirst("(?i)\\.pdf$","");File target=file(id,".pdf");if(!temporary.renameTo(target))throw new IOException("Не удалось сохранить PDF");try{ContentValues values=new ContentValues();values.put("id",id);values.put("title",name);values.put("author","");values.put("kind","pdf");values.put("page",1);values.put("total",pages);values.put("opened",System.currentTimeMillis());if(existing==null)getWritableDatabase().insertOrThrow("books",null,values);else{ContentValues restored=new ContentValues();restored.put("removed",0);restored.put("source_outline","");restored.put("total",pages);getWritableDatabase().update("books",restored,"id=?",new String[]{id});}return id;}catch(Exception error){target.delete();throw error;}}
            if(total>64L*1024*1024)throw new IOException("Выберите FB2 размером до 64 МБ.");
            Fb2.Book parsed;try(InputStream in=new FileInputStream(temporary)){parsed=new Fb2().parse(in);}
            String json=parsed.json().toString();
            android.util.AtomicFile cached=new android.util.AtomicFile(file(id,".json"));FileOutputStream output=cached.startWrite();
            try{output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));cached.finishWrite(output);}catch(Exception e){cached.failWrite(output);throw e;}
            if(!temporary.renameTo(file(id,".fb2")))throw new IOException("Не удалось сохранить локальную копию книги");
            ContentValues v=new ContentValues();v.put("id",id);v.put("title",parsed.title);v.put("author",parsed.author);v.put("opened",System.currentTimeMillis());
            if(existing==null)getWritableDatabase().insertOrThrow("books",null,v);else{ContentValues restored=new ContentValues();restored.put("removed",0);restored.put("source_outline","");getWritableDatabase().update("books",restored,"id=?",new String[]{id});}return id;
        }finally{temporary.delete();}
    }
    public Fb2.Book load(String id)throws Exception{
        BookRow row=book(id);if(row!=null&&row.archived){Fb2.Book saved=new Fb2.Book();saved.title=row.label();saved.author=row.author;try(Cursor c=getReadableDatabase().rawQuery("SELECT source_outline FROM books WHERE id=?",new String[]{id})){if(c.moveToFirst()&&!c.getString(0).isEmpty()){JSONArray outline=new JSONArray(c.getString(0));for(int i=0;i<outline.length();i++){JSONObject item=outline.getJSONObject(i);saved.chapters.add(new Fb2.Chapter(item.optString("title"),item.optString("part"),""));}}}return saved;}if(row!=null&&(row.paper()||row.pdf())){Fb2.Book paper=new Fb2.Book();paper.title=row.label();paper.author=row.author;return paper;}
        File cached=file(id,".json");
        Fb2.Book loaded;
        if(cached.isFile()){try(InputStream in=new FileInputStream(cached)){loaded=Fb2.Book.fromJson(read(in));}}
        else try(InputStream in=new FileInputStream(file(id,".fb2"))){loaded=new Fb2().parse(in);}
        BookRow saved=book(id);if(saved!=null){loaded.title=saved.label();loaded.author=saved.author;}return loaded;
    }
    /** Keep only notebook metadata and source labels; the imported document is removed. */
    public synchronized void deleteBook(String id,boolean withNotebook)throws Exception{
        BookRow row=book(id);if(row==null)return;
        String outline="";
        if(!withNotebook&&!row.archived&&!row.paper()&&!row.pdf()){
            try{Fb2.Book original=load(id);JSONArray titles=new JSONArray();for(Fb2.Chapter c:original.chapters)titles.put(new JSONObject().put("title",c.title).put("part",c.part));outline=titles.toString();}catch(Exception unreadable){android.util.Log.w("Chitalka","Source labels unavailable; retaining notebook",unreadable);}
        }
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
            if(withNotebook)db.delete("books","id=?",new String[]{id});
            else if(!row.archived){ContentValues values=new ContentValues();values.put("removed",1);values.put("source_outline",outline);db.update("books",values,"id=?",new String[]{id});}
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        for(String extension:new String[]{".fb2",".pdf",".json",".json.bak",".json.new",".cover",".cover.bak",".cover.new"}){File local=file(id,extension);if(local.exists()&&!local.delete())throw new IOException("Запись удалена из библиотеки, но не удалось удалить локальный файл. Повторите удаление.");}
    }
    public static String read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}
    public List<Note> notes(String book){List<Note> r=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT id,book_id,type,title,body,parent_id,source,structure_type FROM notes WHERE book_id=? ORDER BY position",new String[]{book})){while(c.moveToNext())r.add(new Note(c));}return r;}
    public Note note(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT id,book_id,type,title,body,parent_id,source,structure_type FROM notes WHERE id=?",new String[]{id})){return c.moveToFirst()?new Note(c):null;}}
    public List<Note> orderedNotes(String book){List<Note> all=notes(book),ordered=new ArrayList<>();Set<String> seen=new HashSet<>();for(Note n:all)if(n.parent==null)orderNote(n,all,ordered,seen);for(Note n:all)orderNote(n,all,ordered,seen);return ordered;}
    private void orderNote(Note n,List<Note> all,List<Note> ordered,Set<String> seen){if(!seen.add(n.id))return;ordered.add(n);for(Note child:all)if(n.id.equals(child.parent))orderNote(child,all,ordered,seen);}
    public Note add(String book,String type,int source,String text){
        String id=UUID.randomUUID().toString();ContentValues v=new ContentValues();v.put("id",id);v.put("book_id",book);v.put("type",type);v.put("title","");v.put("body",text);v.put("source",source);v.put("updated",System.currentTimeMillis());
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(MAX(position),0)+1 FROM notes WHERE book_id=?",new String[]{book})){c.moveToFirst();v.put("position",c.getInt(0));}
        getWritableDatabase().insertOrThrow("notes",null,v);return note(id);
    }
    public Note addSection(String book,String title){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{Note n=add(book,"section",-1,"");n.title=title;save(n);db.setTransactionSuccessful();return n;}finally{db.endTransaction();}
    }
    public void save(Note n){ContentValues v=new ContentValues();v.put("structure_type",n.structureType==null?"":n.structureType.trim());v.put("title",n.title);v.put("body",n.body);v.put("parent_id",n.parent);v.put("source",n.source);v.put("updated",System.currentTimeMillis());getWritableDatabase().update("notes",v,"id=?",new String[]{n.id});}
    public void delete(Note n){getWritableDatabase().delete("notes","id=?",new String[]{n.id});}
    private static final String RELATION_COLUMNS="id,book_id,hero_a,hero_b,description";
    public List<HeroRelation> heroRelations(String hero){List<HeroRelation> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT "+RELATION_COLUMNS+" FROM hero_relations WHERE hero_a=? OR hero_b=? ORDER BY updated DESC,id",new String[]{hero,hero})){while(c.moveToNext())rows.add(new HeroRelation(c));}return rows;}
    public List<HeroRelation> bookHeroRelations(String book){List<HeroRelation> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT "+RELATION_COLUMNS+" FROM hero_relations WHERE book_id=? ORDER BY updated DESC,id",new String[]{book})){while(c.moveToNext())rows.add(new HeroRelation(c));}return rows;}
    public HeroRelation heroRelation(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT "+RELATION_COLUMNS+" FROM hero_relations WHERE id=?",new String[]{id})){return c.moveToFirst()?new HeroRelation(c):null;}}
    public HeroRelation heroRelationBetween(String first,String second){String a=first.compareTo(second)<0?first:second,b=first.compareTo(second)<0?second:first;try(Cursor c=getReadableDatabase().rawQuery("SELECT "+RELATION_COLUMNS+" FROM hero_relations WHERE hero_a=? AND hero_b=?",new String[]{a,b})){return c.moveToFirst()?new HeroRelation(c):null;}}
    private Note[] relationHeroes(String first,String second){Note a=note(first),b=note(second);if(a==null||b==null||!"hero".equals(a.type)||!"hero".equals(b.type))throw new IllegalArgumentException("Выберите двух существующих героев");if(a.id.equals(b.id))throw new IllegalArgumentException("Нельзя связать героя с самим собой");if(!a.book.equals(b.book))throw new IllegalArgumentException("Связь доступна между героями одной книги");return new Note[]{a,b};}
    public HeroRelation connectHeroes(String first,String second,String description){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{Note[] heroes=relationHeroes(first,second);if(heroRelationBetween(first,second)!=null)throw new IllegalArgumentException("Связь между этими героями уже есть");String a=first.compareTo(second)<0?first:second,b=first.compareTo(second)<0?second:first;String id=UUID.randomUUID().toString();ContentValues values=new ContentValues();values.put("id",id);values.put("book_id",heroes[0].book);values.put("hero_a",a);values.put("hero_b",b);values.put("description",description==null?"":description.trim());values.put("updated",System.currentTimeMillis());db.insertOrThrow("hero_relations",null,values);HeroRelation result=heroRelation(id);db.setTransactionSuccessful();return result;}finally{db.endTransaction();}
    }
    public void updateHeroRelation(String id,String description){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{HeroRelation relation=heroRelation(id);if(relation==null)throw new IllegalArgumentException("Связь уже удалена");relationHeroes(relation.heroA,relation.heroB);ContentValues values=new ContentValues();values.put("description",description==null?"":description.trim());values.put("updated",System.currentTimeMillis());db.update("hero_relations",values,"id=?",new String[]{id});db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public void deleteHeroRelation(String id){getWritableDatabase().delete("hero_relations","id=?",new String[]{id});}
    public static String typeLabel(String type){switch(type){case "part":return "Часть";case "chapter":return "Глава / эпизод";case "section":return "Раздел";case "hero":return "Герой";case "quote":return "Цитата";default:return "Моя мысль";}}
    public String markdown(String id,Fb2.Book book){
        StringBuilder s=new StringBuilder("# ").append(book.title).append("\n\n").append(book.author).append("\n\n");List<Note> all=notes(id);
        s.append("## Главные герои\n\n");for(Note n:all)if(n.type.equals("hero"))append(s,n,3,book);
        List<HeroRelation> relations=bookHeroRelations(id);if(!relations.isEmpty()){s.append("## Связи героев\n\n");for(HeroRelation relation:relations){Note a=note(relation.heroA),b=note(relation.heroB);if(a==null||b==null)continue;s.append("### ").append(a.label().replace('\n',' ')).append(" ↔ ").append(b.label().replace('\n',' ')).append("\n\n").append(relation.description).append("\n\n");}}
        s.append("## Конспект\n\n");Set<String> emitted=new HashSet<>();
        for(Note n:all)if(!n.type.equals("hero")&&n.parent==null)tree(s,n,all,3,book,emitted);
        for(Note n:all)if(!n.type.equals("hero")&&!emitted.contains(n.id))tree(s,n,all,3,book,emitted);
        return s.toString();
    }
    private void tree(StringBuilder s,Note n,List<Note> all,int level,Fb2.Book book,Set<String> emitted){if(!emitted.add(n.id))return;append(s,n,level,book);for(Note child:all)if(n.id.equals(child.parent))tree(s,child,all,Math.min(6,level+1),book,emitted);}
    private void append(StringBuilder s,Note n,int level,Fb2.Book book){
        for(int i=0;i<level;i++)s.append('#');s.append(' ').append(n.label().replace('\n',' ')).append("\n\n");
        if(n.type.equals("quote")){for(String line:n.body.split("\n",-1))s.append("> ").append(line).append('\n');}else s.append(n.body).append('\n');
        BookRow row=book(n.book);if(row!=null&&(row.paper()||row.pdf())&&n.source>0)s.append("\n_Источник: страница ").append(n.source).append("_\n");
        else if(n.source>=0&&n.source<book.chapters.size())s.append("\n_Источник: ").append(book.chapters.get(n.source).part).append(" · ").append(book.chapters.get(n.source).title).append("_\n");
        s.append('\n');
    }
}
