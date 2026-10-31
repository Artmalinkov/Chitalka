package ru.devimind.chitalka;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import org.json.*;
import java.io.*;
import java.util.*;

/** Converts FB2 into local, escaped chapter HTML; never passes source markup to WebView. */
public final class Fb2 {
    public static final class Chapter {
        public String title, part, html;
        Chapter(String t, String p, String h) { title=t; part=p; html=h; }
    }
    public static final class Book {
        public String title="Без названия", author="Автор не указан", cover="";
        public final List<Chapter> chapters=new ArrayList<>();
        public final Map<String,String> footnotes=new LinkedHashMap<>();
        public JSONObject json() throws JSONException {
            JSONObject o=new JSONObject().put("title",title).put("author",author).put("cover",cover);
            JSONArray a=new JSONArray();
            for(Chapter c:chapters) a.put(new JSONObject().put("title",c.title).put("part",c.part).put("html",c.html));
            return o.put("chapters",a).put("footnotes",new JSONObject(footnotes));
        }
        public static Book fromJson(String json) throws JSONException {
            JSONObject o=new JSONObject(json); Book b=new Book();
            b.title=o.getString("title"); b.author=o.getString("author"); b.cover=o.optString("cover");
            JSONArray a=o.getJSONArray("chapters");
            for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);b.chapters.add(new Chapter(c.getString("title"),c.getString("part"),c.getString("html")));}
            JSONObject n=o.getJSONObject("footnotes"); Iterator<String> it=n.keys();
            while(it.hasNext()){String k=it.next();b.footnotes.put(k,n.getString(k));} return b;
        }
    }
    private static final class Node {
        String tag; Map<String,String> attrs=new HashMap<>(); List<Object> children=new ArrayList<>();
        Node(String name){tag=name;}
        Node first(String name){for(Object c:children)if(c instanceof Node&&((Node)c).tag.equals(name))return(Node)c;return null;}
        List<Node> all(String name){List<Node> r=new ArrayList<>();for(Object c:children)if(c instanceof Node&&((Node)c).tag.equals(name))r.add((Node)c);return r;}
        String text(){StringBuilder s=new StringBuilder();for(Object c:children)s.append(c instanceof Node?((Node)c).text():c);return s.toString();}
    }
    private final Map<String,String> images=new HashMap<>();
    private Book book;
    public Book parse(InputStream input) throws Exception {
        images.clear();
        XmlPullParser p=Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
        p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
        p.setInput(input,null);
        Deque<Node> stack=new ArrayDeque<>(); Node root=null;
        for(int e=p.nextToken();e!=XmlPullParser.END_DOCUMENT;e=p.nextToken()) {
            if(e==XmlPullParser.DOCDECL)throw new IOException("FB2 с DTD не поддерживается");
            if(e==XmlPullParser.START_TAG) {
                Node n=new Node(p.getName());
                for(int i=0;i<p.getAttributeCount();i++)n.attrs.put(p.getAttributeName(i),p.getAttributeValue(i));
                if(stack.isEmpty()){if(root!=null)throw new IOException("Некорректный XML");root=n;}else stack.peek().children.add(n);
                stack.push(n);
            }else if(e==XmlPullParser.END_TAG){if(!stack.isEmpty())stack.pop();}
            else if(e==XmlPullParser.TEXT||e==XmlPullParser.CDSECT||e==XmlPullParser.ENTITY_REF||e==XmlPullParser.IGNORABLE_WHITESPACE){
                if(!stack.isEmpty()){if(p.getText()==null)throw new IOException("Неизвестная XML-сущность");stack.peek().children.add(p.getText());}
            }
        }
        if(root==null||!root.tag.equals("FictionBook"))throw new IOException("Выберите книгу в формате FB2");
        book=new Book();
        for(Node n:root.all("binary")){
            String mime=n.attrs.getOrDefault("content-type","").toLowerCase(Locale.ROOT);
            if(mime.matches("image/(jpeg|jpg|png|gif|webp)"))images.put(n.attrs.getOrDefault("id",""),"data:"+mime+";base64,"+n.text().replaceAll("\\s+",""));
        }
        Node desc=root.first("description"), info=desc==null?null:desc.first("title-info");
        if(info!=null){
            if(info.first("book-title")!=null)book.title=clean(info.first("book-title").text());
            List<String> authors=new ArrayList<>();
            for(Node a:info.all("author")) {List<String> bits=new ArrayList<>();for(String key:new String[]{"first-name","middle-name","last-name"})if(a.first(key)!=null)bits.add(clean(a.first(key).text()));if(!bits.isEmpty())authors.add(String.join(" ",bits));}
            if(!authors.isEmpty())book.author=String.join(", ",authors);
            Node cover=info.first("coverpage"); if(cover!=null&&cover.first("image")!=null)book.cover=image(cover.first("image"));
        }
        List<Node> bodies=root.all("body");
        for(int i=0;i<bodies.size();i++) {
            Node body=bodies.get(i);
            if(i>0&&!body.attrs.getOrDefault("name","").isEmpty())collectNotes(body);
            else collect(body,"");
        }
        if(book.chapters.isEmpty())throw new IOException("В FB2 не найден текст книги");
        return book;
    }
    private void collectNotes(Node n){
        if(n.tag.equals("section")&&n.attrs.containsKey("id"))book.footnotes.put(n.attrs.get("id"),renderChildren(n));
        for(Object c:n.children)if(c instanceof Node)collectNotes((Node)c);
    }
    private void collect(Node node,String part){
        List<Node> nested=node.all("section"); Node title=node.first("title");
        String name=title==null?"":clean(title.text());
        if(nested.isEmpty()) {
            if(name.isEmpty())name="Раздел "+(book.chapters.size()+1);
            StringBuilder body=new StringBuilder(); for(Object c:node.children)if(c!=title)body.append(render(c));
            book.chapters.add(new Chapter(name,part,body.toString())); return;
        }
        String nextPart=node.tag.equals("section")&&!name.isEmpty()?(part.isEmpty()?name:part+" · "+name):part;
        StringBuilder pending=new StringBuilder();int last=-1;
        for(Object c:node.children){
            if(c==title)continue;
            if(c instanceof Node&&((Node)c).tag.equals("section")){
                int first=book.chapters.size();collect((Node)c,nextPart);
                if(pending.length()>0){book.chapters.get(first).html=pending+book.chapters.get(first).html;pending.setLength(0);}
                last=book.chapters.size()-1;
            }else {String html=render(c);if(last>=0)book.chapters.get(last).html+=html;else pending.append(html);}
        }
    }
    private String image(Node n){String href=n.attrs.getOrDefault("href","");return images.getOrDefault(href.startsWith("#")?href.substring(1):href,"");}
    private String renderChildren(Node n){StringBuilder s=new StringBuilder();for(Object c:n.children)s.append(render(c));return s.toString();}
    private String render(Object value){
        if(value instanceof String)return esc((String)value);
        Node n=(Node)value;String content=renderChildren(n);
        switch(n.tag){
            case "p":case "v":return "<p>"+content+"</p>";
            case "title":return "<h2>"+content+"</h2>";
            case "subtitle":return "<h3>"+content+"</h3>";
            case "strong":return "<strong>"+content+"</strong>";
            case "emphasis":return "<em>"+content+"</em>";
            case "strikethrough":return "<s>"+content+"</s>";
            case "sup":case "sub":return "<"+n.tag+">"+content+"</"+n.tag+">";
            case "cite":case "epigraph":case "poem":return "<blockquote>"+content+"</blockquote>";
            case "text-author":return "<p class='attribution'>"+content+"</p>";
            case "empty-line":return "<div class='space'></div>";
            case "image":String data=image(n);return data.isEmpty()?"":"<img alt='Иллюстрация' src='"+data+"'>";
            case "a":String ref=n.attrs.getOrDefault("href","");return ref.startsWith("#")?"<a href='https://chitalka.invalid/note/"+android.net.Uri.encode(ref.substring(1))+"'>"+content+"</a>":content;
            case "table":case "tr":case "td":case "th":return "<"+n.tag+">"+content+"</"+n.tag+">";
            default:return content;
        }
    }
    static String clean(String s){return s.trim().replaceAll("\\s+"," ");}
    public static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
