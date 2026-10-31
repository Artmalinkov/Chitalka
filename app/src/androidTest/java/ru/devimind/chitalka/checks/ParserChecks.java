package ru.devimind.chitalka.checks;

import android.app.*;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import ru.devimind.chitalka.Fb2;
import ru.devimind.chitalka.Library;

/** Separate test APK. No book fixture or test runner is bundled in the app APK. */
public final class ParserChecks extends Instrumentation {
    int checks; boolean cleanup; Bundle arguments;
    @Override public void onCreate(Bundle args){super.onCreate(args);arguments=args;cleanup=args!=null&&"true".equals(args.getString("cleanup"));start();}
    void expect(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    Fb2.Book parse(String text)throws Exception{return new Fb2().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(arguments!=null&&arguments.containsKey("restore_book")){
            String id=arguments.getString("restore_book");
            int chapter=Integer.parseInt(arguments.getString("restore_chapter"));
            float scroll=Float.parseFloat(arguments.getString("restore_scroll"));
            Library library=new Library(getTargetContext());
            if(library.book(id)==null||chapter<0||scroll<0||scroll>1)throw new IllegalArgumentException("Invalid reading position");
            library.position(id,chapter,scroll);library.close();
            result.putString("result","PASS: saved reading position restored");finish(Activity.RESULT_OK,result);return;
        }
        String prefix="<FictionBook xmlns='http://www.gribuser.ru/xml/fictionbook/2.0' xmlns:l='http://www.w3.org/1999/xlink'><description><title-info><book-title>Тест &amp; текст</book-title></title-info></description>";
        Fb2.Book tiny=parse(prefix+"<body><section><title><p>Часть I</p></title><section><title><p>I. Глава</p></title><p>Текст <strong>важный</strong>, <emphasis>курсив</emphasis> и &lt;script&gt;.</p><p><a l:href='#n1'>1</a><image l:href='https://invalid.example/image.png'/></p></section></section></body><body name='notes'><section id='n1'><p>Пояснение</p></section></body></FictionBook>");
        expect(tiny.title.equals("Тест & текст"),"Unicode and entities");
        expect(tiny.chapters.size()==1,"Notes must not enter chapter list");
        expect(tiny.chapters.get(0).part.equals("Часть I"),"Part association");
        expect(tiny.chapters.get(0).html.contains("<strong>важный</strong>"),"Bold formatting");
        expect(tiny.chapters.get(0).html.contains("<em>курсив</em>"),"Italic formatting");
        expect(tiny.chapters.get(0).html.contains("&lt;script&gt;")&&!tiny.chapters.get(0).html.contains("<script>"),"Source text must remain escaped");
        expect(!tiny.chapters.get(0).html.contains("invalid.example"),"Remote images must not be loaded");
        expect(tiny.footnotes.get("n1").contains("Пояснение"),"Footnote content");
        boolean rejected=false;try{parse("<!DOCTYPE FictionBook [<!ENTITY external SYSTEM 'file:///proc/version'>]><FictionBook><body><p>&external;</p></body></FictionBook>");}catch(Exception expected){rejected=true;}
        expect(rejected,"DTD and external entities must be rejected");
        rejected=false;try{parse("<html><body>Не FB2</body></html>");}catch(Exception expected){rejected=true;}expect(rejected,"Wrong format must be rejected");
        File[] files=new File(getTargetContext().getFilesDir(),"books").listFiles((dir,name)->name.endsWith(".fb2"));
        expect(files!=null&&files.length>0,"Real imported book must exist");
        Fb2.Book real;try(InputStream in=new FileInputStream(files[0])){real=new Fb2().parse(in);}
        expect(real.chapters.size()==117,"Real FB2: 117 chapters");
        expect(real.footnotes.size()==72,"Real FB2: 72 footnotes");
        Set<String> parts=new HashSet<>();for(Fb2.Chapter c:real.chapters)parts.add(c.part);expect(parts.size()==6,"Real FB2: six parts");
        expect(!real.cover.isEmpty(),"Embedded cover");
        if(cleanup){
            Library library=new Library(getTargetContext());String id=files[0].getName().replace(".fb2","");
            List<Library.Note> fixtures=new ArrayList<>();Library.Note chapter=null,part=null;
            for(Library.Note n:library.notes(id)){
                boolean match=n.type.equals("chapter")&&n.title.equals("Prototype check")&&n.body.equals("Saved after restart")
                    ||n.type.equals("hero")&&n.title.equals("Test hero")&&n.body.equals("Description from reader")
                    ||n.type.equals("quote")&&n.title.isEmpty()&&n.body.equals("седьмого")&&n.source==0
                    ||n.type.equals("part")&&n.title.equals("Test part")&&n.body.isEmpty();
                if(match){fixtures.add(n);if(n.type.equals("chapter"))chapter=n;if(n.type.equals("part"))part=n;}
            }
            expect(fixtures.size()==4,"Exactly four known UI test fixtures");
            expect(chapter!=null&&part!=null&&part.id.equals(chapter.parent),"Chapter parent saved by UI");
            String exported=library.markdown(id,real);
            expect(exported.contains("### Test part")&&exported.contains("#### Prototype check"),"Hierarchy exported as Markdown headings");
            expect(exported.contains("> седьмого"),"Selected Cyrillic quote exported");
            for(Library.Note n:fixtures)library.delete(n);
            boolean removed=true;for(Library.Note n:fixtures)removed&=library.note(n.id)==null;
            expect(removed,"Only known test notes cleaned up");library.close();result.putInt("testFixturesRemoved",fixtures.size());
        }
        result.putString("result","PASS: "+checks+" assertions");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("result","FAIL after "+checks+" assertions: "+e);finish(Activity.RESULT_CANCELED,result);}}
}
